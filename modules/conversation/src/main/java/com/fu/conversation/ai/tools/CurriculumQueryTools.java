package com.fu.conversation.ai.tools;

import com.fu.curriculumimport.service.CurriculumStatsService;
import com.fu.curriculumimport.service.embedding.ChatbotFilter;
import com.fu.curriculumimport.service.embedding.EmbeddingService;
import com.fu.curriculumimport.service.embedding.RagContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.TranslationQueryTransformer;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Curriculum chatbot tools — exposed to the LLM via Spring AI function calling.
 *
 * Singleton Spring bean. Per-request scope (courseId / lessonId) is passed via
 * {@link ToolContext} using {@code .toolContext(Map.of("courseId", ..., "lessonId", ...))}
 * on the ChatClient call — NOT stored as instance state, which would be thread-unsafe.
 *
 * Tools:
 *   {@link #searchCurriculum} — semantic vector search (vocab, grammar, examples, dialog)
 *   {@link #countContent}     — SQL COUNT (exact numbers for "bao nhiêu", "có mấy" questions)
 *
 * Bilingual expansion: query language is detected by CJK character ratio.
 * {@link TranslationQueryTransformer} translates the query to the other language
 * (VI→JA or JA→VI). Both queries are searched and results are merged by max similarity.
 */
@Component
@Slf4j
public class CurriculumQueryTools {

    /** ToolContext key for the lesson UUID (may be absent for course-level sessions). */
    public static final String CTX_COURSE_ID = "courseId";
    public static final String CTX_LESSON_ID = "lessonId";

    private final EmbeddingService embeddingService;
    private final CurriculumStatsService statsService;

    /** Translates a Japanese query → Vietnamese for bilingual vector search expansion. */
    private final TranslationQueryTransformer jaToViTransformer;

    /** Translates a Vietnamese/Latin query → Japanese for bilingual vector search expansion. */
    private final TranslationQueryTransformer viToJaTransformer;

    public CurriculumQueryTools(EmbeddingService embeddingService,
                                  CurriculumStatsService statsService,
                                  OpenAiChatModel chatModel) {
        this.embeddingService = embeddingService;
        this.statsService = statsService;

        // Build a shared ChatClient.Builder for the TranslationQueryTransformers.
        // TranslationQueryTransformer makes a cheap single LLM call per translation.
        ChatClient.Builder clientBuilder = ChatClient.builder(chatModel);

        this.jaToViTransformer = TranslationQueryTransformer.builder()
                .chatClientBuilder(clientBuilder)
                .targetLanguage("Vietnamese")
                .build();

        this.viToJaTransformer = TranslationQueryTransformer.builder()
                .chatClientBuilder(clientBuilder)
                .targetLanguage("Japanese")
                .build();
    }

    // ── Tools ──────────────────────────────────────────────────────────────────

    /**
     * Semantic search over curriculum content.
     *
     * Internally applies bilingual expansion: detects query language via CJK character
     * ratio, translates to the other language (VI→JA or JA→VI) using
     * {@link TranslationQueryTransformer}, runs both vector searches, and merges
     * results by content_id (max similarity wins).
     */
    @Tool(description =
            "Tìm kiếm nội dung giáo trình tiếng Nhật theo ngữ nghĩa. "
            + "Dùng khi user hỏi về nghĩa từ vựng, cách dùng ngữ pháp, câu ví dụ, "
            + "hội thoại, hoặc giải thích bài học. "
            + "Không cần gọi cho lời chào, câu hỏi về số lượng (dùng countContent), "
            + "hoặc nội dung rõ ràng nằm ngoài giáo trình.")
    public String searchCurriculum(
            @ToolParam(description = "Từ khóa hoặc câu hỏi cần tìm trong giáo trình") String query,
            @ToolParam(description =
                    "Loại nội dung ưu tiên — truyền danh sách rỗng để tìm tất cả. "
                    + "Hợp lệ: VOCABULARY, GRAMMAR, GRAMMAR_EXPLANATION, DIALOG, EXAMPLE")
            List<String> contentTypes,
            @ToolParam(description =
                    "Số thứ tự bài học muốn tìm (ví dụ: 2 cho 'Bài 2'). "
                    + "Null nếu câu hỏi không đề cập đến bài cụ thể hoặc session đã có bài.")
            Integer lessonNumber,
            ToolContext toolContext) {

        UUID courseId = getUuid(toolContext, CTX_COURSE_ID);
        UUID lessonId = resolveLesson(toolContext, courseId, lessonNumber);

        List<String> types = (contentTypes != null && !contentTypes.isEmpty())
                ? contentTypes
                : List.of("VOCABULARY", "GRAMMAR", "GRAMMAR_EXPLANATION", "DIALOG", "EXAMPLE");

        ChatbotFilter filter = ChatbotFilter.builder()
                .courseId(courseId)
                .lessonId(lessonId)
                .contentTypes(types)
                .similarityThreshold(0.30)
                .topK(8)
                .build();

        String translated = translateQuery(query);
        RagContext rag = (translated != null)
                ? embeddingService.retrieveContextBilingual(query, translated, filter)
                : embeddingService.retrieveContext(query, filter);

        log.debug("searchCurriculum: query='{}' translated='{}' types={} → {} results",
                query, translated, types, rag.getSimilarItems().size());

        if (!rag.hasContext()) {
            return "Không tìm thấy nội dung phù hợp trong giáo trình cho: \"" + query + "\"";
        }
        return formatResults(rag);
    }

    /**
     * Count vocabulary and grammar points in the current lesson or course scope.
     * Returns exact numbers from the database — no hallucination risk.
     *
     * returnDirect is intentionally NOT set so the LLM can wrap the raw counts
     * in a natural, conversational response instead of echoing the raw string.
     */
    @Tool(description =
            "Đếm số từ vựng và điểm ngữ pháp trong bài học hoặc khóa học hiện tại. "
            + "Dùng khi user hỏi 'bao nhiêu từ vựng', 'có mấy ngữ pháp', 'tổng số', v.v.")
    public String countContent(
            @ToolParam(description =
                    "Số thứ tự bài học cần đếm (ví dụ: 2 cho 'Bài 2'). "
                    + "Null nếu muốn đếm toàn khóa học hoặc session đã gắn với bài cụ thể.")
            Integer lessonNumber,
            ToolContext toolContext) {
        UUID courseId = getUuid(toolContext, CTX_COURSE_ID);
        UUID lessonId = resolveLesson(toolContext, courseId, lessonNumber);

        CurriculumStatsService.ContentCount count = statsService.countContent(courseId, lessonId);
        String scope = (lessonNumber != null)
                ? "Bài " + lessonNumber
                : (lessonId != null ? "bài học này" : "toàn khóa học");

        log.debug("countContent: scope={} lessonId={} vocab={} grammar={}",
                scope, lessonId, count.vocabularyCount(), count.grammarCount());

        return "scope=" + scope
                + " | vocabulary=" + count.vocabularyCount()
                + " | grammar_points=" + count.grammarCount();
    }

    // ── Lesson resolution ─────────────────────────────────────────────────────

    /**
     * Resolve the effective lessonId to use for a tool call.
     *
     * Priority:
     *   1. Explicit lessonNumber from the LLM — resolves via entity table (most recent import)
     *   2. Session-level lessonId from ToolContext (set when user started a lesson-scoped session)
     *   3. null → course-level scope
     */
    private UUID resolveLesson(ToolContext toolContext, UUID courseId, Integer lessonNumber) {
        if (lessonNumber != null && courseId != null) {
            UUID resolved = embeddingService.resolveLessonByNumber(courseId, lessonNumber);
            if (resolved != null) {
                log.debug("Resolved lessonNumber={} → lessonId={}", lessonNumber, resolved);
                return resolved;
            }
            log.debug("lessonNumber={} not found, falling back to session lessonId", lessonNumber);
        }
        return getUuid(toolContext, CTX_LESSON_ID);
    }

    // ── Bilingual query expansion ──────────────────────────────────────────────

    /**
     * Detect query language by CJK ratio and translate to the other language using
     * {@link TranslationQueryTransformer}.
     *
     * Returns {@code null} for mixed queries or on translation failure — caller falls
     * back to single-language vector search.
     */
    private String translateQuery(String query) {
        if (query == null || query.isBlank()) return null;
        try {
            long total = query.chars().filter(c -> !Character.isWhitespace(c) && c > 32).count();
            if (total == 0) return null;
            long cjk = query.chars().filter(c ->
                    (c >= 0x3000 && c <= 0x9FFF) || (c >= 0xF900 && c <= 0xFAFF)
            ).count();
            double ratio = (double) cjk / total;

            TranslationQueryTransformer transformer;
            if (ratio >= 0.30)     transformer = jaToViTransformer; // JA → VI
            else if (ratio < 0.05) transformer = viToJaTransformer; // VI → JA
            else return null; // mixed — skip bilingual expansion

            Query translated = transformer.transform(new Query(query));
            if (translated == null || translated.text() == null
                    || translated.text().isBlank()
                    || translated.text().equalsIgnoreCase(query)) {
                return null;
            }
            return translated.text().trim();

        } catch (Exception e) {
            log.debug("Query translation failed: {}", e.getMessage());
            return null;
        }
    }

    // ── Utilities ──────────────────────────────────────────────────────────────

    private String formatResults(RagContext rag) {
        return rag.getSimilarItems().stream()
                .map(item -> {
                    String type = item.getContentType() != null ? item.getContentType() : "UNKNOWN";
                    String text = item.getTextContent() != null ? item.getTextContent() : "";
                    Map<String, String> meta = item.getMetadata() != null
                            ? item.getMetadata() : Map.of();
                    String lesson = meta.containsKey("lesson_title")
                            ? " (Bài: " + meta.get("lesson_title") + ")" : "";
                    return switch (type) {
                        case "VOCABULARY"          -> "[Từ vựng]" + lesson + ": " + text;
                        case "GRAMMAR"             -> "[Ngữ pháp]" + lesson + ": " + text;
                        case "GRAMMAR_EXPLANATION" -> "[Giải thích ngữ pháp]" + lesson + ":\n" + text;
                        case "DIALOG"              -> "[Hội thoại]" + lesson + ":\n" + text;
                        case "EXAMPLE"             -> "[Ví dụ]" + lesson + ":\n" + text;
                        default                    -> "[" + type + "]" + lesson + ": " + text;
                    };
                })
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    private UUID getUuid(ToolContext ctx, String key) {
        if (ctx == null) return null;
        Object val = ctx.getContext().get(key);
        if (val instanceof UUID u) return u;
        if (val instanceof String s && !s.isBlank()) {
            try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
        }
        return null;
    }
}

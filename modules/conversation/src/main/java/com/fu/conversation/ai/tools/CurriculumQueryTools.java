package com.fu.conversation.ai.tools;

import com.fu.curriculumimport.service.CurriculumStatsService;
import com.fu.curriculumimport.service.StructuredCurriculumQueryService;
import com.fu.curriculumimport.service.embedding.ChatbotFilter;
import com.fu.curriculumimport.service.embedding.EmbeddingService;
import com.fu.curriculumimport.service.embedding.RagContext;
import com.fu.infrastructure.learner.LearnerProgressProvider;
import com.fu.infrastructure.learner.LearnerProgressSnapshot;
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
import java.util.Optional;
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
    /** ToolContext key for the authenticated learner — enables progress-aware tool calls. */
    public static final String CTX_USER_ID   = "userId";

    private final EmbeddingService embeddingService;
    private final CurriculumStatsService statsService;
    private final StructuredCurriculumQueryService structuredQueryService;
    private final LearnerProgressProvider learnerProgressProvider;

    /** Translates a Japanese query → Vietnamese for bilingual vector search expansion. */
    private final TranslationQueryTransformer jaToViTransformer;

    /** Translates a Vietnamese/Latin query → Japanese for bilingual vector search expansion. */
    private final TranslationQueryTransformer viToJaTransformer;

    public CurriculumQueryTools(EmbeddingService embeddingService,
                                  CurriculumStatsService statsService,
                                  StructuredCurriculumQueryService structuredQueryService,
                                  LearnerProgressProvider learnerProgressProvider,
                                  OpenAiChatModel chatModel) {
        this.embeddingService = embeddingService;
        this.statsService = statsService;
        this.structuredQueryService = structuredQueryService;
        this.learnerProgressProvider = learnerProgressProvider;

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

        if (query == null || query.isBlank()) {
            log.warn("searchCurriculum: called with blank query — aborting");
            return "Vui lòng cung cấp từ khóa hoặc câu hỏi cụ thể để tìm kiếm trong giáo trình.";
        }

        UUID courseId = getUuid(toolContext, CTX_COURSE_ID);
        UUID lessonId = resolveLesson(toolContext, courseId, lessonNumber);

        List<String> types = (contentTypes != null && !contentTypes.isEmpty())
                ? contentTypes
                : com.fu.common.constant.ContentTypeConstants.DEFAULT_CONTENT_TYPES;

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

    /**
     * Structured (SQL-backed) query against curriculum entity tables.
     *
     * Use this tool when:
     * - User asks to list ALL vocabulary or grammar in a lesson ("liệt kê từ vựng bài 1")
     * - User searches by a specific keyword across vocabulary or grammar
     *   ("tìm từ có nghĩa 'ăn'", "ngữ pháp nào có 〜て?")
     * - Results must be complete and ordered (vector search can miss items or return
     *   results out of curriculum order)
     *
     * Prefer {@link #searchCurriculum} for conceptual/semantic questions about HOW to use
     * a grammar point or WHAT a word means in context. Use THIS tool for listing and searching.
     */
    @Tool(description =
            "Truy vấn danh sách từ vựng hoặc điểm ngữ pháp từ cơ sở dữ liệu giáo trình. "
            + "Dùng khi user muốn liệt kê TẤT CẢ từ vựng/ngữ pháp của một bài học, "
            + "hoặc tìm kiếm theo từ khóa cụ thể trong danh sách (ví dụ: 'tìm từ có nghĩa ăn', "
            + "'ngữ pháp nào chứa て form'). "
            + "KHÔNG dùng cho câu hỏi về cách dùng hay giải thích — dùng searchCurriculum cho đó.")
    public String queryStructuredData(
            @ToolParam(description =
                    "Loại dữ liệu cần truy vấn: VOCABULARY (từ vựng) hoặc GRAMMAR (ngữ pháp)")
            String dataType,
            @ToolParam(description =
                    "Từ khóa tìm kiếm trong danh sách (tên từ, nghĩa, pattern). "
                    + "Null hoặc để trống để lấy toàn bộ danh sách.")
            String keyword,
            @ToolParam(description =
                    "Số thứ tự bài học (ví dụ: 1 cho 'Bài 1'). "
                    + "Null để truy vấn toàn khóa học hoặc khi session đã gắn với bài cụ thể.")
            Integer lessonNumber,
            @ToolParam(description = "Số lượng kết quả tối đa cần trả về (mặc định 20, tối đa 50)")
            Integer limit,
            ToolContext toolContext) {

        UUID courseId = getUuid(toolContext, CTX_COURSE_ID);
        UUID lessonId = resolveLesson(toolContext, courseId, lessonNumber);
        int cap = (limit != null && limit > 0) ? limit : 20;

        boolean isVocab = !"GRAMMAR".equalsIgnoreCase(dataType);
        StructuredCurriculumQueryService.StructuredResult result = isVocab
                ? structuredQueryService.queryVocabulary(courseId, lessonId, keyword, cap)
                : structuredQueryService.queryGrammar(courseId, lessonId, keyword, cap);

        log.debug("queryStructuredData: type={} keyword='{}' lessonId={} → {} rows (total={})",
                dataType, keyword, lessonId, result.rows().size(), result.totalCount());

        if (result.isEmpty()) {
            String scope = lessonNumber != null ? "Bài " + lessonNumber : "khóa học hiện tại";
            String typeName = isVocab ? "từ vựng" : "điểm ngữ pháp";
            return "Không tìm thấy " + typeName + " nào"
                    + (keyword != null && !keyword.isBlank() ? " khớp với \"" + keyword + "\"" : "")
                    + " trong " + scope + ".";
        }

        StringBuilder sb = new StringBuilder();
        String typeName = isVocab ? "Từ vựng" : "Ngữ pháp";
        String scope = lessonId != null ? " (bài học)" : " (khóa học)";
        sb.append(typeName).append(scope).append(" — ").append(result.rows().size());
        if (result.totalCount() > result.rows().size()) {
            sb.append('/').append(result.totalCount()).append(" (đã giới hạn)");
        }
        sb.append(" mục:\n\n");

        int i = 1;
        for (String row : result.rows()) {
            sb.append(i++).append(". ").append(row).append('\n');
        }

        if (result.totalCount() > result.rows().size()) {
            sb.append("\n[Chỉ hiển thị ").append(result.rows().size())
              .append(" / ").append(result.totalCount())
              .append(" mục. Hỏi cụ thể hơn để thu hẹp kết quả.]");
        }

        return sb.toString();
    }

    /**
     * Short-circuits the chat with a canned refusal for questions outside the
     * Japanese-learning domain.
     *
     * <p><b>Why forced tool-call instead of prose refusal:</b> GPT-4o-mini only
     * partially honors "do not answer off-topic" instructions in the system prompt
     * (it answers the off-topic question and <em>then</em> appends a scope note).
     * Tool-selection compliance is materially higher — the LLM either calls this
     * tool or it doesn't, with no in-between state.
     *
     * <p>{@code returnDirect = true} makes Spring AI's {@code ToolCallingManager}
     * return this string directly to the caller, skipping the follow-up LLM call
     * that would otherwise regenerate the assistant message from the tool output.
     */
    @Tool(description =
            "BẮT BUỘC gọi khi câu hỏi KHÔNG thuộc phạm vi học tiếng Nhật / giáo trình. "
            + "Các chủ đề cần gọi tool này: toán học (1+1, phép tính), lập trình (code, "
            + "thuật toán), nấu ăn, chính trị, y tế, tình cảm cá nhân, ngôn ngữ khác "
            + "(trừ khi so sánh để giải thích tiếng Nhật), hoặc yêu cầu tạo nội dung "
            + "không liên quan học tập. "
            + "KHÔNG TỰ TRẢ LỜI các câu hỏi đó — phải gọi tool này thay vì viết câu trả lời.",
            returnDirect = true)
    public String refuseOffTopic(
            @ToolParam(description =
                    "Chủ đề ngoài phạm vi đã phát hiện, viết ngắn bằng tiếng Việt "
                    + "(ví dụ: 'toán học', 'lập trình', 'nấu ăn').")
            String topic) {
        log.info("refuseOffTopic invoked: topic='{}'", topic);
        return "Mình chỉ hỗ trợ câu hỏi về tiếng Nhật và nội dung giáo trình. "
             + "Bạn có câu hỏi nào về bài học không?";
    }

    /**
     * Returns the learner's practice progress snapshot: current lesson, mastery breakdown,
     * weakest items, strongest items, recent activity.
     *
     * <p>The chatbot should call this tool ONLY when the user asks about their own
     * progress, current lesson, weaknesses, or "em đang học đến đâu" style questions.
     * For generic curriculum questions, prefer {@link #searchCurriculum} or
     * {@link #queryStructuredData}.
     */
    @Tool(description =
            "Lấy tiến độ luyện tập của chính người dùng: bài đang học, điểm mastery, "
            + "từ/ngữ pháp yếu nhất, số phiên gần đây. "
            + "CHỈ dùng khi user hỏi về bản thân (ví dụ: 'em đang học bài nào', "
            + "'tiến độ của em thế nào', 'em còn yếu chỗ nào', 'điểm gần nhất của em'). "
            + "KHÔNG dùng cho câu hỏi chung về giáo trình.")
    public String getLearnerProgress(ToolContext toolContext) {
        UUID userId = getUuid(toolContext, CTX_USER_ID);
        if (userId == null) {
            log.debug("getLearnerProgress: no userId in ToolContext — aborting");
            return "Không xác định được người dùng từ phiên chat hiện tại.";
        }
        UUID courseId = getUuid(toolContext, CTX_COURSE_ID);

        Optional<LearnerProgressSnapshot> snap = learnerProgressProvider.getProgress(userId, courseId);
        if (snap.isEmpty() || !snap.get().hasAnyData()) {
            return "Học viên chưa có dữ liệu luyện tập nào trong hệ thống.";
        }
        return formatProgress(snap.get());
    }

    private String formatProgress(LearnerProgressSnapshot s) {
        StringBuilder sb = new StringBuilder();

        LearnerProgressSnapshot.CurrentLessonInfo c = s.currentLesson();
        if (c != null) {
            sb.append("BÀI ĐANG HỌC: ");
            if (c.title() != null) sb.append(c.title());
            if (c.titleJapanese() != null && !c.titleJapanese().isBlank()) {
                sb.append(" (").append(c.titleJapanese()).append(')');
            }
            if (c.orderIndex() != null) sb.append(" — bài số ").append(c.orderIndex());
            sb.append('\n');
            sb.append("  • Số phiên đã luyện: ").append(c.totalSessions())
              .append(" | Điểm cao nhất: ").append(formatScore(c.bestScore()))
              .append(" | Điểm gần nhất: ").append(formatScore(c.lastScore())).append('\n');
            sb.append("  • Mastery từ vựng: ").append(formatScore(c.vocabMastery())).append("%")
              .append(" | Mastery ngữ pháp: ").append(formatScore(c.grammarMastery())).append("%\n");
            if (c.lastPracticedAt() != null) {
                sb.append("  • Lần luyện gần nhất: ").append(c.lastPracticedAt()).append('\n');
            }
        } else {
            sb.append("BÀI ĐANG HỌC: (chưa có bài nào được luyện)\n");
        }

        LearnerProgressSnapshot.CourseProgressInfo cp = s.courseProgress();
        if (cp != null) {
            sb.append('\n').append("TIẾN ĐỘ KHÓA HỌC: ")
              .append(cp.lessonsStarted()).append("/").append(cp.totalLessons())
              .append(" bài đã bắt đầu, ").append(cp.lessonsCompleted())
              .append(" bài đã đạt chuẩn (≥70%)\n");
            sb.append("  • Mastery TB từ vựng toàn khóa: ").append(formatScore(cp.avgVocabMastery())).append("%\n");
            sb.append("  • Mastery TB ngữ pháp toàn khóa: ").append(formatScore(cp.avgGrammarMastery())).append("%\n");
        }

        List<LearnerProgressSnapshot.WeakItem> weak = s.weakItems();
        if (weak != null && !weak.isEmpty()) {
            sb.append('\n').append("YẾU NHẤT (cần luyện thêm):\n");
            for (LearnerProgressSnapshot.WeakItem w : weak) {
                sb.append("  - ").append(w.display())
                  .append(" (").append("VOCABULARY".equals(w.entityType()) ? "từ vựng" : "ngữ pháp")
                  .append(", mastery level ").append(w.masteryLevel()).append(")\n");
            }
        }

        List<LearnerProgressSnapshot.StrongItem> strong = s.strongItems();
        if (strong != null && !strong.isEmpty()) {
            sb.append('\n').append("MẠNH NHẤT:\n");
            for (LearnerProgressSnapshot.StrongItem st : strong) {
                sb.append("  - ").append(st.display())
                  .append(" (level ").append(st.masteryLevel()).append(")\n");
            }
        }

        if (s.recentSessionsLast7d() > 0) {
            sb.append('\n').append("HOẠT ĐỘNG 7 NGÀY QUA: ")
              .append(s.recentSessionsLast7d()).append(" phiên đã hoàn thành\n");
        }

        return sb.toString();
    }

    private String formatScore(java.math.BigDecimal v) {
        return v != null ? v.stripTrailingZeros().toPlainString() : "0";
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
            long total = query.codePoints().filter(cp -> !Character.isWhitespace(cp) && cp > 32).count();
            if (total == 0) return null;
            long cjk = query.codePoints().filter(cp ->
                    (cp >= 0x3000 && cp <= 0x9FFF) || (cp >= 0xF900 && cp <= 0xFAFF)
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

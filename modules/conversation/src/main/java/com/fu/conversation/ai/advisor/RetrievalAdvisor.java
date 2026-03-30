package com.fu.conversation.ai.advisor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.common.ai.PromptTemplateLoader;
import com.fu.curriculumimport.service.embedding.ChatbotFilter;
import com.fu.curriculumimport.service.embedding.EmbeddingService;
import com.fu.curriculumimport.service.embedding.RagContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Retrieval Advisor — First step in the Agentic RAG pipeline.
 *
 * Responsibilities:
 * 1. Analyze query intent via a single LLM call (COUNT/EXPLAIN/LOOKUP/EXAMPLE/GENERAL)
 *    and extract a bilingual translated query in the same call.
 * 2. Route COUNT queries to SQL aggregation (exact numbers, no hallucination).
 * 3. For other intents, narrow the content_type filter (e.g. EXPLAIN → GRAMMAR_EXPLANATION)
 *    and run bilingual vector search (original + translated query merged by max similarity).
 * 4. Apply a quality gate: discard retrieval when best cosine similarity < minMeaningfulSimilarity.
 * 5. Inject context into the system message and store RagContext for downstream advisors.
 *
 * Order: 100 (runs first in the advisor chain).
 */
@Component
@Slf4j
public class RetrievalAdvisor implements BaseAdvisor {

    /** Context key for the RagContext object shared between advisors. */
    public static final String RAG_CONTEXT_KEY = "rag_context";
    /** Context key for the raw text of retrieved documents (for grounding check). */
    public static final String RAG_DOCUMENTS_TEXT_KEY = "rag_documents_text";

    /** Advisor param keys set by CurriculumChatServiceImpl. */
    public static final String PARAM_COURSE_ID = "courseId";
    public static final String PARAM_LESSON_ID = "lessonId";

    // ── Internal types ─────────────────────────────────────────────────────

    private enum QueryLanguage { JAPANESE, VIETNAMESE_OR_LATIN, MIXED }

    /**
     * Result of the query analysis LLM call.
     *
     * @param intent          COUNT | EXPLAIN | LOOKUP | EXAMPLE | GENERAL
     * @param translatedQuery question translated to the other language (null = unknown/mixed)
     * @param countTarget     GRAMMAR | VOCABULARY | ALL | null (COUNT intent only)
     * @param preferredTypes  content_type hints to narrow the vector search
     */
    private record QueryAnalysis(
            String intent,
            String translatedQuery,
            String countTarget,
            List<String> preferredTypes
    ) {
        static QueryAnalysis general() {
            return new QueryAnalysis("GENERAL", null, null, List.of());
        }
    }

    // ── Dependencies ───────────────────────────────────────────────────────

    private final EmbeddingService embeddingService;
    private final ChatRagProperties ragProperties;
    private final ChatClient analyzeClient;
    private final ObjectMapper objectMapper;
    private final PromptTemplateLoader promptLoader;

    public RetrievalAdvisor(EmbeddingService embeddingService, ChatRagProperties ragProperties,
                             OpenAiChatModel chatModel, ObjectMapper objectMapper,
                             PromptTemplateLoader promptLoader) {
        this.embeddingService = embeddingService;
        this.ragProperties = ragProperties;
        this.analyzeClient = ChatClient.builder(chatModel).build();
        this.objectMapper = objectMapper;
        this.promptLoader = promptLoader;
    }

    // ── BaseAdvisor ────────────────────────────────────────────────────────

    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        UUID courseId = getUuidParam(request, PARAM_COURSE_ID);
        UUID lessonId = getUuidParam(request, PARAM_LESSON_ID);

        if (courseId == null) {
            log.debug("RetrievalAdvisor: no courseId — skipping retrieval");
            return request;
        }

        String question = request.prompt().getUserMessage() != null
                ? request.prompt().getUserMessage().getText() : "";

        // ── Step 1: Keyword pre-check for COUNT — fast, zero LLM cost ────────
        // Deterministic patterns ("bao nhiêu", "いくつ") are more reliable than LLM
        // classification and avoid the ~100ms analysis call for obvious cases.
        if (looksLikeCountQuery(question)) {
            return handleCountIntent(request, courseId, lessonId);
        }

        // ── Step 2: Analyze query intent + translation ─────────────────────
        QueryAnalysis analysis = (ragProperties.isQueryAnalysisEnabled() && question.length() > 2)
                ? analyzeQuery(question)
                : fallbackAnalysis(question);

        // LLM may still classify as COUNT for paraphrases not caught by keywords
        if ("COUNT".equals(analysis.intent())) {
            return handleCountIntent(request, courseId, lessonId);
        }

        // ── Step 3: Build typed filter + bilingual retrieval ─────────────────
        ChatbotFilter filter = buildFilter(courseId, lessonId, analysis.preferredTypes());
        RagContext rag = retrieveBilingual(question, analysis.translatedQuery(), filter);

        // ── Step 4: Quality gate ─────────────────────────────────────────────
        double maxSim = rag.getSimilarItems().stream()
                .mapToDouble(RagContext.SimilarItem::getSimilarity)
                .max().orElse(0.0);

        if (maxSim < ragProperties.getMinMeaningfulSimilarity()) {
            log.debug("RetrievalAdvisor: best sim {:.3f} < {:.2f} — discarding weak context for '{}'",
                    maxSim, ragProperties.getMinMeaningfulSimilarity(), question);
            rag = RagContext.empty();
        }

        // ── Step 5: Inject context ────────────────────────────────────────────
        request.context().put(RAG_CONTEXT_KEY, rag);

        if (!rag.hasContext()) {
            log.debug("RetrievalAdvisor: no context for '{}' (intent={})", question, analysis.intent());
            request.context().put(RAG_DOCUMENTS_TEXT_KEY, "");
            return request.mutate()
                    .prompt(request.prompt().augmentSystemMessage(
                            "\n**Lưu ý:** Không tìm thấy nội dung liên quan trong giáo trình. " +
                            "Hãy trả lời: \"Nội dung này chưa có trong giáo trình của bạn.\""))
                    .build();
        }

        String ragText = buildRagContextText(rag);
        request.context().put(RAG_DOCUMENTS_TEXT_KEY, ragText);

        log.debug("RetrievalAdvisor: intent={} types={} items={} maxSim={:.3f}",
                analysis.intent(), analysis.preferredTypes(), rag.getSimilarItems().size(), maxSim);

        return request.mutate()
                .prompt(request.prompt().augmentSystemMessage(
                        "\n**[Nội dung tham khảo từ giáo trình]:**\n\n" + ragText +
                        "\n**Hãy sử dụng CHÍNH XÁC nội dung trên để trả lời. Không bịa đặt thông tin ngoài nội dung tham khảo.**\n"))
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    // ── Query analysis ─────────────────────────────────────────────────────

    /**
     * Single LLM call that returns intent + translated query + preferred content types.
     * Uses gpt-4o-mini at temperature=0 for deterministic classification.
     * Cost: ~$0.0001/call (30 input tokens + 50 output tokens).
     */
    private QueryAnalysis analyzeQuery(String question) {
        try {
            QueryLanguage lang = detectLanguage(question);
            String langLabel = switch (lang) {
                case JAPANESE -> "Japanese";
                case VIETNAMESE_OR_LATIN -> "Vietnamese";
                case MIXED -> "mixed";
            };

            String prompt = promptLoader.render("prompts/query-analysis.st", Map.of(
                    "question", question,
                    "detectedLanguage", langLabel));

            String raw = analyzeClient.prompt()
                    .user(prompt)
                    .options(OpenAiChatOptions.builder()
                            .model(ragProperties.getCheckModel())
                            .maxTokens(150)
                            .temperature(0.0)
                            .build())
                    .call()
                    .content();

            JsonNode node = objectMapper.readTree(cleanJson(raw));

            String intent = node.path("intent").asText("GENERAL");

            String translatedQuery = node.path("translatedQuery").asText(null);
            if ("null".equalsIgnoreCase(translatedQuery) || translatedQuery != null && translatedQuery.isBlank()) {
                translatedQuery = null;
            }

            String countTarget = node.path("countTarget").asText(null);
            if ("null".equalsIgnoreCase(countTarget) || countTarget != null && countTarget.isBlank()) {
                countTarget = null;
            }

            List<String> preferredTypes = new ArrayList<>();
            JsonNode typesNode = node.path("preferredTypes");
            if (typesNode.isArray()) {
                typesNode.forEach(n -> preferredTypes.add(n.asText()));
            }

            log.debug("QueryAnalysis: intent={} translated='{}' types={}", intent, translatedQuery, preferredTypes);
            return new QueryAnalysis(intent, translatedQuery, countTarget, preferredTypes);

        } catch (Exception e) {
            log.warn("QueryAnalysis failed, defaulting to GENERAL: {}", e.getMessage());
            return QueryAnalysis.general();
        }
    }

    /**
     * Fallback when queryAnalysisEnabled=false: detect language and translate only.
     * Preserves the bilingual recall improvement without the full intent routing.
     */
    private QueryAnalysis fallbackAnalysis(String question) {
        if (!ragProperties.isBilingualQueryEnabled()
                || question == null || question.strip().length() <= 2) {
            return QueryAnalysis.general();
        }
        QueryLanguage lang = detectLanguage(question);
        if (lang == QueryLanguage.MIXED) return QueryAnalysis.general();

        String targetLang = lang == QueryLanguage.JAPANESE ? "Vietnamese" : "Japanese";
        try {
            String prompt = "Translate to " + targetLang + ": \"" + question
                    + "\"\nReply with ONLY the translation, no explanation.";
            String translated = analyzeClient.prompt()
                    .user(prompt)
                    .options(OpenAiChatOptions.builder()
                            .model(ragProperties.getCheckModel())
                            .maxTokens(100)
                            .temperature(0.1)
                            .build())
                    .call()
                    .content();
            if (translated != null) translated = translated.trim();
            return new QueryAnalysis("GENERAL", translated, null, List.of());
        } catch (Exception e) {
            log.debug("Fallback translation failed: {}", e.getMessage());
            return QueryAnalysis.general();
        }
    }

    // ── COUNT intent handler ───────────────────────────────────────────────

    /**
     * Handle COUNT intent: run SQL COUNT instead of vector search.
     * Injects exact numbers so the main LLM cannot hallucinate counts.
     *
     * The downstream RelevanceCheckAdvisor and GroundingAdvisor both skip when
     * RagContext.hasContext() is false, so the count context passes through untouched.
     */
    private ChatClientRequest handleCountIntent(ChatClientRequest request,
                                                 UUID courseId, UUID lessonId) {
        EmbeddingService.ContentCount count = embeddingService.countContent(courseId, lessonId);

        String scope = lessonId != null ? "bài học hiện tại" : "khóa học hiện tại";
        String countContext = "Phạm vi: " + scope
                + "\nSố từ vựng: " + count.vocabularyCount()
                + "\nSố điểm ngữ pháp: " + count.grammarCount();

        log.debug("RetrievalAdvisor: COUNT → vocab={}, grammar={} (lesson={}, course={})",
                count.vocabularyCount(), count.grammarCount(), lessonId, courseId);

        request.context().put(RAG_CONTEXT_KEY, RagContext.empty());
        request.context().put(RAG_DOCUMENTS_TEXT_KEY, countContext);

        return request.mutate()
                .prompt(request.prompt().augmentSystemMessage(
                        "\n**[Số liệu chính xác từ hệ thống]:**\n" + countContext
                        + "\nHãy trả lời dựa trên số liệu chính xác trên. Không tự thêm hay đoán số liệu.\n"))
                .build();
    }

    // ── Filter + retrieval helpers ─────────────────────────────────────────

    /**
     * Build a ChatbotFilter using the preferred content types from query analysis.
     * Falls back to all content types when preferredTypes is empty (GENERAL intent).
     */
    private ChatbotFilter buildFilter(UUID courseId, UUID lessonId, List<String> preferredTypes) {
        List<String> types = (preferredTypes != null && !preferredTypes.isEmpty())
                ? preferredTypes
                : List.of("VOCABULARY", "GRAMMAR", "GRAMMAR_EXPLANATION", "DIALOG", "EXAMPLE");
        return ChatbotFilter.builder()
                .courseId(courseId)
                .lessonId(lessonId)
                .contentTypes(types)
                .similarityThreshold(0.30)
                .topK(8)
                .build();
    }

    /**
     * Run bilingual retrieval when translatedQuery is available, single retrieval otherwise.
     */
    private RagContext retrieveBilingual(String question, String translatedQuery, ChatbotFilter filter) {
        if (translatedQuery != null && !translatedQuery.isBlank()
                && !translatedQuery.equalsIgnoreCase(question)) {
            return embeddingService.retrieveContextBilingual(question, translatedQuery, filter);
        }
        return embeddingService.retrieveContext(question, filter);
    }

    // ── Language detection ─────────────────────────────────────────────────

    /**
     * Classify query language by CJK character ratio.
     * Used to inform the analysis prompt and as a hint for the LLM.
     */
    private QueryLanguage detectLanguage(String text) {
        if (text == null || text.isBlank()) return QueryLanguage.MIXED;
        long total = text.chars().filter(c -> !Character.isWhitespace(c) && c > 32).count();
        if (total == 0) return QueryLanguage.MIXED;
        long cjk = text.chars().filter(c ->
                (c >= 0x3000 && c <= 0x9FFF) ||   // CJK unified ideographs, hiragana, katakana
                (c >= 0xF900 && c <= 0xFAFF)       // CJK compatibility ideographs
        ).count();
        double ratio = (double) cjk / total;
        if (ratio >= 0.30) return QueryLanguage.JAPANESE;
        if (ratio <  0.05) return QueryLanguage.VIETNAMESE_OR_LATIN;
        return QueryLanguage.MIXED;
    }

    // ── Utilities ──────────────────────────────────────────────────────────

    /**
     * Keyword-based COUNT detection — runs before the LLM analysis call.
     *
     * Covers Vietnamese and Japanese count patterns commonly used by learners.
     * Case-insensitive; does not need to be exhaustive — the LLM analysis is the
     * fallback for unusual phrasings.
     */
    private static final List<String> COUNT_KEYWORDS = List.of(
            "bao nhiêu", "có mấy", "tổng số", "tổng cộng",
            "いくつ", "何個", "何つ", "いくつあ", "何文法", "何語"
    );

    private boolean looksLikeCountQuery(String question) {
        if (question == null || question.isBlank()) return false;
        String lower = question.toLowerCase();
        return COUNT_KEYWORDS.stream().anyMatch(lower::contains);
    }

    private String buildRagContextText(RagContext rag) {
        return rag.getSimilarItems().stream()
                .map(item -> {
                    String type = item.getContentType() != null ? item.getContentType() : "UNKNOWN";
                    String text = item.getTextContent() != null ? item.getTextContent() : "";
                    var meta = item.getMetadata() != null ? item.getMetadata() : Map.<String, String>of();
                    String lessonInfo = meta.containsKey("lesson_title")
                            ? " (Bài: " + meta.get("lesson_title") + ")" : "";
                    return switch (type) {
                        case "VOCABULARY"         -> "Từ vựng: " + text + lessonInfo;
                        case "GRAMMAR"            -> "Ngữ pháp: " + text + lessonInfo;
                        case "GRAMMAR_EXPLANATION"-> "Giải thích ngữ pháp" + lessonInfo + ":\n" + text;
                        case "DIALOG"             -> "Hội thoại mẫu" + lessonInfo + ":\n" + text;
                        case "EXAMPLE"            -> "Ví dụ" + lessonInfo + ":\n" + text;
                        default                   -> "[" + type + "] " + text + lessonInfo;
                    };
                })
                .collect(Collectors.joining("\n\n"));
    }

    private String cleanJson(String raw) {
        if (raw == null) return "{}";
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").trim();
        }
        return cleaned;
    }

    private UUID getUuidParam(ChatClientRequest request, String key) {
        Object val = request.context().get(key);
        if (val instanceof UUID uuid) return uuid;
        if (val instanceof String s && !s.isBlank()) {
            try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
        }
        return null;
    }
}

package com.fu.conversation.ai.advisor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.common.ai.PromptTemplateLoader;
import com.fu.common.ai.PromptUtils;
import com.fu.curriculumimport.service.CurriculumStatsService;
import com.fu.curriculumimport.service.embedding.ChatbotFilter;
import com.fu.curriculumimport.service.embedding.EmbeddingService;
import com.fu.curriculumimport.service.embedding.RagContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.stereotype.Component;

import org.springframework.ai.chat.messages.MessageType;

import java.util.ArrayList;
import java.util.HashMap;
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
 * 5. Inject context into the system message and store {@link Document} list under
 *    {@link RetrievalAugmentationAdvisor#DOCUMENT_CONTEXT} for source references.
 *
 * Order: 100 (runs after MessageChatMemoryAdvisor).
 */
@Component
@Slf4j
public class RetrievalAdvisor implements BaseAdvisor {

    /** Context key for the RagContext object shared between advisors. */
    public static final String RAG_CONTEXT_KEY = "rag_context";
    /** Context key for the raw text of retrieved documents (for grounding check). */
    public static final String RAG_DOCUMENTS_TEXT_KEY = "rag_documents_text";
    /** Context key for the query intent (COUNT|LOOKUP|EXPLAIN|EXAMPLE|GENERAL). */
    public static final String RAG_INTENT_KEY = "rag_intent";
    /** Context key for the retrieval strategy used (HYBRID|HYDE|BILINGUAL|BROWSE|COUNT|NONE). */
    public static final String RAG_STRATEGY_KEY = "rag_retrieval_strategy";
    /** Marker prefix injected into the system message when RAG context is present.
     *  RelevanceCheckAdvisor uses this to locate and strip the block when irrelevant. */
    public static final String RAG_SYSTEM_MESSAGE_MARKER = "\n**[Nội dung tham khảo từ giáo trình]:**";

    /** Advisor param keys set by CurriculumChatServiceImpl. */
    public static final String PARAM_COURSE_ID = "courseId";
    public static final String PARAM_LESSON_ID = "lessonId";

    // ── Internal types ─────────────────────────────────────────────────────

    private enum QueryLanguage { JAPANESE, VIETNAMESE_OR_LATIN, MIXED }

    /**
     * Result of the query analysis LLM call.
     *
     * @param intent          COUNT | EXPLAIN | LOOKUP | EXAMPLE | GENERAL
     * @param searchKeyword   core term/phrase extracted from the question for vector embedding
     *                        (question phrasing stripped — e.g. "〜ている" not "〜ている nghĩa là gì?")
     * @param translatedQuery searchKeyword translated to the other language (null = unknown/mixed)
     * @param countTarget     GRAMMAR | VOCABULARY | ALL | null (COUNT intent only)
     * @param preferredTypes  content_type hints to narrow the vector search
     */
    private record QueryAnalysis(
            String intent,
            String searchKeyword,
            String translatedQuery,
            String countTarget,
            List<String> preferredTypes
    ) {
        static QueryAnalysis general() {
            return new QueryAnalysis("GENERAL", null, null, null, List.of());
        }
    }

    // ── Dependencies ───────────────────────────────────────────────────────

    private final EmbeddingService embeddingService;
    private final CurriculumStatsService statsService;
    private final ChatRagProperties ragProperties;
    private final ChatClient analyzeClient;
    private final ObjectMapper objectMapper;
    private final PromptTemplateLoader promptLoader;

    public RetrievalAdvisor(EmbeddingService embeddingService,
                             CurriculumStatsService statsService,
                             ChatRagProperties ragProperties,
                             OpenAiChatModel chatModel, ObjectMapper objectMapper,
                             PromptTemplateLoader promptLoader) {
        this.embeddingService = embeddingService;
        this.statsService = statsService;
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
            request.context().put(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT, List.of());
            return request;
        }

        String question = request.prompt().getUserMessage() != null
                ? request.prompt().getUserMessage().getText() : "";

        // ── Step 1: Keyword pre-check for COUNT — fast, zero LLM cost ────────
        if (looksLikeCountQuery(question)) {
            request.context().put(RAG_INTENT_KEY, "COUNT");
            request.context().put(RAG_STRATEGY_KEY, "COUNT");
            return handleCountIntent(request, courseId, lessonId);
        }

        // ── Step 2: Analyze query intent + translation ─────────────────────
        // Include recent conversation history so pronoun/reference resolution works
        // (e.g. "Những ngữ pháp đó là gì?" → resolves "đó" from prior turn)
        String recentHistory = extractRecentHistory(request, 3);
        QueryAnalysis analysis = (ragProperties.isQueryAnalysisEnabled() && question.length() > 2)
                ? analyzeQuery(question, recentHistory)
                : fallbackAnalysis(question);

        // LLM may still classify as COUNT for paraphrases not caught by keywords
        if ("COUNT".equals(analysis.intent())) {
            request.context().put(RAG_INTENT_KEY, "COUNT");
            request.context().put(RAG_STRATEGY_KEY, "COUNT");
            return handleCountIntent(request, courseId, lessonId);
        }

        // GENERAL intent = greeting, meta-question, chitchat — retrieval not needed.
        // Skip vector search entirely: the AI will answer from system prompt + tools.
        if ("GENERAL".equals(analysis.intent())) {
            log.debug("RetrievalAdvisor: GENERAL intent — skipping retrieval for '{}'", question);
            request.context().put(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT, List.of());
            request.context().put(RAG_INTENT_KEY, "GENERAL");
            request.context().put(RAG_STRATEGY_KEY, "NONE");
            return request;
        }

        // ── Step 3: Build typed filter + retrieval ───────────────────────────
        ChatbotFilter filter = buildFilter(courseId, lessonId, analysis.preferredTypes());

        // Browse routing: EXAMPLE intent with no specific keyword → category browse.
        // e.g. "ví dụ hội thoại?" / "cho tôi xem ví dụ" → user wants to see examples
        // by type, not search for a specific term. Using cosine similarity here fails
        // because the query is in meta-language ("ví dụ") while content is Japanese text.
        // browseByType() fetches by content_type without a similarity gate.
        boolean isBrowseQuery = "EXAMPLE".equals(analysis.intent())
                && (analysis.searchKeyword() == null || analysis.searchKeyword().isBlank());
        if (isBrowseQuery) {
            log.debug("RetrievalAdvisor: EXAMPLE browse (no keyword) — browseByType for '{}'", question);
            request.context().put(RAG_INTENT_KEY, "EXAMPLE");
            request.context().put(RAG_STRATEGY_KEY, "BROWSE");
            RagContext rag = embeddingService.browseByType(filter);
            request.context().put(RAG_CONTEXT_KEY, rag);
            request.context().put(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT, toDocuments(rag));
            if (!rag.hasContext()) {
                request.context().put(RAG_DOCUMENTS_TEXT_KEY, "");
                return request.mutate()
                        .prompt(request.prompt().augmentSystemMessage(
                                "\n**Lưu ý:** Chưa có dữ liệu ví dụ/hội thoại trong giáo trình này."))
                        .build();
            }
            String ragText = buildRagContextText(rag);
            request.context().put(RAG_DOCUMENTS_TEXT_KEY, ragText);
            return request.mutate()
                    .prompt(request.prompt().augmentSystemMessage(
                            RAG_SYSTEM_MESSAGE_MARKER + "\n\n" + ragText +
                            "\n**Hãy sử dụng CHÍNH XÁC nội dung trên để trả lời. Không bịa đặt thông tin ngoài nội dung tham khảo.**\n"))
                    .build();
        }

        // ── Step 3b: Retrieval strategy ───────────────────────────────────────
        // Precedence (highest to lowest):
        //   1. Hybrid (dense HNSW + sparse pg_trgm via RRF) — best recall + precision
        //      Dense query: HyDE text when applicable, else searchKeyword
        //      Sparse query: always the original searchKeyword (exact-term precision)
        //   2. HyDE-only (when hybrid disabled) — embeds hypothetical answer
        //   3. Standard bilingual retrieval — baseline (VI↔JA vector expansion)
        String searchQuery = (analysis.searchKeyword() != null && !analysis.searchKeyword().isBlank())
                ? analysis.searchKeyword()
                : question;

        // Determine dense query: use HyDE text for LOOKUP/EXPLAIN when available
        boolean hydeApplicable = ragProperties.isHydeEnabled()
                && ("LOOKUP".equals(analysis.intent()) || "EXPLAIN".equals(analysis.intent()))
                && analysis.searchKeyword() != null && !analysis.searchKeyword().isBlank();

        String denseQuery = searchQuery;
        if (hydeApplicable) {
            String hydeText = generateHyDE(analysis.searchKeyword(), analysis.intent());
            if (hydeText != null) {
                denseQuery = hydeText;
                log.debug("HyDE: '{}' → '{}'", analysis.searchKeyword(), truncate(hydeText, 80));
            }
        }

        RagContext rag;
        String strategy;
        if (ragProperties.isHybridEnabled()) {
            // Hybrid: dense (possibly HyDE) + sparse (original keyword) merged by RRF
            String sparseKeyword = analysis.searchKeyword() != null ? analysis.searchKeyword() : searchQuery;
            rag = embeddingService.retrieveHybridContext(denseQuery, sparseKeyword, filter);
            strategy = "HYBRID";
            log.debug("RetrievalAdvisor: HYBRID intent={} dense='{}' sparse='{}'",
                    analysis.intent(), truncate(denseQuery, 60), sparseKeyword);
        } else if (!denseQuery.equals(searchQuery)) {
            // HyDE only (hybrid disabled) — single dense search with HyDE embedding
            rag = embeddingService.retrieveContext(denseQuery, filter);
            strategy = "HYDE";
        } else {
            // Standard bilingual retrieval
            rag = retrieveBilingual(searchQuery, analysis.translatedQuery(), filter);
            strategy = "BILINGUAL";
        }
        request.context().put(RAG_INTENT_KEY, analysis.intent());
        request.context().put(RAG_STRATEGY_KEY, strategy);

        // ── Step 4: Quality gate ─────────────────────────────────────────────
        double maxSim = rag.getSimilarItems().stream()
                .mapToDouble(RagContext.SimilarItem::getSimilarity)
                .max().orElse(0.0);

        double simThreshold = ragProperties.getThresholdForIntent(analysis.intent());
        if (maxSim < simThreshold) {
            log.debug("RetrievalAdvisor: best sim {} < {} (intent={}) — discarding weak context for '{}'",
                    String.format("%.3f", maxSim),
                    String.format("%.2f", simThreshold),
                    analysis.intent(),
                    question);
            rag = RagContext.empty();
        }

        // ── Step 5: Store Documents + inject context ──────────────────────────
        request.context().put(RAG_CONTEXT_KEY, rag);
        request.context().put(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT, toDocuments(rag));

        if (!rag.hasContext()) {
            log.debug("RetrievalAdvisor: no context for '{}' (intent={})", question, analysis.intent());
            request.context().put(RAG_DOCUMENTS_TEXT_KEY, "");
            // Soft hint — let the AI decide: call searchCurriculum tool for another attempt,
            // or inform the user if the topic is genuinely outside the curriculum.
            // Do NOT mandate a specific phrase — that would block tool calls.
            return request.mutate()
                    .prompt(request.prompt().augmentSystemMessage(
                            "\n**Lưu ý:** Vector search không tìm thấy nội dung liên quan trong giáo trình " +
                            "cho câu hỏi này. Hãy thử gọi công cụ `searchCurriculum` với từ khóa cụ thể hơn " +
                            "nếu cần, hoặc thông báo lịch sự nếu chủ đề thực sự nằm ngoài giáo trình."))
                    .build();
        }

        String ragText = buildRagContextText(rag);
        request.context().put(RAG_DOCUMENTS_TEXT_KEY, ragText);

        log.debug("RetrievalAdvisor: intent={} types={} items={} maxSim={}",
                analysis.intent(), analysis.preferredTypes(), rag.getSimilarItems().size(),
                String.format("%.3f", maxSim));

        return request.mutate()
                .prompt(request.prompt().augmentSystemMessage(
                        RAG_SYSTEM_MESSAGE_MARKER + "\n\n" + ragText +
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
     * Includes recent conversation history for pronoun/reference resolution.
     * Uses gpt-4o-mini at temperature=0 for deterministic classification.
     * Cost: ~$0.0001/call (30 input tokens + 50 output tokens).
     */
    private QueryAnalysis analyzeQuery(String question, String recentHistory) {
        try {
            QueryLanguage lang = detectLanguage(question);
            String langLabel = switch (lang) {
                case JAPANESE -> "Japanese";
                case VIETNAMESE_OR_LATIN -> "Vietnamese";
                case MIXED -> "mixed";
            };

            String prompt = promptLoader.render("prompts/query-analysis.st", Map.of(
                    "question", question,
                    "detectedLanguage", langLabel,
                    "recentHistory", recentHistory.isBlank() ? "(none)" : recentHistory));

            String raw = analyzeClient.prompt()
                    .user(prompt)
                    .options(OpenAiChatOptions.builder()
                            .model(ragProperties.getCheckModel())
                            .maxTokens(200)
                            .temperature(0.0)
                            .build())
                    .call()
                    .content();

            JsonNode node = objectMapper.readTree(PromptUtils.cleanMarkdownFences(raw, "{}"));

            String intent = node.path("intent").asText("GENERAL");

            String searchKeyword = node.path("searchKeyword").asText(null);
            if (searchKeyword != null && (searchKeyword.isBlank() || "null".equalsIgnoreCase(searchKeyword))) {
                searchKeyword = null;
            }

            String translatedQuery = node.path("translatedQuery").asText(null);
            if (translatedQuery != null && (translatedQuery.isBlank() || "null".equalsIgnoreCase(translatedQuery))) {
                translatedQuery = null;
            }

            String countTarget = node.path("countTarget").asText(null);
            if (countTarget != null && (countTarget.isBlank() || "null".equalsIgnoreCase(countTarget))) {
                countTarget = null;
            }

            List<String> preferredTypes = new ArrayList<>();
            JsonNode typesNode = node.path("preferredTypes");
            if (typesNode.isArray()) {
                typesNode.forEach(n -> preferredTypes.add(n.asText()));
            }

            log.debug("QueryAnalysis: intent={} keyword='{}' translated='{}' types={}",
                    intent, searchKeyword, translatedQuery, preferredTypes);
            return new QueryAnalysis(intent, searchKeyword, translatedQuery, countTarget, preferredTypes);

        } catch (Exception e) {
            log.warn("QueryAnalysis failed, defaulting to GENERAL: {}", e.getMessage());
            return QueryAnalysis.general();
        }
    }

    /**
     * Fallback when queryAnalysisEnabled=false: detect language and translate only.
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
            // fallback: no keyword extraction — searchKeyword=null falls back to raw question
            return new QueryAnalysis("GENERAL", null, translated, null, List.of());
        } catch (Exception e) {
            log.debug("Fallback translation failed: {}", e.getMessage());
            return QueryAnalysis.general();
        }
    }

    // ── HyDE — Hypothetical Document Embedding ────────────────────────────

    /**
     * Generate a short hypothetical curriculum entry that would answer the query about
     * {@code searchKeyword}. The generated text mirrors the format of stored documents
     * (Japanese term + Vietnamese explanation + example), so its embedding lands in the
     * same vector-space cluster as real curriculum content.
     *
     * Returns {@code null} on failure so the caller falls back to raw-keyword retrieval.
     *
     * @param searchKeyword cleaned term (question phrasing already stripped)
     * @param intent        LOOKUP or EXPLAIN — shapes the generation style
     */
    private String generateHyDE(String searchKeyword, String intent) {
        try {
            String instruction = "EXPLAIN".equals(intent)
                    ? "Viết một đoạn giải thích ngắn (2-3 câu) cho điểm ngữ pháp tiếng Nhật sau đây. "
                      + "Bao gồm cấu trúc (pattern), nghĩa tiếng Việt, và một ví dụ ngắn. "
                      + "Viết theo phong cách sách giáo khoa. CHỈ viết nội dung, không thêm giải thích.\n"
                      + "Điểm ngữ pháp: " + searchKeyword
                    : "Viết một mục từ điển ngắn cho từ/cụm từ tiếng Nhật sau đây. "
                      + "Bao gồm: từ (kèm furigana), nghĩa tiếng Việt, loại từ, một câu ví dụ. "
                      + "Viết theo phong cách sách giáo khoa. CHỈ viết nội dung, không thêm giải thích.\n"
                      + "Từ: " + searchKeyword;

            String hydeText = analyzeClient.prompt()
                    .user(instruction)
                    .options(OpenAiChatOptions.builder()
                            .model(ragProperties.getCheckModel())
                            .maxTokens(150)
                            .temperature(0.1)
                            .build())
                    .call()
                    .content();

            return (hydeText != null && !hydeText.isBlank()) ? hydeText.trim() : null;
        } catch (Exception e) {
            log.debug("HyDE generation failed for '{}': {}", searchKeyword, e.getMessage());
            return null;
        }
    }

    // ── COUNT intent handler ───────────────────────────────────────────────

    private ChatClientRequest handleCountIntent(ChatClientRequest request,
                                                 UUID courseId, UUID lessonId) {
        // Use CurriculumStatsService (entity join tables) — source of truth.
        // EmbeddingService.countContent() counts rows in the vector store, which
        // can under-count when metadata is missing or embeddings are not yet generated.
        CurriculumStatsService.ContentCount count = statsService.countContent(courseId, lessonId);

        String scope = lessonId != null ? "bài học hiện tại" : "khóa học hiện tại";
        String countContext = "Phạm vi: " + scope
                + "\nSố từ vựng: " + count.vocabularyCount()
                + "\nSố điểm ngữ pháp: " + count.grammarCount();

        log.debug("RetrievalAdvisor: COUNT → vocab={}, grammar={} (lesson={}, course={})",
                count.vocabularyCount(), count.grammarCount(), lessonId, courseId);

        request.context().put(RAG_CONTEXT_KEY, RagContext.empty());
        request.context().put(RAG_DOCUMENTS_TEXT_KEY, countContext);
        request.context().put(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT, List.of());

        return request.mutate()
                .prompt(request.prompt().augmentSystemMessage(
                        "\n**[Số liệu chính xác từ hệ thống]:**\n" + countContext
                        + "\nHãy trả lời dựa trên số liệu chính xác trên. Không tự thêm hay đoán số liệu.\n"))
                .build();
    }

    // ── Filter + retrieval helpers ─────────────────────────────────────────

    private ChatbotFilter buildFilter(UUID courseId, UUID lessonId, List<String> preferredTypes) {
        List<String> types = (preferredTypes != null && !preferredTypes.isEmpty())
                ? preferredTypes
                : com.fu.common.constant.ContentTypeConstants.DEFAULT_CONTENT_TYPES;
        return ChatbotFilter.builder()
                .courseId(courseId)
                .lessonId(lessonId)
                .contentTypes(types)
                .similarityThreshold(ragProperties.getRetrievalSimilarityFloor())
                .topK(ragProperties.getRetrievalTopK())
                .build();
    }

    private RagContext retrieveBilingual(String question, String translatedQuery, ChatbotFilter filter) {
        if (translatedQuery != null && !translatedQuery.isBlank()
                && !translatedQuery.equalsIgnoreCase(question)) {
            return embeddingService.retrieveContextBilingual(question, translatedQuery, filter);
        }
        return embeddingService.retrieveContext(question, filter);
    }

    // ── Language detection ─────────────────────────────────────────────────

    private QueryLanguage detectLanguage(String text) {
        if (text == null || text.isBlank()) return QueryLanguage.MIXED;
        long total = text.codePoints()
                .filter(cp -> !Character.isWhitespace(cp) && cp > 32)
                .count();
        if (total == 0) return QueryLanguage.MIXED;
        long cjk = text.codePoints().filter(cp ->
                (cp >= 0x3000 && cp <= 0x9FFF) ||
                (cp >= 0xF900 && cp <= 0xFAFF)
        ).count();
        double ratio = (double) cjk / total;
        if (ratio >= 0.30) return QueryLanguage.JAPANESE;
        if (ratio <  0.05) return QueryLanguage.VIETNAMESE_OR_LATIN;
        return QueryLanguage.MIXED;
    }

    // ── Utilities ──────────────────────────────────────────────────────────

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

    /**
     * Convert {@link RagContext.SimilarItem} list to Spring AI {@link Document} objects
     * so that {@code CurriculumChatServiceImpl.buildSourcesFromDocuments()} can read them
     * from {@link RetrievalAugmentationAdvisor#DOCUMENT_CONTEXT}.
     */
    private List<Document> toDocuments(RagContext rag) {
        return rag.getSimilarItems().stream()
                .map(item -> {
                    Map<String, Object> meta = new HashMap<>(
                            item.getMetadata() != null ? item.getMetadata() : Map.of());
                    meta.put("contentType", item.getContentType() != null ? item.getContentType() : "");
                    meta.put("similarity", item.getSimilarity());
                    return new Document(
                            item.getTextContent() != null ? item.getTextContent() : "",
                            meta);
                })
                .collect(Collectors.toList());
    }

    /**
     * Extract the last {@code maxExchanges} conversation turns (user + assistant pairs)
     * from the prompt — already injected by MessageChatMemoryAdvisor (order=10).
     *
     * Excludes the current user question (last message) and all system messages.
     * Used by {@link #analyzeQuery} to resolve pronouns/references in follow-up questions.
     */
    private String extractRecentHistory(ChatClientRequest request, int maxExchanges) {
        var messages = request.prompt().getInstructions();
        if (messages == null || messages.size() <= 1) return "";

        // Filter to USER + ASSISTANT messages only, skip SYSTEM
        var conversation = messages.stream()
                .filter(m -> m.getMessageType() == MessageType.USER
                        || m.getMessageType() == MessageType.ASSISTANT)
                .collect(Collectors.toList());

        // Drop last message — that is the current user question being processed
        if (!conversation.isEmpty()
                && conversation.get(conversation.size() - 1).getMessageType() == MessageType.USER) {
            conversation = conversation.subList(0, conversation.size() - 1);
        }

        if (conversation.isEmpty()) return "";

        int start = Math.max(0, conversation.size() - maxExchanges * 2);
        var recent = conversation.subList(start, conversation.size());

        StringBuilder sb = new StringBuilder();
        for (var m : recent) {
            String role = m.getMessageType() == MessageType.USER ? "User" : "Assistant";
            String text = m.getText() != null ? m.getText() : "";
            sb.append(role).append(": ").append(truncate(text, 300)).append("\n");
        }
        return sb.toString().trim();
    }

    private String truncate(String text, int maxChars) {
        return com.fu.common.util.TextUtils.truncate(text, maxChars, "...");
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

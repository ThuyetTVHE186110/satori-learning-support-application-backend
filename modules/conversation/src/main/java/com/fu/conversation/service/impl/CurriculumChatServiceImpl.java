package com.fu.conversation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.common.ai.PromptTemplateLoader;
import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.conversation.ai.advisor.GroundingAdvisor;
import com.fu.conversation.ai.advisor.RelevanceCheckAdvisor;
import com.fu.conversation.ai.advisor.RetrievalAdvisor;
import com.fu.conversation.ai.memory.CurriculumChatMemoryRepository;
import com.fu.conversation.ai.tools.CurriculumQueryTools;
import com.fu.conversation.config.ChatProperties;
import com.fu.conversation.service.ChatRateLimiter;
import com.fu.conversation.dto.response.ChatHistoryItemResponse;
import com.fu.conversation.dto.response.ChatMessageResponse;
import com.fu.conversation.dto.response.ChatSessionResponse;
import com.fu.conversation.dto.response.ChatSourceReference;
import com.fu.conversation.entity.ChatSession;
import com.fu.conversation.repository.ChatMessageRepository;
import com.fu.conversation.repository.ChatSessionRepository;
import com.fu.conversation.service.ChatHistoryManager;
import com.fu.conversation.service.CurriculumChatService;
import com.fu.conversation.service.RagEvaluator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class CurriculumChatServiceImpl implements CurriculumChatService {

    // Advisor execution order — lower = runs first in before(), last in after()
    private static final int MEMORY_ADVISOR_ORDER    = 10;

    private final ChatClient chatClient;
    /** Lightweight client used only for title generation — no advisors, minimal tokens. */
    private final ChatClient titleChatClient;
    private final ChatProperties chatProperties;
    private final ChatRateLimiter chatRateLimiter;
    private final CurriculumQueryTools curriculumQueryTools;
    private final PromptTemplateLoader promptLoader;
    private final ChatHistoryManager historyManager;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ObjectMapper objectMapper;
    private final RagEvaluator ragEvaluator;
    private final java.util.concurrent.Executor conversationExecutor;

    public CurriculumChatServiceImpl(OpenAiChatModel chatModel,
                                      ChatProperties chatProperties,
                                      ChatRateLimiter chatRateLimiter,
                                      CurriculumChatMemoryRepository chatMemoryRepository,
                                      RetrievalAdvisor retrievalAdvisor,
                                      RelevanceCheckAdvisor relevanceCheckAdvisor,
                                      GroundingAdvisor groundingAdvisor,
                                      ChatHistoryManager historyManager,
                                      ChatSessionRepository chatSessionRepository,
                                      ChatMessageRepository chatMessageRepository,
                                      ObjectMapper objectMapper,
                                      PromptTemplateLoader promptLoader,
                                      CurriculumQueryTools curriculumQueryTools,
                                      RagEvaluator ragEvaluator,
                                      @org.springframework.beans.factory.annotation.Qualifier("conversationExecutor") java.util.concurrent.Executor conversationExecutor) {
        this.chatProperties = chatProperties;
        this.chatRateLimiter = chatRateLimiter;
        this.curriculumQueryTools = curriculumQueryTools;
        this.historyManager = historyManager;
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.objectMapper = objectMapper;
        this.promptLoader = promptLoader;
        this.ragEvaluator = ragEvaluator;
        this.conversationExecutor = conversationExecutor;

        // ── Memory advisor (order=10): injects sliding-window history into every prompt ──
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(40)
                .build();

        MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory)
                .order(MEMORY_ADVISOR_ORDER)
                .build();

        // ── Custom RAG advisor chain (Self-RAG + CRAG):
        //    RetrievalAdvisor (100) → RelevanceCheckAdvisor (200) → GroundingAdvisor (300)
        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(memoryAdvisor, retrievalAdvisor, relevanceCheckAdvisor, groundingAdvisor)
                .build();

        // ── Lightweight client for async title generation (no advisors, no RAG) ──
        this.titleChatClient = ChatClient.builder(chatModel).build();
    }

    // ── Public API ──────────────────────────────────────────────────────────

    @Override
    @Transactional
    public ChatSessionResponse createSession(UUID userId, UUID courseId,
                                              UUID lessonId, String jlptLevel, String userTitle) {
        // Auto-resolve courseId when frontend does not supply it.
        // Business rule: 1 learner → at most 1 active class → 1 course at any time.
        if (courseId == null) {
            List<Object[]> rows = chatSessionRepository.findActiveCourse(userId);
            if (rows.isEmpty()) {
                throw new BusinessException("NOT_ENROLLED",
                        "Bạn chưa tham gia lớp học nào. Vui lòng đăng ký khóa học trước.");
            }
            courseId = UUID.fromString(rows.get(0)[0].toString());
        } else if (!chatSessionRepository.isEnrolledInCourse(userId, courseId)) {
            throw new BusinessException("NOT_ENROLLED",
                    "Bạn chưa tham gia lớp học nào thuộc khóa học này.");
        }

        // Resolve human-readable names once at creation and cache in JSONB context.
        // This avoids two cross-schema queries on every subsequent chat() call.
        String courseName = Optional.ofNullable(chatSessionRepository.findCourseNameById(courseId))
                .orElse("Khóa học");
        String lessonTitle = (lessonId != null)
                ? Optional.ofNullable(chatSessionRepository.findLessonTitleById(lessonId))
                          .orElse("Bài học")
                : null;

        Map<String, Object> ctx = new HashMap<>();
        ctx.put("courseId",   courseId.toString());
        ctx.put("courseName", courseName);
        if (lessonId    != null) ctx.put("lessonId",    lessonId.toString());
        if (lessonTitle != null) ctx.put("lessonTitle", lessonTitle);
        if (jlptLevel   != null) ctx.put("jlptLevel",  jlptLevel);
        ctx.put("sessionType", "CURRICULUM_QA");

        // Title priority: user-provided → auto-generated from context
        String defaultTitle = (lessonTitle != null)
                ? courseName + " — " + lessonTitle
                : courseName;
        String sessionTitle = (userTitle != null && !userTitle.isBlank())
                ? userTitle.trim() : defaultTitle;

        ChatSession session = ChatSession.builder()
                .userId(userId)
                .sessionType("CURRICULUM_QA")
                .context(toJson(ctx))
                .status("ACTIVE")
                .title(sessionTitle)
                .build();
        session = chatSessionRepository.save(session);

        log.info("Tạo phiên chat: sessionId={}, userId={}, courseId={}, lessonId={}, title={}",
                session.getId(), userId, courseId, lessonId, sessionTitle);

        return ChatSessionResponse.builder()
                .sessionId(session.getId())
                .title(sessionTitle)
                .status("ACTIVE")
                .courseId(courseId)
                .lessonId(lessonId)
                .jlptLevel(jlptLevel)
                .messageCount(0)
                .createdAt(session.getCreatedAt())
                .build();
    }

    /**
     * Send a message to the chatbot.
     *
     * <p>Not annotated {@code @Transactional}: holding a DB connection open for the
     * duration of multiple LLM API calls (3–10 s each) would exhaust the HikariCP pool
     * under concurrent load. The only write operation — {@code saveExchange} — runs in
     * its own transaction inside {@link ChatHistoryManager}.
     */
    @Override
    public ChatMessageResponse chat(UUID sessionId, UUID userId, String question) {
        // 1. Rate limit check — throws BusinessException if over quota
        chatRateLimiter.checkRateLimit(userId);

        // 2. Load + verify session (single SELECT, no transaction needed)
        SessionContext session = loadAndVerifySession(sessionId, userId);

        // 3. Build system prompt from cached JSONB fields (no DB queries)
        String systemPrompt = buildBaseSystemPrompt(session);

        // 4. Build tool context — courseId/lessonId passed to tools per-call via ToolContext
        Map<String, Object> toolCtx = new HashMap<>();
        toolCtx.put(CurriculumQueryTools.CTX_COURSE_ID, session.courseId());
        if (session.lessonId() != null) {
            toolCtx.put(CurriculumQueryTools.CTX_LESSON_ID, session.lessonId());
        }

        ChatClientResponse chatClientResponse;
        try {
            // Advisor chain (lowest order runs first in before(), last in after()):
            //   Memory(10)     → injects sliding-window history
            //   Retrieval(100) → query analysis + bilingual vector search + quality gate
            //   Relevance(200) → Self-RAG [IsRelevant]: strips context if irrelevant
            //   Grounding(300) → CRAG [IsSupported]: appends disclaimer if hallucinated
            final UUID lessonId = session.lessonId();
            chatClientResponse = chatClient.prompt()
                    .system(systemPrompt)
                    .user(question)
                    .advisors(a -> {
                        a.param(ChatMemory.CONVERSATION_ID, sessionId.toString());
                        a.param(RetrievalAdvisor.PARAM_COURSE_ID, session.courseId());
                        if (lessonId != null) {
                            a.param(RetrievalAdvisor.PARAM_LESSON_ID, lessonId);
                        }
                    })
                    .options(OpenAiChatOptions.builder()
                            .model(chatProperties.getModel())
                            .temperature(chatProperties.getTemperature())
                            .maxTokens(chatProperties.getMaxResponseTokens())
                            .build())
                    .tools(curriculumQueryTools)
                    .toolContext(toolCtx)
                    .call()
                    .chatClientResponse();

        } catch (Exception e) {
            log.error("ChatClient lỗi cho session {}: {}", sessionId, e.getMessage());
            throw new BusinessException("CHAT_ERROR",
                    "Hệ thống AI đang gặp sự cố, vui lòng thử lại sau.");
        }

        String aiResponse = Optional.ofNullable(chatClientResponse.chatResponse())
                .map(r -> r.getResult())
                .map(g -> g.getOutput())
                .map(m -> m.getText())
                .orElse(null);

        if (aiResponse == null || aiResponse.isBlank()) {
            log.error("AI trả về phản hồi null/rỗng cho session {}", sessionId);
            throw new BusinessException("CHAT_ERROR",
                    "Hệ thống AI không trả về phản hồi hợp lệ, vui lòng thử lại sau.");
        }

        // Extract retrieved documents stored by RetrievalAdvisor for source references
        @SuppressWarnings("unchecked")
        List<Document> retrievedDocs = (List<Document>) chatClientResponse.context()
                .getOrDefault(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT, List.of());

        // Persist exchange — saveExchange runs in its own @Transactional
        UUID aiMessageId = historyManager.saveExchange(sessionId, question, aiResponse);

        // ── Async RAGAS evaluation (fire-and-forget, zero latency impact) ──────────
        // Captures retrieval metadata from advisor context for quality scoring.
        String retrievedContextText = (String) chatClientResponse.context()
                .getOrDefault(RetrievalAdvisor.RAG_DOCUMENTS_TEXT_KEY, "");
        double maxSimilarity = retrievedDocs.stream()
                .mapToDouble(d -> {
                    Object sim = d.getMetadata().get("similarity");
                    return sim instanceof Number n ? n.doubleValue() : 0.0;
                })
                .max().orElse(0.0);
        String ragIntent = (String) chatClientResponse.context()
                .getOrDefault(RetrievalAdvisor.RAG_INTENT_KEY, null);
        String ragStrategy = (String) chatClientResponse.context()
                .getOrDefault(RetrievalAdvisor.RAG_STRATEGY_KEY, null);
        ragEvaluator.evaluateAsync(
                sessionId, null, aiMessageId,
                question, retrievedContextText, aiResponse,
                ragIntent, ragStrategy,
                retrievedDocs.size(), maxSimilarity);

        // After the very first message, generate an AI title async (fire-and-forget).
        // messageCount == 0 means this is the first exchange (counter not yet incremented).
        if (session.messageCount() == 0) {
            final String firstQuestion = question;
            final UUID sid = sessionId;
            conversationExecutor.execute(() -> generateAndSaveTitleAsync(sid, firstQuestion));
        }

        log.debug("Chat: session={} responseTokens≈{} ragDocs={}",
                sessionId, ChatHistoryManager.estimateTokens(aiResponse), retrievedDocs.size());

        return ChatMessageResponse.builder()
                .sessionId(sessionId)
                .messageId(aiMessageId)
                .content(aiResponse)
                .sources(buildSourcesFromDocuments(retrievedDocs))
                .noContextFound(isNoContextFound(retrievedDocs, aiResponse))
                .timestamp(LocalDateTime.now())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatHistoryItemResponse> getHistory(UUID sessionId, UUID userId,
                                                     int page, int size) {
        verifyOwnership(sessionId, userId);

        Page<com.fu.conversation.entity.ChatMessage> pageResult =
                chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(
                        sessionId, PageRequest.of(page, size));

        return pageResult.getContent().stream()
                .map(m -> ChatHistoryItemResponse.builder()
                        .messageId(m.getId())
                        .role(m.getRole())
                        .content(m.getContent())
                        .createdAt(m.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void closeSession(UUID sessionId, UUID userId) {
        verifyOwnership(sessionId, userId);
        chatSessionRepository.updateStatus(sessionId, "COMPLETED");
        log.info("Đóng phiên chat: sessionId={}, userId={}", sessionId, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ChatSessionResponse> listSessions(UUID userId, int page, int size) {
        Page<ChatSession> sessions = chatSessionRepository.findByUserIdAndSessionType(
                userId, "CURRICULUM_QA", PageRequest.of(page, size));
        return sessions.map(this::toSessionResponse);
    }

    @Override
    @Transactional
    public ChatSessionResponse reopenSession(UUID sessionId, UUID userId) {
        ChatSession session = chatSessionRepository.findByIdNotDeleted(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Phiên chat không tồn tại."));

        if (!session.getUserId().equals(userId)) {
            throw new BusinessException("FORBIDDEN",
                    "Bạn không có quyền truy cập phiên chat này.");
        }

        String status = session.getStatus();
        if ("ACTIVE".equals(status)) {
            return toSessionResponse(session);
        }
        if (!"COMPLETED".equals(status) && !"ABANDONED".equals(status)) {
            throw new BusinessException("INVALID_STATUS",
                    "Không thể mở lại phiên chat với trạng thái: " + status);
        }

        chatSessionRepository.updateStatus(sessionId, "ACTIVE");
        session.setStatus("ACTIVE");

        log.info("Mở lại phiên chat: sessionId={}, userId={}, oldStatus={}",
                sessionId, userId, status);
        return toSessionResponse(session);
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private ChatSessionResponse toSessionResponse(ChatSession entity) {
        Map<String, Object> ctx = fromJson(entity.getContext());
        UUID courseId = ctx.containsKey("courseId")
                ? UUID.fromString((String) ctx.get("courseId")) : null;
        UUID lessonId = ctx.containsKey("lessonId")
                ? UUID.fromString((String) ctx.get("lessonId")) : null;
        String jlptLevel = (String) ctx.getOrDefault("jlptLevel", null);

        // updatedAt is stamped by incrementMessageCount after each exchange
        LocalDateTime lastMessageAt = entity.getMessageCount() > 0 ? entity.getUpdatedAt() : null;

        return ChatSessionResponse.builder()
                .sessionId(entity.getId())
                .title(entity.getTitle())
                .status(entity.getStatus())
                .courseId(courseId)
                .lessonId(lessonId)
                .jlptLevel(jlptLevel)
                .messageCount(entity.getMessageCount())
                .createdAt(entity.getCreatedAt())
                .lastMessageAt(lastMessageAt)
                .build();
    }

    private SessionContext loadAndVerifySession(UUID sessionId, UUID userId) {
        ChatSession entity = chatSessionRepository.findByIdAndStatus(sessionId, "ACTIVE")
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Phiên chat không tồn tại hoặc đã kết thúc."));

        if (!entity.getUserId().equals(userId)) {
            throw new BusinessException("FORBIDDEN",
                    "Bạn không có quyền truy cập phiên chat này.");
        }

        Map<String, Object> ctx = fromJson(entity.getContext());
        UUID courseId = ctx.containsKey("courseId")
                ? UUID.fromString((String) ctx.get("courseId")) : null;
        UUID lessonId = ctx.containsKey("lessonId")
                ? UUID.fromString((String) ctx.get("lessonId")) : null;
        String jlptLevel  = (String) ctx.getOrDefault("jlptLevel", "N5");
        String courseName = (String) ctx.getOrDefault("courseName", "Khóa học");
        String lessonTitle = lessonId != null ? (String) ctx.getOrDefault("lessonTitle", null) : null;

        if (courseId == null) {
            throw new BusinessException("INVALID_SESSION",
                    "Phiên chat thiếu thông tin khóa học.");
        }
        return new SessionContext(sessionId, entity.getUserId(),
                courseId, lessonId, jlptLevel, courseName, lessonTitle,
                entity.getMessageCount());
    }

    private void verifyOwnership(UUID sessionId, UUID userId) {
        if (!chatSessionRepository.existsByIdAndUserId(sessionId, userId)) {
            throw new ResourceNotFoundException(
                    "Phiên chat không tồn tại hoặc không thuộc về bạn.");
        }
    }

    private String buildBaseSystemPrompt(SessionContext session) {
        String lessonCtx = session.lessonTitle() != null
                ? "- Bài học: " + session.lessonTitle()
                : "";
        return promptLoader.render("prompts/curriculum-chat-system.st", Map.of(
                "jlptLevel",    session.jlptLevel() != null ? session.jlptLevel() : "N5",
                "courseName",   session.courseName(),
                "lessonContext", lessonCtx));
    }

    /**
     * Build source references from {@link Document} objects stored by
     * {@link RetrievalAdvisor} under {@link RetrievalAugmentationAdvisor#DOCUMENT_CONTEXT}.
     *
     * <p>Metadata keys populated by {@link RetrievalAdvisor#toDocuments}:
     * {@code contentType}, {@code similarity}, plus all keys from
     * {@link com.fu.curriculumimport.service.embedding.RagContext.SimilarItem#getMetadata()}.
     */
    private List<ChatSourceReference> buildSourcesFromDocuments(List<Document> docs) {
        if (docs == null || docs.isEmpty()) return List.of();
        return docs.stream()
                .map(doc -> {
                    Map<String, Object> meta = doc.getMetadata() != null
                            ? doc.getMetadata() : Map.of();
                    String type = (String) meta.getOrDefault("contentType", "");
                    String text = doc.getText() != null ? doc.getText() : "";

                    String japaneseText;
                    String meaningVi;

                    switch (type) {
                        case "VOCABULARY" -> {
                            int dashIdx    = text.indexOf(" - Nghĩa: ");
                            int bracketIdx = text.indexOf(" [");
                            int cutAt = dashIdx > 0 ? dashIdx : (bracketIdx > 0 ? bracketIdx : text.length());
                            japaneseText = text.substring(0, Math.min(cutAt, text.length())).trim();
                            if (dashIdx > 0) {
                                int meaningStart = dashIdx + 10; // " - Nghĩa: " is 10 chars
                                String rest = meaningStart < text.length()
                                        ? text.substring(meaningStart) : "";
                                String extracted = rest.split("\\.", 2)[0].trim();
                                meaningVi = extracted.isEmpty() ? null : extracted;
                            } else {
                                meaningVi = null;
                            }
                        }
                        case "GRAMMAR" -> {
                            int meaningIdx = text.indexOf(". Nghĩa: ");
                            int dashIdx    = text.indexOf(" — ");
                            int cutAt = dashIdx > 0 ? dashIdx
                                    : (meaningIdx > 0 ? meaningIdx : Math.min(80, text.length()));
                            japaneseText = text.substring(0, cutAt).trim();
                            meaningVi    = meaningIdx > 0
                                    ? text.substring(meaningIdx + 9).split("\\.", 2)[0].trim()
                                    : null;
                        }
                        default -> {
                            // GRAMMAR_EXPLANATION, DIALOG, EXAMPLE — truncated preview
                            japaneseText = text.length() > 100 ? text.substring(0, 100) + "..." : text;
                            meaningVi    = null;
                        }
                    }

                    Integer lessonNumber = null;
                    Object lnObj = meta.get("lesson_number");
                    if (lnObj instanceof Number n) {
                        lessonNumber = n.intValue();
                    } else if (lnObj instanceof String s) {
                        try { lessonNumber = Integer.parseInt(s); }
                        catch (NumberFormatException ignored) {}
                    }

                    double similarity = 0.0;
                    Object simObj = meta.get("similarity");
                    if (simObj instanceof Number n) similarity = n.doubleValue();

                    String lessonTitle = meta.containsKey("lesson_title")
                            ? (String) meta.get("lesson_title") : null;

                    return ChatSourceReference.builder()
                            .contentType(type)
                            .japaneseText(japaneseText)
                            .meaningVi(meaningVi)
                            .lessonTitle(lessonTitle)
                            .lessonNumber(lessonNumber)
                            .similarity(similarity)
                            .build();
                })
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return objectMapper.readValue(json, Map.class); }
        catch (Exception e) {
            log.warn("fromJson: failed to parse session context JSON: {}", e.getMessage());
            return Map.of();
        }
    }

    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); }
        catch (Exception e) { return "{}"; }
    }

    /**
     * Determine whether the response genuinely had no curriculum context.
     *
     * {@code noContextFound = true} only when BOTH conditions hold:
     *  1. RetrievalAdvisor found zero documents (initial RAG miss)
     *  2. AI response contains no CJK characters — meaning the AI did NOT
     *     successfully call a tool (queryStructuredData / searchCurriculum)
     *     and receive Japanese content back.
     *
     * Without condition 2, a successful tool call would still set
     * noContextFound=true because tool results bypass DOCUMENT_CONTEXT.
     */
    private boolean isNoContextFound(List<Document> retrievedDocs, String aiResponse) {
        if (!retrievedDocs.isEmpty()) return false;
        // CJK block presence = AI had structured Japanese content from a tool call
        boolean hasCjkContent = aiResponse != null && aiResponse.codePoints()
                .anyMatch(cp -> (cp >= 0x3000 && cp <= 0x9FFF)
                             || (cp >= 0xF900 && cp <= 0xFAFF));
        return !hasCjkContent;
    }

    private record SessionContext(UUID sessionId, UUID userId,
                                  UUID courseId, UUID lessonId, String jlptLevel,
                                  String courseName, String lessonTitle,
                                  int messageCount) {}

    /**
     * Fire-and-forget: generate a short AI title from the first user question and
     * persist it. Runs on the bounded conversationExecutor — never blocks the chat response.
     * Any failure is swallowed and logged; the default context title remains intact.
     */
    private void generateAndSaveTitleAsync(UUID sessionId, String firstQuestion) {
        try {
            String prompt = """
                    Tạo tiêu đề ngắn gọn (tối đa 7 từ, tiếng Việt) cho cuộc trò chuyện \
                    học tiếng Nhật dựa trên câu hỏi sau đây. \
                    Chỉ trả về tiêu đề, không giải thích, không dấu ngoặc kép:
                    """ + firstQuestion;

            String generated = titleChatClient.prompt()
                    .user(prompt)
                    .options(OpenAiChatOptions.builder()
                            .model(chatProperties.getModel())
                            .temperature(0.3)
                            .maxTokens(30)
                            .build())
                    .call()
                    .content();

            if (generated != null && !generated.isBlank()) {
                String title = generated.trim()
                        .replaceAll("^\"|\"$", "")   // strip surrounding quotes if any
                        .replaceAll("^'|'$", "");
                chatSessionRepository.updateTitle(sessionId, title);
                log.debug("AI title saved for session {}: \"{}\"", sessionId, title);
            }
        } catch (Exception e) {
            log.warn("AI title generation failed for session {} — keeping default title: {}",
                    sessionId, e.getMessage());
        }
    }
}

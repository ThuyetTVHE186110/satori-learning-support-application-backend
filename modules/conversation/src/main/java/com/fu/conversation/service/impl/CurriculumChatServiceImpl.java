package com.fu.conversation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.common.ai.PromptTemplateLoader;
import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.conversation.ai.memory.CurriculumChatMemoryRepository;
import com.fu.conversation.ai.retriever.EmbeddingDocumentRetriever;
import com.fu.conversation.ai.tools.CurriculumQueryTools;
import com.fu.conversation.dto.response.ChatHistoryItemResponse;
import com.fu.conversation.dto.response.ChatMessageResponse;
import com.fu.conversation.dto.response.ChatSessionResponse;
import com.fu.conversation.dto.response.ChatSourceReference;
import com.fu.conversation.entity.ChatSession;
import com.fu.conversation.repository.ChatMessageRepository;
import com.fu.conversation.repository.ChatSessionRepository;
import com.fu.conversation.service.ChatHistoryManager;
import com.fu.conversation.service.CurriculumChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
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

    private static final String CHAT_MODEL  = "gpt-4o-mini";
    private static final double TEMPERATURE = 0.3;

    // Advisor execution order: memory first (lower order = higher priority),
    // then RAG retrieval so CompressionQueryTransformer can see injected history.
    private static final int MEMORY_ADVISOR_ORDER    = 10;
    private static final int RETRIEVAL_ADVISOR_ORDER = 20;

    private final ChatClient chatClient;
    private final CurriculumQueryTools curriculumQueryTools;
    private final PromptTemplateLoader promptLoader;
    private final ChatHistoryManager historyManager;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ObjectMapper objectMapper;

    public CurriculumChatServiceImpl(OpenAiChatModel chatModel,
                                      CurriculumChatMemoryRepository chatMemoryRepository,
                                      EmbeddingDocumentRetriever documentRetriever,
                                      ChatHistoryManager historyManager,
                                      ChatSessionRepository chatSessionRepository,
                                      ChatMessageRepository chatMessageRepository,
                                      ObjectMapper objectMapper,
                                      PromptTemplateLoader promptLoader,
                                      CurriculumQueryTools curriculumQueryTools) {
        this.curriculumQueryTools = curriculumQueryTools;
        this.historyManager = historyManager;
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.objectMapper = objectMapper;
        this.promptLoader = promptLoader;

        // ── Memory advisor — injects sliding-window history into every prompt ──
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(40)
                .build();

        MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory)
                .order(MEMORY_ADVISOR_ORDER)
                .build();

        // ── Retrieval advisor — pre-retrieves RAG context and injects it into
        //    the system message; also stores retrieved Documents in
        //    ChatClientResponse.context() under DOCUMENT_CONTEXT for source refs. ──
        RetrievalAugmentationAdvisor retrievalAdvisor = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(documentRetriever)
                .queryAugmenter(ContextualQueryAugmenter.builder()
                        .allowEmptyContext(true) // LLM can still answer when no docs found
                        .build())
                .order(RETRIEVAL_ADVISOR_ORDER)
                .build();

        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(memoryAdvisor, retrievalAdvisor)
                .build();
    }

    // ── Public API ──────────────────────────────────────────────────────────

    @Override
    @Transactional
    public ChatSessionResponse createSession(UUID userId, UUID courseId,
                                              UUID lessonId, String jlptLevel) {
        if (!chatSessionRepository.isEnrolledInCourse(userId, courseId)) {
            throw new BusinessException("NOT_ENROLLED",
                    "Bạn chưa tham gia lớp học nào thuộc khóa học này.");
        }

        Map<String, Object> ctx = new HashMap<>();
        ctx.put("courseId", courseId.toString());
        if (lessonId  != null) ctx.put("lessonId",  lessonId.toString());
        if (jlptLevel != null) ctx.put("jlptLevel", jlptLevel);
        ctx.put("sessionType", "CURRICULUM_QA");

        ChatSession session = ChatSession.builder()
                .userId(userId)
                .sessionType("CURRICULUM_QA")
                .context(toJson(ctx))
                .status("ACTIVE")
                .build();
        session = chatSessionRepository.save(session);

        log.info("Tạo phiên chat: sessionId={}, userId={}, courseId={}, lessonId={}",
                session.getId(), userId, courseId, lessonId);

        return ChatSessionResponse.builder()
                .sessionId(session.getId())
                .status("ACTIVE")
                .courseId(courseId)
                .lessonId(lessonId)
                .jlptLevel(jlptLevel)
                .messageCount(0)
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Override
    @Transactional
    public ChatMessageResponse chat(UUID sessionId, UUID userId, String question) {
        // 1. Load + verify session
        SessionContext session = loadAndVerifySession(sessionId, userId);

        // 2. Build system prompt
        String systemPrompt = buildBaseSystemPrompt(session);

        // 3. Build tool context — courseId/lessonId passed to tools per-call via ToolContext
        Map<String, Object> toolCtx = new HashMap<>();
        toolCtx.put(CurriculumQueryTools.CTX_COURSE_ID, session.courseId());
        if (session.lessonId() != null) {
            toolCtx.put(CurriculumQueryTools.CTX_LESSON_ID, session.lessonId());
        }

        ChatClientResponse chatClientResponse;
        try {
            // 4. Call AI with advisor chain:
            //    MessageChatMemoryAdvisor (order=10) → injects history from DB
            //    RetrievalAugmentationAdvisor (order=20) → pre-retrieves docs, augments system msg
            //       EmbeddingDocumentRetriever reads courseId/lessonId from Query.context()
            //       which the advisor auto-populates from ChatClientRequest.context() (advisor params)
            //    LLM invokes CurriculumQueryTools as needed (searchCurriculum / countContent)
            final UUID lessonId = session.lessonId();
            chatClientResponse = chatClient.prompt()
                    .system(systemPrompt)
                    .user(question)
                    .advisors(a -> {
                        a.param(ChatMemory.CONVERSATION_ID, sessionId.toString());
                        a.param("courseId", session.courseId());
                        if (lessonId != null) {
                            a.param("lessonId", lessonId);
                        }
                    })
                    .options(OpenAiChatOptions.builder()
                            .model(CHAT_MODEL)
                            .temperature(TEMPERATURE)
                            .maxTokens(1024)
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

        String aiResponse = chatClientResponse.chatResponse()
                .getResult().getOutput().getText();

        // 6. Extract retrieved documents from advisor context for source references
        @SuppressWarnings("unchecked")
        List<Document> retrievedDocs = (List<Document>) chatClientResponse.context()
                .getOrDefault(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT, List.of());

        // 7. Persist exchange — MessageChatMemoryAdvisor.after() calls saveAll() which is
        //    a no-op; we persist explicitly here for the atomic CTE insert + counter update.
        UUID aiMessageId = historyManager.saveExchange(sessionId, question, aiResponse);

        log.debug("Chat: session={} responseTokens≈{} ragDocs={}",
                sessionId, ChatHistoryManager.estimateTokens(aiResponse), retrievedDocs.size());

        return ChatMessageResponse.builder()
                .sessionId(sessionId)
                .messageId(aiMessageId)
                .content(aiResponse)
                .sources(buildSourcesFromDocuments(retrievedDocs))
                .noContextFound(retrievedDocs.isEmpty())
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

        return ChatSessionResponse.builder()
                .sessionId(entity.getId())
                .status(entity.getStatus())
                .courseId(courseId)
                .lessonId(lessonId)
                .jlptLevel(jlptLevel)
                .messageCount(entity.getMessageCount())
                .createdAt(entity.getCreatedAt())
                .lastMessageAt(null)
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
        String jlptLevel = (String) ctx.getOrDefault("jlptLevel", "N5");

        if (courseId == null) {
            throw new BusinessException("INVALID_SESSION",
                    "Phiên chat thiếu thông tin khóa học.");
        }
        return new SessionContext(sessionId, entity.getUserId(), courseId, lessonId, jlptLevel);
    }

    private void verifyOwnership(UUID sessionId, UUID userId) {
        if (!chatSessionRepository.existsByIdAndUserId(sessionId, userId)) {
            throw new ResourceNotFoundException(
                    "Phiên chat không tồn tại hoặc không thuộc về bạn.");
        }
    }

    private String buildBaseSystemPrompt(SessionContext session) {
        String courseName = Optional.ofNullable(
                chatSessionRepository.findCourseNameById(session.courseId()))
                .orElse("Khóa học");
        String lessonCtx = session.lessonId() != null
                ? "- Bài học: " + Optional.ofNullable(
                        chatSessionRepository.findLessonTitleById(session.lessonId()))
                        .orElse("Bài học")
                : "";

        return promptLoader.render("prompts/curriculum-chat-system.st", Map.of(
                "jlptLevel",    session.jlptLevel() != null ? session.jlptLevel() : "N5",
                "courseName",   courseName,
                "lessonContext", lessonCtx));
    }

    /**
     * Build source references from {@link Document} objects returned by
     * {@link RetrievalAugmentationAdvisor} via
     * {@code chatClientResponse.context().get(DOCUMENT_CONTEXT)}.
     *
     * <p>Metadata keys in each Document are populated by
     * {@link com.fu.conversation.ai.retriever.EmbeddingDocumentRetriever}:
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
                            meaningVi    = dashIdx > 0
                                    ? text.substring(dashIdx + 10).split("\\.", 2)[0].trim()
                                    : null;
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
        catch (Exception e) { return Map.of(); }
    }

    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); }
        catch (Exception e) { return "{}"; }
    }

    private record SessionContext(UUID sessionId, UUID userId,
                                  UUID courseId, UUID lessonId, String jlptLevel) {}
}

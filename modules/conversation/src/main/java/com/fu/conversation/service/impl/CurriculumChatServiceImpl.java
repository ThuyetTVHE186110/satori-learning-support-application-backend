package com.fu.conversation.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.conversation.dto.response.ChatHistoryItemResponse;
import com.fu.conversation.dto.response.ChatMessageResponse;
import com.fu.conversation.dto.response.ChatSessionResponse;
import com.fu.conversation.dto.response.ChatSourceReference;
import com.fu.conversation.entity.ChatSession;
import com.fu.conversation.repository.ChatMessageRepository;
import com.fu.conversation.repository.ChatSessionRepository;
import com.fu.conversation.service.ChatHistoryManager;
import com.fu.conversation.service.CurriculumChatService;
import com.fu.curriculumimport.service.embedding.ChatbotFilter;
import com.fu.curriculumimport.service.embedding.EmbeddingService;
import com.fu.curriculumimport.service.embedding.RagContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
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

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            Bạn là trợ lý học tiếng Nhật của hệ thống Satori, chuyên giải thích nội dung giáo trình cho học viên Việt Nam.

            **Thông tin học viên:**
            - Cấp độ: {jlptLevel}
            - Khóa học: {courseName}
            {lessonContext}

            **Nguyên tắc trả lời:**
            1. Trả lời bằng tiếng Việt, rõ ràng và súc tích
            2. Khi nhắc đến từ/cụm từ tiếng Nhật, luôn kèm furigana: 食べる[たべる]
            3. Đưa ví dụ cụ thể từ giáo trình nếu có
            4. Nếu KHÔNG có thông tin trong [Nội dung tham khảo], trả lời: "Nội dung này chưa có trong giáo trình của bạn."
            5. KHÔNG bịa đặt thông tin không có trong [Nội dung tham khảo]
            6. Giải thích ngắn gọn, tránh lý thuyết dài dòng
            """;

    private final ChatClient chatClient;
    private final EmbeddingService embeddingService;
    private final ChatHistoryManager historyManager;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ObjectMapper objectMapper;

    public CurriculumChatServiceImpl(OpenAiChatModel chatModel,
                                      EmbeddingService embeddingService,
                                      ChatHistoryManager historyManager,
                                      ChatSessionRepository chatSessionRepository,
                                      ChatMessageRepository chatMessageRepository,
                                      ObjectMapper objectMapper) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.embeddingService = embeddingService;
        this.historyManager = historyManager;
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.objectMapper = objectMapper;
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

        // 2. RAG retrieval
        ChatbotFilter filter = ChatbotFilter.forChatbot(session.courseId(), session.lessonId());
        RagContext rag = embeddingService.retrieveContext(question, filter);

        boolean noContext = !rag.hasContext();
        if (noContext) log.debug("Không có RAG context cho: '{}'", question);

        // 3. Build prompt + history
        String systemPrompt = buildSystemPrompt(session, rag);
        List<Message> history = historyManager.loadHistory(sessionId);

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.addAll(history);
        messages.add(new UserMessage(question));

        // 4. Call AI
        String aiResponse;
        try {
            aiResponse = chatClient.prompt()
                    .messages(messages)
                    .options(OpenAiChatOptions.builder()
                            .model(CHAT_MODEL)
                            .temperature(TEMPERATURE)
                            .maxTokens(1024)
                            .build())
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("ChatClient lỗi cho session {}: {}", sessionId, e.getMessage());
            throw new BusinessException("CHAT_ERROR",
                    "Hệ thống AI đang gặp sự cố, vui lòng thử lại sau.");
        }

        // 5. Persist exchange
        UUID aiMessageId = historyManager.saveExchange(sessionId, question, aiResponse);

        log.debug("Chat: session={}, responseTokens≈{}, sources={}, noContext={}",
                sessionId, ChatHistoryManager.estimateTokens(aiResponse),
                rag.getSimilarItems().size(), noContext);

        return ChatMessageResponse.builder()
                .sessionId(sessionId)
                .messageId(aiMessageId)
                .content(aiResponse)
                .sources(buildSourceReferences(rag))
                .noContextFound(noContext)
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

    // ── Private helpers ─────────────────────────────────────────────────────

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

    private String buildSystemPrompt(SessionContext session, RagContext rag) {
        String courseName = Optional.ofNullable(
                chatSessionRepository.findCourseNameById(session.courseId()))
                .orElse("Khóa học");
        String lessonCtx = session.lessonId() != null
                ? "- Bài học: " + Optional.ofNullable(
                        chatSessionRepository.findLessonTitleById(session.lessonId()))
                        .orElse("Bài học")
                : "";

        String prompt = SYSTEM_PROMPT_TEMPLATE
                .replace("{jlptLevel}",    session.jlptLevel() != null ? session.jlptLevel() : "N5")
                .replace("{courseName}",   courseName)
                .replace("{lessonContext}", lessonCtx);

        if (rag.hasContext()) {
            StringBuilder sb = new StringBuilder(prompt);
            sb.append("\n**[Nội dung tham khảo từ giáo trình]:**\n\n");
            for (RagContext.SimilarItem item : rag.getSimilarItems()) {
                String type = item.getContentType() != null ? item.getContentType() : "UNKNOWN";
                String text = item.getTextContent() != null ? item.getTextContent() : "";
                Map<String, String> meta = item.getMetadata() != null ? item.getMetadata() : Map.of();
                String lessonInfo = meta.containsKey("lesson_title")
                        ? " (Bài: " + meta.get("lesson_title") + ")" : "";

                switch (type) {
                    case "VOCABULARY" -> sb.append("📖 Từ vựng: ").append(text).append(lessonInfo).append("\n");
                    case "GRAMMAR" -> sb.append("📝 Ngữ pháp: ").append(text).append(lessonInfo).append("\n");
                    case "GRAMMAR_EXPLANATION" -> sb.append("📘 Giải thích ngữ pháp").append(lessonInfo)
                            .append(":\n").append(text).append("\n");
                    case "DIALOG" -> sb.append("💬 Hội thoại mẫu").append(lessonInfo)
                            .append(":\n").append(text).append("\n");
                    case "EXAMPLE" -> sb.append("✏️ Ví dụ").append(lessonInfo)
                            .append(":\n").append(text).append("\n");
                    default -> sb.append("- [").append(type).append("] ").append(text).append(lessonInfo).append("\n");
                }
                sb.append("\n");
            }
            sb.append("**Hãy sử dụng CHÍNH XÁC nội dung trên để trả lời. Không bịa đặt thông tin ngoài nội dung tham khảo.**\n");
            return sb.toString();
        }
        return prompt + "\n**Lưu ý:** Không tìm thấy nội dung liên quan trong giáo trình. "
                + "Hãy trả lời: \"Nội dung này chưa có trong giáo trình của bạn.\"";
    }

    private List<ChatSourceReference> buildSourceReferences(RagContext rag) {
        if (!rag.hasContext()) return List.of();
        return rag.getSimilarItems().stream()
                .map(item -> {
                    Map<String, String> meta = item.getMetadata() != null
                            ? item.getMetadata() : Map.of();
                    String text = item.getTextContent() != null ? item.getTextContent() : "";
                    String type = item.getContentType() != null ? item.getContentType() : "";

                    String japaneseText;
                    String meaningVi;

                    switch (type) {
                        case "VOCABULARY" -> {
                            // Vocab format: "日本語 (reading) [POS] - Nghĩa: meaning ..."
                            // Extract Japanese text (before first " - " or " [")
                            int dashIdx = text.indexOf(" - Nghĩa: ");
                            int bracketIdx = text.indexOf(" [");
                            int cutAt = dashIdx > 0 ? dashIdx : (bracketIdx > 0 ? bracketIdx : text.length());
                            japaneseText = text.substring(0, Math.min(cutAt, text.length())).trim();
                            meaningVi = dashIdx > 0 ? text.substring(dashIdx + 10).split("\\.", 2)[0].trim() : null;
                        }
                        case "GRAMMAR" -> {
                            // Grammar format: "Point: Pattern — Function. Nghĩa: meaning ..."
                            int meaningIdx = text.indexOf(". Nghĩa: ");
                            int dashIdx = text.indexOf(" — ");
                            int cutAt = dashIdx > 0 ? dashIdx : (meaningIdx > 0 ? meaningIdx : Math.min(80, text.length()));
                            japaneseText = text.substring(0, cutAt).trim();
                            meaningVi = meaningIdx > 0 ? text.substring(meaningIdx + 9).split("\\.", 2)[0].trim() : null;
                        }
                        default -> {
                            // GRAMMAR_EXPLANATION, DIALOG, EXAMPLE — use truncated text as preview
                            japaneseText = text.length() > 100 ? text.substring(0, 100) + "..." : text;
                            meaningVi = null;
                        }
                    }

                    Integer lessonNumber = null;
                    try {
                        if (meta.containsKey("lesson_number"))
                            lessonNumber = Integer.parseInt(meta.get("lesson_number"));
                    } catch (NumberFormatException ignored) {}

                    return ChatSourceReference.builder()
                            .contentType(type)
                            .japaneseText(japaneseText)
                            .meaningVi(meaningVi)
                            .lessonTitle(meta.getOrDefault("lesson_title", null))
                            .lessonNumber(lessonNumber)
                            .similarity(item.getSimilarity())
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

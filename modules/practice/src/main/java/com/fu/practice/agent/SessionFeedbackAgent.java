package com.fu.practice.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.common.ai.PromptTemplateLoader;
import com.fu.practice.entity.PracticeItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Agent tổng kết phiên luyện tập và sinh nhận xét AI.
 *
 * Trả về JSON string lưu vào cột ai_feedback của PracticeSession.
 * Cấu trúc: {"summary":"...","strengths":[...],"weaknesses":[...],"nextLessonSuggestion":"..."}
 */
@Service
@Slf4j
public class SessionFeedbackAgent {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final PromptTemplateLoader promptLoader;

    public SessionFeedbackAgent(OpenAiChatModel openAiChatModel, ObjectMapper objectMapper,
                                 PromptTemplateLoader promptLoader) {
        this.chatClient = ChatClient.builder(openAiChatModel).build();
        this.objectMapper = objectMapper;
        this.promptLoader = promptLoader;
    }

    /**
     * Sinh nhận xét tổng kết cho phiên luyện tập.
     *
     * @param sessionId ID phiên (dùng cho log)
     * @param score     điểm trung bình (0-100)
     * @param items     danh sách câu hỏi đã trả lời
     * @return JSON string chứa feedback
     */
    public String generateFeedback(UUID sessionId, double score, List<PracticeItem> items) {
        try {
            List<PracticeItem> wrongItems = items.stream()
                    .filter(i -> Boolean.FALSE.equals(i.getIsCorrect()))
                    .collect(Collectors.toList());

            String prompt = buildFeedbackPrompt(score, items.size(), wrongItems);

            String raw = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (raw == null || raw.isBlank()) {
                return buildSimpleFeedback(score);
            }

            String cleaned = raw.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").trim();
            }

            // Validate JSON hợp lệ
            objectMapper.readTree(cleaned);
            return cleaned;

        } catch (Exception e) {
            log.warn("SessionFeedbackAgent: Lỗi sinh feedback cho session {}: {}", sessionId, e.getMessage());
            return buildSimpleFeedback(score);
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private String buildFeedbackPrompt(double score, int totalItems, List<PracticeItem> wrongItems) {
        StringBuilder wrongSummary = new StringBuilder();
        if (!wrongItems.isEmpty()) {
            wrongSummary.append("Các câu sai:\n");
            wrongItems.forEach(item ->
                    wrongSummary.append(String.format("- Câu %d: %s (Đúng: %s, Trả lời: %s)\n",
                            item.getItemIndex() + 1,
                            item.getQuestion(),
                            item.getCorrectAnswer(),
                            item.getUserAnswer() != null ? item.getUserAnswer() : "(chưa trả lời)")));
        }

        return promptLoader.render("prompts/session-feedback-system.st", Map.of(
                "score", String.format("%.1f", score),
                "correctCount", String.valueOf(totalItems - wrongItems.size()),
                "totalItems", String.valueOf(totalItems),
                "wrongSummary", wrongSummary.toString()));
    }

    private String buildSimpleFeedback(double score) {
        String summary;
        if (score >= 80) {
            summary = String.format("Xuất sắc! Bạn đạt %.0f%%. Tiếp tục phát huy nhé!", score);
        } else if (score >= 60) {
            summary = String.format("Khá tốt! Bạn đạt %.0f%%. Hãy ôn lại các từ chưa thuộc.", score);
        } else {
            summary = String.format("Bạn đạt %.0f%%. Hãy xem lại bài học và luyện tập thêm.", score);
        }

        try {
            return objectMapper.writeValueAsString(Map.of(
                    "summary", summary,
                    "strengths", List.of(),
                    "weaknesses", List.of("Cần luyện tập thêm các từ chưa thuộc"),
                    "nextLessonSuggestion", "Ôn lại bài học và thử luyện tập lại."
            ));
        } catch (Exception ex) {
            return "{\"summary\": \"" + summary + "\"}";
        }
    }
}

package com.fu.practice.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.practice.entity.PracticeItem;
import com.fu.practice.entity.PracticeItemType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Service;

/**
 * Agent đánh giá câu trả lời của học viên.
 *
 * - MULTIPLE_CHOICE, MATCHING: so sánh chuỗi đơn giản, không gọi LLM
 * - FILL_BLANK, TRANSLATION, SENTENCE_ORDER: gọi LLM để chấp nhận
 *   câu trả lời đúng về nghĩa dù form nhẹ khác nhau
 */
@Service
@Slf4j
public class AnswerEvaluatorAgent {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public AnswerEvaluatorAgent(OpenAiChatModel openAiChatModel, ObjectMapper objectMapper) {
        this.chatClient = ChatClient.builder(openAiChatModel).build();
        this.objectMapper = objectMapper;
    }

    /**
     * Đánh giá câu trả lời.
     *
     * @param item       câu hỏi cần đánh giá
     * @param userAnswer câu trả lời của học viên
     * @return kết quả đánh giá
     */
    public EvaluationResult evaluate(PracticeItem item, String userAnswer) {
        if (item.getItemType() == PracticeItemType.MULTIPLE_CHOICE
                || item.getItemType() == PracticeItemType.MATCHING) {
            return evaluateByRule(item, userAnswer);
        }
        return evaluateByLlm(item, userAnswer);
    }

    // ── Rule-based evaluation ─────────────────────────────────────────────

    private EvaluationResult evaluateByRule(PracticeItem item, String userAnswer) {
        boolean correct = userAnswer.trim().equalsIgnoreCase(item.getCorrectAnswer().trim());
        String feedback = correct
                ? "Chính xác!"
                : "Đáp án đúng là: " + item.getCorrectAnswer();
        return new EvaluationResult(correct, correct ? 100 : 0, feedback, correct ? null : item.getCorrectAnswer());
    }

    // ── LLM evaluation ────────────────────────────────────────────────────

    private EvaluationResult evaluateByLlm(PracticeItem item, String userAnswer) {
        try {
            String prompt = buildEvaluationPrompt(item, userAnswer);
            String raw = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            return parseEvaluationResponse(raw, item, userAnswer);

        } catch (Exception e) {
            log.warn("AnswerEvaluatorAgent: Lỗi gọi LLM, dùng fallback exact match: {}", e.getMessage());
            return fallbackExactMatch(item, userAnswer);
        }
    }

    private String buildEvaluationPrompt(PracticeItem item, String userAnswer) {
        return String.format("""
                Bạn là giáo viên tiếng Nhật. Đánh giá câu trả lời của học viên.

                Câu hỏi: %s
                Đáp án mẫu: %s
                Câu trả lời của học viên: %s

                Quy tắc đánh giá:
                - Chấp nhận câu trả lời nếu đúng về nghĩa, dù form chữ hoa/thường hoặc dấu câu nhẹ khác
                - Với bài dịch: chấp nhận nếu nghĩa tương đương dù cách diễn đạt khác
                - Với điền vào chỗ trống: cần đúng từ/cấu trúc ngữ pháp
                - Với sắp xếp câu: cần đúng thứ tự

                Trả về JSON THUẦN (không markdown):
                {"isCorrect": true/false, "score": 0-100, "feedback": "nhận xét ngắn tiếng Việt", "correction": "câu/từ đúng nếu sai hoặc null"}
                """,
                item.getQuestion(),
                item.getCorrectAnswer(),
                userAnswer);
    }

    private EvaluationResult parseEvaluationResponse(String raw, PracticeItem item, String userAnswer) {
        try {
            String cleaned = raw.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").trim();
            }

            JsonNode node = objectMapper.readTree(cleaned);
            boolean isCorrect = node.path("isCorrect").asBoolean(false);
            int score = node.path("score").asInt(isCorrect ? 100 : 0);
            String feedback = node.path("feedback").asText("Đã ghi nhận câu trả lời.");
            String correction = node.path("correction").isNull() ? null : node.path("correction").asText(null);

            return new EvaluationResult(isCorrect, score, feedback, correction);

        } catch (Exception e) {
            log.warn("AnswerEvaluatorAgent: Không parse được response LLM, dùng fallback: {}", e.getMessage());
            return fallbackExactMatch(item, userAnswer);
        }
    }

    private EvaluationResult fallbackExactMatch(PracticeItem item, String userAnswer) {
        boolean correct = userAnswer.trim().equalsIgnoreCase(item.getCorrectAnswer().trim());
        String feedback = correct
                ? "Chính xác!"
                : "Câu trả lời chưa đúng. Đáp án: " + item.getCorrectAnswer();
        return new EvaluationResult(correct, correct ? 100 : 0, feedback, correct ? null : item.getCorrectAnswer());
    }

    // ── Result record ─────────────────────────────────────────────────────

    /**
     * Kết quả đánh giá một câu trả lời.
     *
     * @param isCorrect  có đúng không
     * @param score      điểm 0-100
     * @param feedback   nhận xét ngắn bằng tiếng Việt
     * @param correction câu/từ đúng (null nếu đúng)
     */
    public record EvaluationResult(
            boolean isCorrect,
            int score,
            String feedback,
            String correction
    ) {}
}

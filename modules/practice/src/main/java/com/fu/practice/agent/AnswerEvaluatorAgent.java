package com.fu.practice.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.common.ai.PromptTemplateLoader;
import com.fu.practice.entity.PracticeItem;
import com.fu.practice.entity.PracticeItemType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

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
    private final PromptTemplateLoader promptLoader;

    public AnswerEvaluatorAgent(OpenAiChatModel openAiChatModel, ObjectMapper objectMapper,
                                 PromptTemplateLoader promptLoader) {
        this.chatClient = ChatClient.builder(openAiChatModel).build();
        this.objectMapper = objectMapper;
        this.promptLoader = promptLoader;
    }

    /**
     * Đánh giá câu trả lời.
     *
     * @param item       câu hỏi cần đánh giá
     * @param userAnswer câu trả lời của học viên
     * @return kết quả đánh giá
     */
    public EvaluationResult evaluate(PracticeItem item, String userAnswer) {
        return switch (item.getItemType()) {
            case MULTIPLE_CHOICE, TRUE_FALSE -> evaluateByRule(item, userAnswer);
            case MATCHING                    -> evaluateMatching(item, userAnswer);
            default                          -> evaluateByLlm(item, userAnswer);
        };
    }

    // ── Rule-based evaluation ─────────────────────────────────────────────

    private EvaluationResult evaluateByRule(PracticeItem item, String userAnswer) {
        boolean correct = userAnswer.trim().equalsIgnoreCase(item.getCorrectAnswer().trim());
        String feedback = correct
                ? "Chính xác!"
                : "Đáp án đúng là: " + item.getCorrectAnswer();
        return new EvaluationResult(correct, correct ? 100 : 0, feedback, correct ? null : item.getCorrectAnswer());
    }

    /**
     * Đánh giá MATCHING: so sánh ánh xạ "leftId:rightId" do học viên gửi
     * với correctAnswer lưu trong DB.
     *
     * userAnswer format: "0:4,1:5,2:6,3:7"
     * correctAnswer format: "0:4,1:5,2:6,3:7"
     *
     * Tính partial score: N cặp đúng / tổng cặp × 100.
     * Chỉ isCorrect=true khi TẤT CẢ cặp đúng.
     */
    private EvaluationResult evaluateMatching(PracticeItem item, String userAnswer) {
        try {
            Map<String, String> userMap    = parsePairMap(userAnswer);
            Map<String, String> correctMap = parsePairMap(item.getCorrectAnswer());

            if (userMap.isEmpty()) {
                return new EvaluationResult(false, 0,
                        "Bạn chưa nối cặp nào.", item.getCorrectAnswer());
            }

            long correctPairs = userMap.entrySet().stream()
                    .filter(e -> e.getValue().equals(correctMap.get(e.getKey())))
                    .count();
            int total = correctMap.size();
            int score = total > 0 ? (int) (correctPairs * 100L / total) : 0;
            boolean allCorrect = correctPairs == total;

            String feedback = allCorrect
                    ? "Chính xác! Bạn đã nối đúng tất cả " + total + " cặp."
                    : String.format("Đúng %d/%d cặp.", correctPairs, total);

            return new EvaluationResult(allCorrect, score, feedback,
                    allCorrect ? null : item.getCorrectAnswer());

        } catch (Exception e) {
            log.warn("evaluateMatching: Không parse được userAnswer '{}': {}", userAnswer, e.getMessage());
            return new EvaluationResult(false, 0,
                    "Không thể đánh giá câu trả lời.", item.getCorrectAnswer());
        }
    }

    /** Parse "0:4,1:5,2:6" → Map{0→4, 1→5, 2→6}. */
    private Map<String, String> parsePairMap(String pairStr) {
        Map<String, String> map = new HashMap<>();
        if (pairStr == null || pairStr.isBlank()) return map;
        for (String pair : pairStr.split(",")) {
            String[] parts = pair.trim().split(":");
            if (parts.length == 2) {
                map.put(parts[0].trim(), parts[1].trim());
            }
        }
        return map;
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
        return promptLoader.render("prompts/answer-evaluator-system.st", Map.of(
                "itemType", item.getItemType().name(),
                "question", item.getQuestion() != null ? item.getQuestion() : "",
                "correctAnswer", item.getCorrectAnswer() != null ? item.getCorrectAnswer() : "",
                "userAnswer", userAnswer != null ? userAnswer : ""));
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

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
            // FILL_BLANK và TRANSLATION đều là chọn option → correctAnswer là ID integer
            case FILL_BLANK, TRANSLATION     -> evaluateFillBlank(item, userAnswer);
            case SENTENCE_ORDER              -> evaluateSentenceOrder(item, userAnswer);
        };
    }

    // ── Rule-based evaluation ─────────────────────────────────────────────

    private EvaluationResult evaluateByRule(PracticeItem item, String userAnswer) {
        if (userAnswer == null || item.getCorrectAnswer() == null) {
            return new EvaluationResult(false, 0, "Không thể đánh giá câu trả lời.", item.getCorrectAnswer());
        }
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

    // ── Rule-based: FILL_BLANK ────────────────────────────────────────────

    /**
     * Đánh giá FILL_BLANK bằng so sánh ID option.
     *
     * correctAnswer = index (id) của option đúng trong mảng sau shuffle (ví dụ: "2").
     * userAnswer    = id option user chọn (ví dụ: "2").
     * So sánh integer — không cần normalize string tiếng Nhật, không có encoding edge case.
     *
     * Fallback về LLM nếu correctAnswer không phải số (item cũ hoặc LLM không trả về isCorrect).
     */
    private EvaluationResult evaluateFillBlank(PracticeItem item, String userAnswer) {
        String correct = item.getCorrectAnswer();
        if (correct == null || correct.isBlank()) {
            return evaluateByLlm(item, userAnswer);
        }
        try {
            int correctId = Integer.parseInt(correct.trim());
            int userId    = Integer.parseInt(userAnswer == null ? "-1" : userAnswer.trim());
            boolean isCorrect = correctId == userId;
            String feedback = isCorrect
                    ? "Chính xác!"
                    : "Đáp án chưa đúng.";
            return new EvaluationResult(isCorrect, isCorrect ? 100 : 0, feedback,
                    isCorrect ? null : correct);
        } catch (NumberFormatException e) {
            // correctAnswer là text (item legacy) → fallback LLM
            log.debug("evaluateFillBlank: correctAnswer '{}' không phải ID, fallback LLM", correct);
            return evaluateByLlm(item, userAnswer);
        }
    }

    // ── Rule-based: SENTENCE_ORDER ────────────────────────────────────────

    /**
     * Đánh giá SENTENCE_ORDER bằng so sánh mảng token ID.
     *
     * correctAnswer = "0,1,2,3" — thứ tự đúng của token IDs (đúng thứ tự LLM sinh ra).
     * userAnswer    = "0,2,1,3" — thứ tự user kéo.
     * So sánh integer array — không cần xử lý chuỗi tiếng Nhật.
     *
     * Fallback về LLM nếu correctAnswer không phải dạng ID (item legacy).
     */
    private EvaluationResult evaluateSentenceOrder(PracticeItem item, String userAnswer) {
        String correct = item.getCorrectAnswer();
        if (correct == null || correct.isBlank()) {
            return evaluateByLlm(item, userAnswer);
        }
        try {
            int[] correctIds = parseIdArray(correct);
            int[] userIds    = parseIdArray(userAnswer == null ? "" : userAnswer);

            if (correctIds.length == 0) return evaluateByLlm(item, userAnswer);

            boolean isCorrect = java.util.Arrays.equals(correctIds, userIds);
            String feedback = isCorrect
                    ? "Chính xác! Thứ tự câu đúng."
                    : "Thứ tự chưa đúng.";
            return new EvaluationResult(isCorrect, isCorrect ? 100 : 0, feedback,
                    isCorrect ? null : correct);
        } catch (NumberFormatException e) {
            // correctAnswer là text (item legacy) → fallback LLM
            log.debug("evaluateSentenceOrder: correctAnswer '{}' không phải ID array, fallback LLM", correct);
            return evaluateByLlm(item, userAnswer);
        }
    }

    /** Parse "0,1,2,3" → int[]{0,1,2,3}. Ném NumberFormatException nếu không hợp lệ. */
    private static int[] parseIdArray(String input) {
        if (input == null || input.isBlank()) return new int[0];
        String[] parts = input.trim().split(",");
        int[] ids = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            ids[i] = Integer.parseInt(parts[i].trim());
        }
        return ids;
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
        if (userAnswer == null || item.getCorrectAnswer() == null) {
            return new EvaluationResult(false, 0, "Không thể đánh giá câu trả lời.", item.getCorrectAnswer());
        }
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

package com.fu.roleplay.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.common.ai.PromptFragment;
import com.fu.common.ai.PromptUtils;
import com.fu.roleplay.dto.response.SessionEvaluationResponse;
import com.fu.roleplay.evaluation.CrossSessionTracker;
import com.fu.roleplay.evaluation.ErrorPatternAggregator;
import com.fu.roleplay.evaluation.VocabularyRangeAnalyzer;
import com.fu.roleplay.enums.ErrorType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Session evaluator v2: LLM as qualitative synthesizer, NOT scorer.
 *
 * <p>Scores are computed from data (error counts, Azure pronunciation, mission status,
 * vocabulary JLPT distribution). The LLM's only job is to produce specific, honest,
 * actionable narrative feedback referencing those computed facts.
 *
 * <p>Based on research recommendations (MDPI 2025 systematic review):
 * use LLMs for qualitative synthesis rather than numerical scoring.
 */
@Service
@Slf4j
public class SessionEvaluatorAI {

    private static final String SYNTHESIZER_SYSTEM_PROMPT =
            "You are an AI tutor providing feedback on a Japanese conversation roleplay session.\n" +
            "You do NOT produce scores — scores are already computed from data.\n" +
            "Your role is to produce SPECIFIC, HONEST, ACTIONABLE qualitative feedback.\n\n" +
            PromptFragment.EVALUATOR_INJECTION_GUARD + "\n\n" +
            "LANGUAGE REQUIREMENT: ALL text MUST be written in Vietnamese (Tiếng Việt).\n\n" +
            PromptFragment.JSON_ONLY + "\n" +
            """
            Return a JSON object with these fields:
            - overallAssessment (String): 2-3 câu nhận xét tổng thể trung thực. Tham chiếu lỗi cụ thể theo tên.
            - jlptEstimate (String): e.g. "N5 (vững)" or "N5→N4 (đang chuyển tiếp)"
            - accuracyFeedback (String): 1-2 câu về lỗi cụ thể, gọi tên loại lỗi và đề xuất bài học cụ thể.
            - pronunciationFeedback (String): 1-2 câu về phát âm dựa trên dữ liệu.
            - taskCompletionFeedback (String): 1-2 câu về nhiệm vụ đã/chưa hoàn thành, gợi ý cách hoàn thành.
            - complexityFeedback (String): 1-2 câu về phân bố từ vựng JLPT, gợi ý mở rộng.
            - recurringWarning (String): Cảnh báo lỗi lặp nếu có, hoặc "" nếu không.
            - nextSteps (Array<String>): 2-3 hành động cụ thể (tên ngữ pháp, bộ từ vựng, loại bài tập).

            RULES:
            - Be honest. If error rate > 40%, do NOT say "doing well" or use vague praise.
            - Every feedback field MUST cite specific data (error count, word, grammar point).
            - accuracyFeedback MUST name the dominant error type AND quote at least one concrete
              error using the format: "bạn đã viết '<WRONG>' thay vì '<CORRECT>'". Never swap the
              two sides — WRONG is what the learner said, CORRECT is the target form.
            - complexityFeedback MUST reference the JLPT distribution numbers.
            - nextSteps MUST be actionable: name specific grammar points, vocabulary sets, or drill types.
            """;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public SessionEvaluatorAI(OpenAiChatModel chatModel, ObjectMapper objectMapper) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.objectMapper = objectMapper;
    }

    /**
     * Synthesizes qualitative feedback from computed facts. Does NOT generate scores.
     */
    public SessionEvaluationResponse.QualitativeSummary synthesize(
            ErrorPatternAggregator.ErrorBreakdown errorBreakdown,
            SessionEvaluationResponse.PronunciationSummary pronunciationSummary,
            BigDecimal taskCompletionScore,
            int completedMissions,
            int totalMissions,
            List<String> failedMissionTitles,
            VocabularyRangeAnalyzer.ComplexityResult complexity,
            List<CrossSessionTracker.RecurringPattern> recurringPatterns,
            String jlptLevel) {
        try {
            String userPrompt = buildFactsPrompt(
                    errorBreakdown, pronunciationSummary, taskCompletionScore,
                    completedMissions, totalMissions, failedMissionTitles,
                    complexity, recurringPatterns, jlptLevel);

            String rawResponse = chatClient.prompt()
                    .system(SYNTHESIZER_SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call()
                    .content();

            String cleanJson = PromptUtils.cleanMarkdownFences(rawResponse, "{}");
            JsonNode result = objectMapper.readTree(cleanJson);

            List<String> nextSteps = new ArrayList<>();
            result.path("nextSteps").forEach(s -> nextSteps.add(s.asText()));

            return SessionEvaluationResponse.QualitativeSummary.builder()
                    .overallAssessment(result.path("overallAssessment").asText(""))
                    .jlptEstimate(result.path("jlptEstimate").asText(""))
                    .accuracyFeedback(result.path("accuracyFeedback").asText(""))
                    .pronunciationFeedback(result.path("pronunciationFeedback").asText(""))
                    .taskCompletionFeedback(result.path("taskCompletionFeedback").asText(""))
                    .complexityFeedback(result.path("complexityFeedback").asText(""))
                    .recurringWarning(result.path("recurringWarning").asText(""))
                    .nextSteps(nextSteps.isEmpty() ? List.of("Tiếp tục luyện tập hội thoại") : nextSteps)
                    .build();

        } catch (Exception e) {
            log.error("Session evaluation synthesis failed: {}", e.getMessage());
            return fallbackSummary();
        }
    }

    private String buildFactsPrompt(
            ErrorPatternAggregator.ErrorBreakdown errorBreakdown,
            SessionEvaluationResponse.PronunciationSummary pronunciationSummary,
            BigDecimal taskCompletionScore,
            int completedMissions, int totalMissions, List<String> failedMissionTitles,
            VocabularyRangeAnalyzer.ComplexityResult complexity,
            List<CrossSessionTracker.RecurringPattern> recurringPatterns,
            String jlptLevel) {

        StringBuilder sb = new StringBuilder();
        sb.append("FACTS about the learner's session:\n\n");

        // Accuracy facts
        sb.append("## ACCURACY\n");
        sb.append("- Error rate: ").append(errorBreakdown.errorRate()).append("% (")
          .append(errorBreakdown.totalCorrections()).append(" corrections / ")
          .append(errorBreakdown.userMessageCount()).append(" messages)\n");
        sb.append("- Error breakdown: ");
        errorBreakdown.errorCounts().forEach((type, count) ->
                sb.append(type.name()).append(": ").append(count).append(", "));
        sb.append("\n");
        if (!errorBreakdown.topExamples().isEmpty()) {
            sb.append("- Top errors (format: WRONG → CORRECT). The left side is what the learner said incorrectly; the right side is the correct form:\n");
            for (var ex : errorBreakdown.topExamples()) {
                sb.append("  - WRONG=\"").append(ex.original()).append("\" CORRECT=\"").append(ex.corrected()).append("\"")
                  .append(" type=").append(ex.errorType().name());
                if (ex.explanation() != null && !ex.explanation().isBlank()) {
                    sb.append(" explanation=\"").append(ex.explanation()).append("\"");
                }
                sb.append("\n");
            }
        }

        // Pronunciation facts
        sb.append("\n## PRONUNCIATION\n");
        if (pronunciationSummary != null && pronunciationSummary.getTotalAssessed() > 0) {
            sb.append("- Average overall: ").append(pronunciationSummary.getAverageOverall()).append("/100\n");
            sb.append("- Accuracy: ").append(pronunciationSummary.getAverageAccuracy())
              .append(", Fluency: ").append(pronunciationSummary.getAverageFluency())
              .append(", Completeness: ").append(pronunciationSummary.getAverageCompleteness()).append("\n");
            sb.append("- Messages assessed: ").append(pronunciationSummary.getTotalAssessed()).append("\n");
        } else {
            sb.append("- No audio messages submitted (pronunciation not assessed)\n");
        }

        // Task completion facts
        sb.append("\n## TASK COMPLETION\n");
        sb.append("- Missions completed: ").append(completedMissions).append("/").append(totalMissions).append("\n");
        if (!failedMissionTitles.isEmpty()) {
            sb.append("- Not completed: ").append(String.join(", ", failedMissionTitles)).append("\n");
        }

        // Complexity facts
        sb.append("\n## VOCABULARY COMPLEXITY\n");
        sb.append("- Unique words used: ").append(complexity.uniqueVocabularyCount()).append("\n");
        if (!complexity.jlptDistribution().isEmpty()) {
            sb.append("- JLPT distribution: ");
            complexity.jlptDistribution().forEach((level, pct) ->
                    sb.append(level).append(": ").append(pct).append("%, "));
            sb.append("\n");
        } else {
            sb.append("- No vocabulary matched in database\n");
        }

        // Recurring patterns
        sb.append("\n## CROSS-SESSION PATTERNS\n");
        if (recurringPatterns.isEmpty()) {
            sb.append("- No prior session data available\n");
        } else {
            for (var rp : recurringPatterns) {
                sb.append("- ").append(rp.errorType().name())
                  .append(": ").append(rp.totalOccurrences()).append(" occurrences in ")
                  .append(rp.sessionCount()).append("/").append(rp.totalSessionsChecked())
                  .append(" recent sessions\n");
            }
        }

        sb.append("\n## TARGET JLPT LEVEL: ").append(jlptLevel != null ? jlptLevel : "N5").append("\n");

        return sb.toString();
    }

    private SessionEvaluationResponse.QualitativeSummary fallbackSummary() {
        return SessionEvaluationResponse.QualitativeSummary.builder()
                .overallAssessment("Đánh giá không khả dụng cho phiên hội thoại này.")
                .jlptEstimate("")
                .accuracyFeedback("")
                .pronunciationFeedback("")
                .taskCompletionFeedback("")
                .complexityFeedback("")
                .recurringWarning("")
                .nextSteps(List.of("Tiếp tục luyện tập hội thoại"))
                .build();
    }
}

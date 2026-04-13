package com.fu.roleplay.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.roleplay.entity.ConversationMessage;
import com.fu.roleplay.enums.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Aggregates correction data from conversation messages into structured error breakdowns.
 * Pure computation — no LLM calls, no DB access.
 *
 * <p>Grounded in the CAF (Complexity, Accuracy, Fluency) framework (Skehan 1998):
 * accuracy is measured by observable error rate, not LLM opinion.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ErrorPatternAggregator {

    private final ObjectMapper objectMapper;

    // Scaling factor per JLPT level — beginners tolerated more errors per message
    private static final Map<String, Double> JLPT_SCALING = Map.of(
            "N5", 150.0,
            "N4", 130.0,
            "N3", 110.0,
            "N2", 90.0,
            "N1", 70.0
    );
    private static final double DEFAULT_SCALING = 130.0;

    public ErrorBreakdown aggregate(List<ConversationMessage> messages, String jlptLevel) {
        long userMessageCount = messages.stream()
                .filter(m -> m.getRole() == ConversationMessage.MessageRole.USER)
                .count();

        Map<ErrorType, Integer> errorCounts = new EnumMap<>(ErrorType.class);
        List<CorrectionExample> topExamples = new ArrayList<>();
        int totalCorrections = 0;

        // Corrections are on ASSISTANT messages (AI responds with corrections of learner's input)
        for (ConversationMessage msg : messages) {
            if (msg.getRole() != ConversationMessage.MessageRole.ASSISTANT) continue;
            if (msg.getCorrections() == null || msg.getCorrections().equals("[]")) continue;

            try {
                List<Map<String, String>> corrections = objectMapper.readValue(
                        msg.getCorrections(), new TypeReference<>() {});

                for (Map<String, String> correction : corrections) {
                    totalCorrections++;
                    ErrorType type = parseErrorType(correction.get("errorType"));
                    errorCounts.merge(type, 1, Integer::sum);

                    if (topExamples.size() < 5) {
                        topExamples.add(new CorrectionExample(
                                correction.getOrDefault("original", ""),
                                correction.getOrDefault("corrected", ""),
                                correction.getOrDefault("explanation", ""),
                                type
                        ));
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to parse corrections JSON for message {}: {}", msg.getId(), e.getMessage());
            }
        }

        BigDecimal errorRate = userMessageCount > 0
                ? BigDecimal.valueOf(totalCorrections)
                    .divide(BigDecimal.valueOf(userMessageCount), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        double scaling = JLPT_SCALING.getOrDefault(jlptLevel, DEFAULT_SCALING);
        double rawScore = 100.0 - (errorRate.doubleValue() * scaling / 100.0);
        BigDecimal accuracyScore = BigDecimal.valueOf(Math.max(0, Math.min(100, rawScore)))
                .setScale(2, RoundingMode.HALF_UP);

        return new ErrorBreakdown(
                totalCorrections,
                (int) userMessageCount,
                errorRate,
                accuracyScore,
                errorCounts,
                topExamples
        );
    }

    private ErrorType parseErrorType(String raw) {
        if (raw == null || raw.isBlank()) return ErrorType.OTHER;
        try {
            return ErrorType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ErrorType.OTHER;
        }
    }

    public record ErrorBreakdown(
            int totalCorrections,
            int userMessageCount,
            BigDecimal errorRate,
            BigDecimal accuracyScore,
            Map<ErrorType, Integer> errorCounts,
            List<CorrectionExample> topExamples
    ) {
        public Map<String, Integer> errorCountsAsStringMap() {
            return errorCounts.entrySet().stream()
                    .collect(Collectors.toMap(e -> e.getKey().name(), Map.Entry::getValue));
        }
    }

    public record CorrectionExample(
            String original,
            String corrected,
            String explanation,
            ErrorType errorType
    ) {}
}

package com.fu.roleplay.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.roleplay.entity.LearnerErrorPattern;
import com.fu.roleplay.enums.ErrorType;
import com.fu.roleplay.repository.LearnerErrorPatternRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Tracks and queries error patterns across multiple sessions for a learner.
 * Enables detection of recurring weaknesses (e.g., "particle errors in 4/5 recent sessions").
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CrossSessionTracker {

    private final LearnerErrorPatternRepository repository;
    private final ObjectMapper objectMapper;

    private static final int DEFAULT_SESSION_LOOKBACK = 5;

    /**
     * Persists error type counts for a completed session.
     */
    public void recordSessionErrors(UUID userId, UUID sessionId,
                                     ErrorPatternAggregator.ErrorBreakdown breakdown) {
        for (Map.Entry<ErrorType, Integer> entry : breakdown.errorCounts().entrySet()) {
            if (entry.getValue() <= 0) continue;

            List<ErrorPatternAggregator.CorrectionExample> examples = breakdown.topExamples().stream()
                    .filter(ex -> ex.errorType() == entry.getKey())
                    .limit(3)
                    .toList();

            String examplesJson = "[]";
            try {
                examplesJson = objectMapper.writeValueAsString(examples);
            } catch (Exception e) {
                log.debug("Failed to serialize examples: {}", e.getMessage());
            }

            LearnerErrorPattern pattern = LearnerErrorPattern.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .errorType(entry.getKey())
                    .occurrenceCount(entry.getValue())
                    .examples(examplesJson)
                    .build();
            repository.save(pattern);
        }
    }

    /**
     * Queries recurring error patterns over the N most recent completed sessions.
     */
    public List<RecurringPattern> getRecurringPatterns(UUID userId) {
        return getRecurringPatterns(userId, DEFAULT_SESSION_LOOKBACK);
    }

    public List<RecurringPattern> getRecurringPatterns(UUID userId, int sessionLimit) {
        List<Object[]> rows = repository.findTopErrorTypesByRecentSessions(userId, sessionLimit);

        return rows.stream()
                .map(row -> {
                    String errorTypeName = (String) row[0];
                    long totalCount = ((Number) row[1]).longValue();
                    long sessionCount = ((Number) row[2]).longValue();

                    ErrorType errorType;
                    try {
                        errorType = ErrorType.valueOf(errorTypeName);
                    } catch (IllegalArgumentException e) {
                        errorType = ErrorType.OTHER;
                    }

                    return new RecurringPattern(
                            errorType,
                            (int) totalCount,
                            (int) sessionCount,
                            sessionLimit
                    );
                })
                .filter(p -> p.sessionCount() >= 2) // only flag if recurring in 2+ sessions
                .collect(Collectors.toList());
    }

    public record RecurringPattern(
            ErrorType errorType,
            int totalOccurrences,
            int sessionCount,
            int totalSessionsChecked
    ) {}
}

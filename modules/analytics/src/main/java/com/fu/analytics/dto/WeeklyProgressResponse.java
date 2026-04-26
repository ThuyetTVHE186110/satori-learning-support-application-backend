package com.fu.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeeklyProgressResponse {

    private String period;
    private List<ProgressDataPoint> dataPoints;
    private TrendSummary trends;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProgressDataPoint {
        private String label;
        private LocalDate startDate;
        private LocalDate endDate;

        // Practice (existing — backwards compatible)
        private int sessionsCompleted;
        private long totalStudyMinutes;
        private double averageScore;
        private int expEarned;

        // Speaking — pronunciation (Azure, per-utterance average).
        // No prosody (always 0 for ja-JP), no completeness (degenerate in unscripted mode).
        // Nullable: null when user had zero utterances in the period.
        private Integer pronunciationUtterances;
        private Double  pronunciationOverall;
        private Double  pronunciationAccuracy;
        private Double  pronunciationFluency;

        // Speaking — language & task (LLM-judged, per-completed-session average).
        // languageOverall = ConversationSession.languageScore (grammar/vocab in speech),
        // intentionally renamed to avoid collision with Azure accuracyScore.
        private Integer speakingSessions;
        private Double  languageOverall;
        private Double  speakingTaskCompletion;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrendSummary {
        private String scoreDirection;
        private String studyTimeDirection;
        private double consistencyRate;

        /** UP / DOWN / STABLE for pronunciation overall (Azure). */
        private String pronunciationDirection;
        /** UP / DOWN / STABLE for language overall (LLM). */
        private String languageDirection;

        /**
         * Numeric deltas (current period − previous period). Reference-dependent
         * framing: absolute numbers are ambiguous without an anchor. Null when
         * either side has no data (e.g., no speaking yet).
         */
        private Integer sessionsDelta;
        private Double  scoreDelta;
        private Long    studyMinutesDelta;
        private Integer speakingSessionsDelta;
        private Double  pronunciationDelta;
        private Double  languageDelta;
    }
}

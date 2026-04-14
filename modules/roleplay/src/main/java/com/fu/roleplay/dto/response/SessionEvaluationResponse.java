package com.fu.roleplay.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fu.achievement.dto.response.LevelUpNotificationResponse;
import com.fu.achievement.dto.response.UserAchievementResponse;
import com.fu.achievement.dto.response.StreakNotificationResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionEvaluationResponse {

    private UUID sessionId;

    // ── Internal only (gamification EXP/badges) — NOT shown to learner ──
    @JsonIgnore
    private BigDecimal overallScore;

    // ── Legacy fields (kept for backward compat, deprecated) ──
    @Deprecated
    private BigDecimal missionScore;
    @Deprecated
    private BigDecimal pronunciationScore;
    @Deprecated
    private BigDecimal languageScore;

    // ── NEW: 4 Orthogonal Dimensions (shown to learner) ──
    private AccuracyDimension accuracy;
    private PronunciationDimension pronunciation;
    private TaskCompletionDimension taskCompletion;
    private ComplexityDimension complexity;

    // ── Qualitative Summary (LLM-synthesized from facts) ──
    private QualitativeSummary qualitativeSummary;

    // ── Cross-session recurring patterns ──
    private List<RecurringErrorPattern> recurringPatterns;

    // ── Legacy mission details (kept for backward compat) ──
    private List<MissionEvaluationDetail> missionDetails;

    // ── Legacy language evaluation (kept for backward compat) ──
    @Deprecated
    private LanguageEvaluationDetail languageEvaluation;

    @JsonProperty("newBadgesEarned")
    private List<UserAchievementResponse> earnedBadges;

    @JsonProperty("levelUp")
    private LevelUpNotificationResponse levelUpInfo;

    @JsonProperty("streakNotification")
    private StreakNotificationResponse streakNotification;

    // ── New dimension inner classes ──

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AccuracyDimension {
        private BigDecimal score;
        private int totalCorrections;
        private int userMessageCount;
        private BigDecimal errorRate;
        private Map<String, Integer> errorBreakdown;
        private String feedback;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PronunciationDimension {
        private BigDecimal score;
        private BigDecimal averageAccuracy;
        private BigDecimal averageFluency;
        private BigDecimal averageCompleteness;
        private int totalAssessed;
        private String feedback;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskCompletionDimension {
        private BigDecimal score;
        private int completedMissions;
        private int totalMissions;
        private List<MissionEvaluationDetail> details;
        private String feedback;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComplexityDimension {
        private BigDecimal score;
        private int uniqueVocabularyCount;
        private Map<String, BigDecimal> jlptDistribution;
        private String feedback;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QualitativeSummary {
        private String overallAssessment;
        private String jlptEstimate;
        private String accuracyFeedback;
        private String pronunciationFeedback;
        private String taskCompletionFeedback;
        private String complexityFeedback;
        private String recurringWarning;
        private List<String> nextSteps;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecurringErrorPattern {
        private String errorType;
        private int totalOccurrences;
        private int sessionCount;
        private int totalSessionsChecked;
    }

    // ── Legacy inner classes (kept for backward compat) ──

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MissionEvaluationDetail {
        private String title;
        private String titleVi;
        private String titleJapanese;
        private String status;
        private Integer progressPct;
        private String reasoning;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PronunciationSummary {
        private BigDecimal averageOverall;
        private BigDecimal averageAccuracy;
        private BigDecimal averageFluency;
        private BigDecimal averageCompleteness;
        private int totalAssessed;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Deprecated
    public static class LanguageEvaluationDetail {
        private BigDecimal fluencyScore;
        private BigDecimal accuracyScore;
        private BigDecimal vocabularyScore;
        private BigDecimal grammarScore;
        private List<String> strengths;
        private List<String> improvements;
        private String summary;
    }
}

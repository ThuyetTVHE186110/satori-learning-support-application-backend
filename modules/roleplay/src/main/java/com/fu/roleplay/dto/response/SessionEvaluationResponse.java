package com.fu.roleplay.dto.response;

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
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionEvaluationResponse {

    private UUID sessionId;

    // Overall scores
    private BigDecimal missionScore;
    private BigDecimal pronunciationScore;
    private BigDecimal languageScore;
    private BigDecimal overallScore;

    // Mission details
    private List<MissionEvaluationDetail> missionDetails;

    // Pronunciation summary
    private PronunciationSummary pronunciationSummary;

    // Language evaluation
    private LanguageEvaluationDetail languageEvaluation;

    /**
     * Badges earned when completing this session.
     */
    @JsonProperty("newBadgesEarned")
    private List<UserAchievementResponse> earnedBadges;

    /**
     * Level-up notification if user leveled up during submission.
     */
    @JsonProperty("levelUp")
    private LevelUpNotificationResponse levelUpInfo;

    /**
     * Streak notification indicating first activity of day.
     */
    @JsonProperty("streakNotification")
    private StreakNotificationResponse streakNotification;

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
        private BigDecimal averageProsody;
        private BigDecimal averagePitchAccent;
        private BigDecimal averageMoraTiming;
        private int totalAssessed;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
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

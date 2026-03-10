package com.fu.roleplay.dto.response;

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

package com.fu.roleplay.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PronunciationScoreResponse {

    private BigDecimal overallScore;
    private BigDecimal accuracyScore;
    private BigDecimal fluencyScore;
    private BigDecimal completenessScore;
    private String transcribedText;

    private List<CriteriaEvaluation> criteriaEvaluations;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CriteriaEvaluation {
        private String name;
        private String label;
        private BigDecimal score;
    }
}

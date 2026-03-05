package com.fu.roleplay.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PronunciationScoreResponse {

    private BigDecimal overallScore;
    private BigDecimal accuracyScore;
    private BigDecimal fluencyScore;
    private BigDecimal prosodyScore;
    private String transcribedText;
    private BigDecimal pitchAccentScore;
    private BigDecimal moraTimingScore;
}

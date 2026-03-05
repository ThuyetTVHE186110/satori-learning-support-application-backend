package com.fu.pronunciation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssessmentResult {

    private double overallScore;
    private double accuracyScore;
    private double fluencyScore;
    private double prosodyScore;
    private String transcribedText;
    private String rawResponse;
    private boolean success;
    private String errorMessage;

    // Japanese-specific scores (from custom pitch accent / mora timing service)
    private double pitchAccentScore;
    private double moraTimingScore;
    private String detailedPronunciationFeedback; // JSON string

    public static AssessmentResult failure(String errorMessage) {
        return AssessmentResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}

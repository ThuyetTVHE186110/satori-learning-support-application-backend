package com.fu.pronunciation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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

    // Japanese pitch accent / mora timing — always 0, kept for API/DB compatibility
    private double pitchAccentScore;
    private double moraTimingScore;

    // Azure-specific fields (null when using SpeechSuper)
    private double completenessScore;
    private String providerName; // "AZURE" | "SPEECHSUPER"
    private List<WordAssessment> wordDetails;

    public static AssessmentResult failure(String errorMessage) {
        return AssessmentResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WordAssessment {
        private String word;
        private double accuracyScore;
        private String errorType; // None, Omission, Insertion, Mispronunciation
        private List<PhonemeAssessment> phonemes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PhonemeAssessment {
        private String phoneme; // IPA
        private double accuracyScore;
    }
}

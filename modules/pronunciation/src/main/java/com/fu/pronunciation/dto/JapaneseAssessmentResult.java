package com.fu.pronunciation.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class JapaneseAssessmentResult {

    private boolean success;

    @JsonProperty("pitch_accent_score")
    private double pitchAccentScore;

    @JsonProperty("mora_timing_score")
    private double moraTimingScore;

    @JsonProperty("detailed_feedback")
    private DetailedFeedback detailedFeedback;

    @JsonProperty("processing_ms")
    private int processingMs;

    @JsonProperty("error_message")
    private String errorMessage;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DetailedFeedback {
        private List<WordDetail> words;

        @JsonProperty("mora_details")
        private List<MoraDetail> moraDetails;

        @JsonProperty("sokuon_accuracy")
        private Double sokuonAccuracy;

        @JsonProperty("hatsuon_accuracy")
        private Double hatsuonAccuracy;

        @JsonProperty("long_vowel_accuracy")
        private Double longVowelAccuracy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WordDetail {
        private String word;
        private String reading;

        @JsonProperty("expected_pattern")
        private String expectedPattern;

        @JsonProperty("detected_pattern")
        private String detectedPattern;

        @JsonProperty("pitch_correct")
        private boolean pitchCorrect;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MoraDetail {
        private String mora;

        @JsonProperty("expected_relative_duration")
        private double expectedRelativeDuration;

        @JsonProperty("actual_relative_duration")
        private double actualRelativeDuration;

        private String status;

        @JsonProperty("mora_type")
        private String moraType;
    }
}

package com.fu.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillRadarResponse {

    private List<SkillItem> skills;
    private double overallScore;
    private String targetJlptLevel;
    private LocalDateTime lastUpdated;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillItem {
        private String skill;
        private double score;
        private double previousScore;
        private String trend;
        private String color;
        private int dataPoints;
    }

    /** Color logic: green (≥80), amber (50-79), red (<50). */
    public static String colorForScore(double score) {
        if (score >= 80) return "#4CAF50";
        if (score >= 50) return "#FF9800";
        return "#F44336";
    }
}

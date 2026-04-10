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
        private int sessionsCompleted;
        private long totalStudyMinutes;
        private double averageScore;
        private int expEarned;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrendSummary {
        private String scoreDirection;
        private String studyTimeDirection;
        private double consistencyRate;
    }
}

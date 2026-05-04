package com.fu.monitoring.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentManagerDashboardResponse {
    private SummarySection summary;
    private List<StatusDistributionChart> statusDistributions;
    private PerformanceMetricsSection performance;
    private MostPracticedContentSection mostPracticedContent;
    private PracticeTrendSection practiceTrend;
    private ContentImprovementSection contentNeedsImprovement;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SummarySection {
        private long totalLessons;
        private long totalVocabulary;
        private long totalGrammarPoints;
        private long totalConversations;
        private long totalTopics;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatusDistributionChart {
        private String title;
        private long total;
        private long published;
        private long archived;
        private long draft;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PerformanceMetricsSection {
        private PerformanceMetric totalSpeakingSessions;
        private PerformanceMetric averageSystemScore;
        private PerformanceMetric averagePracticeSessionDuration;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PerformanceMetric {
        private String label;
        private String value;
        private String unit;
    }
}

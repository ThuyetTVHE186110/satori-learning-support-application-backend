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
public class ActivityHeatmapResponse {

    private int currentStreak;
    private int longestStreak;
    private int totalActiveDays;
    private List<HeatmapDay> heatmap;
    private int streakFreezeCount;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HeatmapDay {
        private LocalDate date;
        private int activityCount;
        private int intensity;
        private List<String> activities;
    }

    public static int intensityForCount(int count) {
        if (count == 0) return 0;
        if (count == 1) return 1;
        if (count <= 3) return 2;
        return 3;
    }
}

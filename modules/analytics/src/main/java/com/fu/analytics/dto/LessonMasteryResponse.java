package com.fu.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LessonMasteryResponse {

    private UUID courseId;
    private String courseName;
    private List<LessonMasteryItem> lessons;
    private CourseProgressSummary courseProgress;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LessonMasteryItem {
        private UUID lessonId;
        private String lessonTitle;
        private int orderIndex;
        private double vocabMastery;
        private double grammarMastery;
        private double overallMastery;
        private int totalSessions;
        private double bestScore;
        private LocalDateTime lastPracticed;
        private String status;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CourseProgressSummary {
        private int lessonsStarted;
        private int totalLessons;
        private double overallMastery;
    }

    public static String statusForMastery(double mastery) {
        if (mastery >= 90) return "MASTERED";
        if (mastery >= 70) return "PROFICIENT";
        if (mastery >= 40) return "FAMILIAR";
        if (mastery > 0)   return "NEEDS_WORK";
        return "NOT_STARTED";
    }
}

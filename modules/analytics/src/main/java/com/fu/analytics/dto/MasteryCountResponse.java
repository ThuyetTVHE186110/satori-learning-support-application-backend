package com.fu.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Mastery counts for the learner dashboard "đã master bao nhiêu" surface.
 *
 * Vocab and grammar are intentionally split (WaniKani-style) instead of a single
 * combined number, so users see the vocab/grammar tradeoff. "mastered" = items
 * with {@code masteryLevel >= 2} ("Nắm vững" + "Thành thạo"); the threshold is
 * already EMA-driven inside {@code ItemMastery.computeMasteryLevel()}, so no
 * separate decay model is layered on top.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MasteryCountResponse {

    private Scope scope;

    private Bucket vocabulary;
    private Bucket grammar;

    /** Items with level <= 1 in scope, oldest practice first — drives "Cần ôn lại". */
    private List<ReviewItem> needsReview;

    private LocalDateTime lastUpdated;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Scope {
        /** GLOBAL | COURSE | LESSON */
        private String type;
        private UUID id;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Bucket {
        private long mastered;     // level >= 2
        private long learning;     // level 0 or 1 with attempts
        private long notStarted;   // total - (mastered + learning)
        private long total;        // count of items in scope (PUBLISHED only)
        private int  percent;      // mastered / total * 100, 0 if total=0
        private List<Tier> tiers;  // breakdown by mastery level 0..3
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Tier {
        private int level;       // 0..3
        private String label;    // "Chưa thành thạo" | "Quen thuộc" | "Nắm vững" | "Thành thạo"
        private long count;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReviewItem {
        private UUID entityId;
        private String entityType;            // "VOCABULARY" | "GRAMMAR"
        private int masteryLevel;
        private double emaScore;
        private LocalDateTime lastPracticedAt;
    }

    public static String labelForLevel(int level) {
        return switch (level) {
            case 1  -> "Quen thuộc";
            case 2  -> "Nắm vững";
            case 3  -> "Thành thạo";
            default -> "Chưa thành thạo";
        };
    }
}

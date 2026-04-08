package com.fu.practice.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Response cho GET /api/v1/learner/practice/lessons/{lessonId}/items
 * Dùng để render item picker trên UI trước khi bắt đầu phiên luyện tập.
 */
@Data
@Builder
public class LessonItemsResponse {

    private List<VocabItemDto> vocabulary;
    private List<GrammarItemDto> grammar;
    private LessonItemSummary summary;

    @Data
    @Builder
    public static class VocabItemDto {
        private UUID   id;
        private String japaneseText;
        private String reading;
        private String meaningVi;
        private String partOfSpeech;
        /** null nếu học viên chưa luyện item này lần nào. */
        private MasteryDto mastery;
    }

    @Data
    @Builder
    public static class GrammarItemDto {
        private UUID   id;
        private String pattern;
        private String meaningVi;
        private String shortExplanation;
        /** null nếu học viên chưa luyện item này lần nào. */
        private MasteryDto mastery;
    }

    @Data
    @Builder
    public static class MasteryDto {
        /** 0=Chưa luyện, 1=Quen thuộc, 2=Nắm vững, 3=Thuộc */
        private int    level;
        /** Nhãn hiển thị: "Chưa luyện" / "Quen thuộc" / "Nắm vững" / "Thuộc" */
        private String label;
        private int    attemptCount;
        /** EMA score [0.0, 1.0] — dùng cho client sort/filter nếu cần */
        private double emaScore;
        private LocalDateTime lastPracticedAt;
    }

    @Data
    @Builder
    public static class LessonItemSummary {
        private int totalVocab;
        private int totalGrammar;
        /** Items với mastery_level == 3 */
        private int masteredVocab;
        private int masteredGrammar;
        /** Items với mastery_level <= 1 (cần ôn tập) */
        private int needsReviewCount;
    }
}

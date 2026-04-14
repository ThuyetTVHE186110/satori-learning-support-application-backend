package com.fu.practice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fu.achievement.dto.response.UserAchievementResponse;
import com.fu.achievement.dto.response.LevelUpNotificationResponse;
import com.fu.achievement.dto.response.StreakNotificationResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Tổng kết phiên luyện tập sau khi hoàn thành.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionSummaryResponse {

    private UUID sessionId;
    private BigDecimal score;
    private int correctItems;
    private int totalItems;

    /**
     * AI feedback dạng chuỗi văn bản (parse từ JSON aiFeedback).
     * Gồm: tổng kết, điểm mạnh, điểm yếu, gợi ý.
     */
    private String aiFeedback;

    /** Danh sách kết quả chi tiết từng câu. */
    private List<ItemResultDto> items;

    /** Badges earned when completing this session. */
    @JsonProperty("newBadgesEarned")
    private List<UserAchievementResponse> earnedBadges;

    /** Level-up notification if user leveled up during this session. */
    @JsonProperty("levelUp")
    private LevelUpNotificationResponse levelUpInfo;

    /** Streak notification indicating first activity of day. */
    @JsonProperty("streakNotification")
    private StreakNotificationResponse streakNotification;

    /**
     * Thay đổi mastery level của từng item trong phiên này.
     * Chỉ chứa items có refContentId (có thể track mastery).
     * Dùng để hiển thị "先生: ◔ → ●" trên màn hình summary.
     */
    @JsonProperty("masteryChanges")
    private List<MasteryChangeDto> masteryChanges;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MasteryChangeDto {
        private UUID   entityId;
        private String entityType;       // "VOCABULARY" | "GRAMMAR"
        private String japaneseText;     // "先生" / "〜てください"
        private int    levelBefore;
        private int    levelAfter;
        /** true nếu levelAfter == 3 && levelBefore < 3 — để show celebration UI */
        private boolean newlyMastered;
    }
}

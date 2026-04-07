package com.fu.practice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fu.achievement.dto.response.UserAchievementResponse;
import com.fu.achievement.dto.response.LevelUpNotificationResponse;
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
}

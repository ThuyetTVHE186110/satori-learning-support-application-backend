package com.fu.monitoring.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Achievement metrics for the 3 summary cards.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AchievementMetricsResponse {
    private Long totalBadgesAwarded;
    /** Percentage of users who have earned at least one badge (0-100) */
    private Double badgeAwardRate;
    /** Tổng lượt nhận badge của tất cả học sinh trong hệ thống */
    private Long totalBadgeReceipts;
}

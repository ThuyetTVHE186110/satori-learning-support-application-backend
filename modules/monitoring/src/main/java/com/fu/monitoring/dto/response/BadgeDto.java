package com.fu.monitoring.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Single badge entry for the popular badges horizontal bar chart.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BadgeDto {
    private UUID id;
    private String name;
    private Long awardCount;
}

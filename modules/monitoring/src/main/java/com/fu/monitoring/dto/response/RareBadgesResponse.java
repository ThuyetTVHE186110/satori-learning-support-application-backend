package com.fu.monitoring.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * "Thành tích ít người đạt" — badges with the fewest earners.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RareBadgesResponse {
    private List<RareBadgeDto> badges;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RareBadgeDto {
        private java.util.UUID id;
        private String name;
        private Long awardCount;
        /** Always "Chú ý" — flags this badge as rare/needs attention */
        private String label;
    }
}

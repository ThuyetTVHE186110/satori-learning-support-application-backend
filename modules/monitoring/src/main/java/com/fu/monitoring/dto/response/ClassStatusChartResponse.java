package com.fu.monitoring.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Class status distribution for the donut chart.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassStatusChartResponse {
    /** Đang diễn ra */
    private Long active;
    /** Đã kết thúc */
    private Long closed;
    /** Chưa bắt đầu */
    private Long notStarted;
}

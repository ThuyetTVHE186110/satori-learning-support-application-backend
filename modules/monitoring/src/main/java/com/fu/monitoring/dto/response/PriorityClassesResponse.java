package com.fu.monitoring.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Paginated list of priority classes requiring attention.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriorityClassesResponse {
    private List<PriorityClassResponse> content;
    private Long totalElements;
    private Integer totalPages;
    private Integer criticalCount;
}

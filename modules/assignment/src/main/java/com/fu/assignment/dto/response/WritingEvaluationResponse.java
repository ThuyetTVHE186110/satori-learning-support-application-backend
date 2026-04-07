package com.fu.assignment.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for writing evaluation result.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WritingEvaluationResponse {
    private String feedback;
}

package com.fu.assignment.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

/**
 * Request DTO for teacher to grade a WRITING or TRANSLATION submission.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GradeWritingRequest {

    @NotNull(message = "Score is required")
    @DecimalMin(value = "0.0", message = "Score cannot be negative")
    @DecimalMax(value = "100.0", message = "Score cannot exceed 100")
    private BigDecimal teacherScore;

    /** Feedback for the learner (required) */
    @NotNull(message = "Feedback is required")
    private String feedback;
}

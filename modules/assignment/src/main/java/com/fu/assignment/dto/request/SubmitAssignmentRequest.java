package com.fu.assignment.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;
import java.util.UUID;

/**
 * Request DTO for submitting an assignment.
 * Used by LEARNER role.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmitAssignmentRequest {

    /**
     * Answers as JSON string.
     * Structure: [{"questionId":"uuid","selectedAnswer":"A","timeSpent":15}, ...]
     */
    @NotEmpty(message = "Answers must not be empty")
    private String answers;

    /** Total time spent on this attempt (seconds) */
    private Integer timeSpentSeconds;
}

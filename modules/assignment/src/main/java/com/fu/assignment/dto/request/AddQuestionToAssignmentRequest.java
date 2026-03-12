package com.fu.assignment.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.UUID;

/**
 * Request DTO for adding a quiz question to an assignment.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddQuestionToAssignmentRequest {

    @NotNull(message = "Question ID is required")
    private UUID questionId;

    @Builder.Default
    private Integer points = 10;

    @Builder.Default
    private Integer orderIndex = 0;
}

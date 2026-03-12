package com.fu.assignment.dto.request;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Request DTO for cloning/duplicating an existing assignment to another class.
 * The cloned assignment is always created as DRAFT with no submissions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloneAssignmentRequest {

    /** The target class to assign the cloned assignment to */
    @NotNull(message = "Target class ID is required")
    private UUID targetClassId;

    /** New due date for the cloned assignment (must be in the future) */
    @NotNull(message = "Due date is required")
    @Future(message = "Due date must be in the future")
    private LocalDateTime newDueDate;

    /** Optional: override title (null = keep original title) */
    private String newTitle;
}


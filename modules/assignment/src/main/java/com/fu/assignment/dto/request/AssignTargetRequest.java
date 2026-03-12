package com.fu.assignment.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.UUID;

/**
 * Request DTO for assigning an assignment to a class or user.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssignTargetRequest {

    /** Assign to entire class (mutually exclusive with userId, but at least one required) */
    private UUID classId;

    /** Assign to specific user (mutually exclusive with classId, but at least one required) */
    private UUID userId;
}

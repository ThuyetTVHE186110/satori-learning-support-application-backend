package com.fu.assignment.dto.response;

import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for AssignmentTarget (class or user assignment).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssignmentTargetResponse {

    private UUID id;
    private UUID classId;
    private String className;       // enriched
    private UUID userId;
    private String userFullName;    // enriched
    private String userEmail;       // enriched
    private LocalDateTime assignedAt;
    private UUID assignedBy;
}

package com.fu.assignment.dto.response;

import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for WritingAssignment content.
 * Returned when teacher views the writing/translation content of an assignment.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WritingAssignmentResponse {

    private UUID id;
    private UUID assignmentId;
    private String prompt;
    private String sourceText;            // For TRANSLATION: the passage to translate
    private LocalDateTime createdAt;
}

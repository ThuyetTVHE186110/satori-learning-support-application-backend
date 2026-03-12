package com.fu.assignment.dto.request;

import com.fu.assignment.entity.AssignmentStatus;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Request DTO for updating an Assignment.
 * All fields are optional — only non-null values will be applied.
 * 
 * Supports status transitions via the {@code status} field:
 * 
 *   DRAFT → PUBLISHED (publishes the assignment)
 *   PUBLISHED → CLOSED (closes the assignment)
 * 
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateAssignmentRequest {

    /**
     * Optional status change. Allowed transitions:
     * DRAFT → PUBLISHED, PUBLISHED → CLOSED.
     */
    private AssignmentStatus status;

    @Size(max = 255)
    private String title;

    private String description;

    private String instructions;

    private LocalDateTime startDate;

    @Future(message = "Due date must be in the future")
    private LocalDateTime dueDate;

    private Integer timeLimitMinutes;

    private Boolean lateSubmissionAllowed;

    private Integer lateSubmissionPenaltyPercent;

    private Integer maxScore;

    private Integer passingScore;

    private Boolean shuffleQuestions;

    private Boolean shuffleOptions;

    private Boolean showCorrectAnswers;

    private Boolean allowRetry;

    private Integer maxRetries;

    private UUID lessonId;

    private Integer questionCount;
}

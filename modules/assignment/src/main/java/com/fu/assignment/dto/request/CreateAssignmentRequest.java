package com.fu.assignment.dto.request;

import com.fu.assignment.entity.AssignmentType;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Request DTO for creating a new Assignment.
 * Used by MANAGER role.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateAssignmentRequest {

    @NotBlank(message = "Title is required")
    @Size(max = 255, message = "Title must not exceed 255 characters")
    private String title;

    private String description;

    private String instructions;

    @NotNull(message = "Assignment type is required")
    private AssignmentType assignmentType;

    private LocalDateTime startDate;

    @NotNull(message = "Due date is required")
    @Future(message = "Due date must be in the future")
    private LocalDateTime dueDate;

    /** NULL = no time limit */
    private Integer timeLimitMinutes;

    @Builder.Default
    private Boolean lateSubmissionAllowed = false;

    @Builder.Default
    private Integer lateSubmissionPenaltyPercent = 0;

    @Builder.Default
    private Integer maxScore = 100;

    @Builder.Default
    private Integer passingScore = 60;

    @Builder.Default
    private Boolean shuffleQuestions = false;

    @Builder.Default
    private Boolean shuffleOptions = false;

    @Builder.Default
    private Boolean showCorrectAnswers = true;

    @Builder.Default
    private Boolean allowRetry = false;

    @Builder.Default
    private Integer maxRetries = 1;

    /** Link to lesson for auto-quiz generation */
    private UUID lessonId;

    @Builder.Default
    private Integer questionCount = 10;
}

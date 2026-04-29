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

    /**
     * Reference to lesson for this assignment (required at creation).
     * Associates the assignment with specific lesson content.
     */
    @NotNull(message = "Lesson is required")
    private UUID lessonId;

    @Builder.Default
    private Integer questionCount = 10;
}

package com.fu.assignment.dto.response;

import com.fu.assignment.entity.AssignmentType;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Learner-safe version of AssignmentResponse.
 * Uses LearnerQuizQuestionResponse to hide correct answers.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LearnerAssignmentResponse {

    private UUID id;
    private String title;
    private String description;
    private String instructions;
    private AssignmentType assignmentType;

    private LocalDateTime startDate;
    private LocalDateTime dueDate;
    private String audioUrl;
    private Long actualQuestionCount;

    private List<LearnerQuizQuestionResponse> questions;
    private WritingAssignmentResponse writingContent;
}

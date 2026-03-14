package com.fu.assignment.dto.response;

import com.fu.assignment.entity.AssignmentStatus;
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
    private Integer timeLimitMinutes;
    private Boolean lateSubmissionAllowed;
    private Integer lateSubmissionPenaltyPercent;

    private String audioUrl;
    private Integer questionCount;
    private Long actualQuestionCount;

    private AssignmentStatus status;
    private UUID createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Boolean hasSubmissions;

    private List<LearnerQuizQuestionResponse> questions;
    private WritingAssignmentResponse writingContent;
    private Long submissionCount;
}

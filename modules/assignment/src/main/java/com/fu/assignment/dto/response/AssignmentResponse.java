package com.fu.assignment.dto.response;

import com.fu.assignment.entity.AssignmentStatus;
import com.fu.assignment.entity.AssignmentType;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Full assignment response (detail view).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssignmentResponse {

    private UUID id;
    private String title;
    private String description;
    private String instructions;
    private AssignmentType assignmentType;

    // Timing
    private LocalDateTime startDate;
    private LocalDateTime dueDate;

    // Scoring

    // Content
    private String audioUrl;
    private Integer questionCount;
    private Long actualQuestionCount;   // computed from assignment_questions table

    // Status & Audit
    private AssignmentStatus status;
    private UUID createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Enriched
    private Boolean hasSubmissions;
    private List<AssignmentQuestionResponse> questions;        // For QUIZ
    private WritingAssignmentResponse writingContent;          // For WRITING / TRANSLATION
    private List<AssignmentTargetResponse> targets;
    private Long submissionCount;
}

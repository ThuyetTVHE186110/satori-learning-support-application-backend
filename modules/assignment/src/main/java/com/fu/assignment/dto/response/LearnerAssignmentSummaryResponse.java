package com.fu.assignment.dto.response;

import com.fu.assignment.entity.AssignmentStatus;
import com.fu.assignment.entity.AssignmentType;
import com.fu.assignment.entity.SubmissionStatus;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Summary assignment response for learners.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LearnerAssignmentSummaryResponse {

    private UUID id;
    private String title;
    private AssignmentType assignmentType;
    private LocalDateTime dueDate;
    private Long questionCount;
    private LocalDateTime createdAt;
    private SubmissionStatus learnerSubmissionStatus;
    private UUID learnerSubmissionId;
}

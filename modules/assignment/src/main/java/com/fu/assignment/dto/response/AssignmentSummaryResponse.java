package com.fu.assignment.dto.response;

import com.fu.assignment.entity.AssignmentStatus;
import com.fu.assignment.entity.AssignmentType;
import com.fu.assignment.entity.SubmissionStatus;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Summary assignment response (list view).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssignmentSummaryResponse {

    private UUID id;
    private String title;
    private AssignmentType assignmentType;
    private AssignmentStatus status;
    private LocalDateTime dueDate;
    private Long questionCount;
    private Long submissionCount;
    private Long ungradedCount;
    private LocalDateTime createdAt;
    private Boolean hasSubmissions;
}

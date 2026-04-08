package com.fu.assignment.dto.response;

import com.fu.assignment.entity.SubmissionStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Summary submission response (list view).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmissionSummaryResponse {

    private UUID id;
    private UUID assignmentId;
    private String assignmentTitle;
    private UUID userId;
    private String userFullName;
    private String userEmail;
    private SubmissionStatus status;
    private Integer attemptNumber;
    private BigDecimal score;
    private LocalDateTime submittedAt;

}

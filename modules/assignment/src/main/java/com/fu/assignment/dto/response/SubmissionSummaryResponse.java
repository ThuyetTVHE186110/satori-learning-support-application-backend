package com.fu.assignment.dto.response;

import com.fu.assignment.entity.SubmissionStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Summary submission response (list view).
 * Includes ALL students in class: those with submissions and those without (NOT_STARTED).
 * Includes both active and removed class members with isRemovedLearner flag.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmissionSummaryResponse {

    private UUID id;
    private String userFullName;
    private SubmissionStatus status;
    private BigDecimal score;
    private LocalDateTime submittedAt;
    private Boolean isRemovedLearner; // true if student was removed from class, false if active

}

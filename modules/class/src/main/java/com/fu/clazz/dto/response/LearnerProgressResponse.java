package com.fu.clazz.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Response DTO for individual learner progress metrics.
 * Contains completion stats and performance data for a single learner in a class.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LearnerProgressResponse {

    /**
     * Learner ID
     */
    private java.util.UUID userId;

    /**
     * Full name (firstName + lastName)
     */
    private String fullName;

    /**
     * Email address
     */
    private String email;

    /**
     * Avatar URL
     */
    private String avatarUrl;

    /**
     * When learner joined the class
     */
    private LocalDateTime joinedAt;

    // ── Completion & Submission Metrics ────────────────────────────────────

    /**
     * Completion rate: (assignments completed with GRADED status / total assignments) * 100
     * Range: 0-100
     */
    private Double completionPercentage;

    /**
     * Submission rate: (assignments submitted / total assignments) * 100
     * Includes SUBMITTED, GRADED, and other non-null submission records
     * Range: 0-100
     */
    private Double submissionPercentage;

    /**
     * Total number of assignments in this class
     */
    private Long totalAssignments;

    /**
     * Number of assignments completed (status = GRADED)
     */
    private Long completedAssignments;

    /**
     * Number of assignments submitted (submitted_at is not null)
     */
    private Long submittedAssignments;

    // ── Performance Metrics ────────────────────────────────────────────────

    /**
     * Average score from graded assignments
     * Scale 0-10, null if no graded submissions exist
     */
    private BigDecimal averageScore;

    // ── Status ─────────────────────────────────────────────────────────────

    /**
     * Learner status in the class.
     * Values: "Đang học" (Learning), "Hoàn thành" (Completed), "Chậm tiến độ" (Behind), "Chưa bắt đầu" (Not started)
     */
    private String status;
}

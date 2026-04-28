package com.fu.clazz.dto.response;

import com.fu.common.response.ApiResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Unified response DTO for class progress & learner performance dashboard.
 * This is the main response returned by GET /api/v1/classes/{classId}/progress
 * 
 * Contains:
 * 1. Class info and overview stats
 * 2. Learning trend historical data
 * 3. Individual learner performance metrics
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassProgressResponse {

    // ── Class Information ──────────────────────────────────────────────────

    /**
     * Class ID
     */
    private UUID classId;

    /**
     * Class name (e.g., "Lớp U2 - Khóa N5")
     */
    private String className;

    /**
     * Course ID
     */
    private UUID courseId;

    /**
     * Course name
     */
    private String courseName;

    /**
     * Course JLPT level (e.g., "N5", "N4")
     */
    private String courseLevel;

    // ── Class Overview Statistics ──────────────────────────────────────────

    /**
     * Aggregated class-level metrics
     */
    private ClassProgressStatsResponse classStats;

    // ── Learning Trend Data ────────────────────────────────────────────────

    /**
     * Historical learning trend data points (optional - may be empty if insufficient data)
     * Ordered chronologically from oldest to newest
     */
    private List<LearningTrendDataPoint> learningTrendData;

    // ── Learner Performance Table ──────────────────────────────────────────

    /**
     * Individual learner progress metrics (paginated if necessary)
     * Ordered by learner name
     */
    private List<LearnerProgressResponse> learnerMetrics;

    /**
     * Total count of learners in the response
     */
    private Integer learnerCount;
}

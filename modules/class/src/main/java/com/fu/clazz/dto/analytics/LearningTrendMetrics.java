package com.fu.clazz.dto.analytics;

import java.time.LocalDate;

/**
 * Projection interface for learning trend data.
 * Maps weekly aggregation from assignment submission data.
 */
public interface LearningTrendMetrics {

    /**
     * Period start date (beginning of the week)
     */
    LocalDate getPeriodStart();

    /**
     * Count of unique learners with submissions in this period
     */
    Long getActiveLearnersCount();

    /**
     * Total count of submissions in this period
     */
    Long getSubmissionsCount();

    /**
     * Average score from submissions in this period (0-10 scale)
     */
    Double getAverageScore();
}

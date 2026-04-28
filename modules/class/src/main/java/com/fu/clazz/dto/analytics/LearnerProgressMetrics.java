package com.fu.clazz.dto.analytics;

import java.util.UUID;

/**
 * Projection interface for learner progress metrics.
 * Queries assignment and submission data without circular dependency.
 */
public interface LearnerProgressMetrics {
    UUID getUserId();
    Long getTotalAssignments();
    Long getCompletedAssignments();
    Long getSubmittedAssignments();
    Double getAvgScore();
}

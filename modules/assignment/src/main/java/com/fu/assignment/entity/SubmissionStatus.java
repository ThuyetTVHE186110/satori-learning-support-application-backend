package com.fu.assignment.entity;

/**
 * Submission status enum.
 * Maps to PostgreSQL enum: submission_status
 */
public enum SubmissionStatus {
    NOT_STARTED,
    IN_PROGRESS,
    SUBMITTED,
    GRADED,
    OVERDUE
}

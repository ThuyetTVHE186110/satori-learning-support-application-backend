package com.fu.assignment.dto.request;

import com.fu.assignment.entity.AssignmentStatus;

import java.time.LocalDateTime;

/**
 * Marker interface for assignment metadata that is common to all assignment types
 * (Quiz, Writing, Translation). Used to share helper methods across service implementations.
 *
 * <p>Implementations: {@link SaveFullQuizRequest}, {@link SaveFullWritingRequest}.
 */
public interface AssignmentMetadataRequest {

    String getTitle();

    String getDescription();

    String getInstructions();

    LocalDateTime getDueDate();

    Integer getTimeLimitMinutes();

    Boolean getLateSubmissionAllowed();

    Integer getMaxScore();

    Integer getPassingScore();

    Boolean getAllowRetry();

    Integer getMaxRetries();

    /**
     * Target status. Returns null when the field is not set (status unchanged).
     */
    AssignmentStatus getStatus();
}

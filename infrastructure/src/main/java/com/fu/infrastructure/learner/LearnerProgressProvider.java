package com.fu.infrastructure.learner;

import java.util.Optional;
import java.util.UUID;

/**
 * Cross-module contract for retrieving a learner's practice progress snapshot.
 * Implemented by the practice module, consumed by the conversation module's
 * chatbot tools to answer progress-related questions.
 */
public interface LearnerProgressProvider {

    /**
     * @param userId   the learner
     * @param courseId optional — scope progress/current-lesson lookup to a specific course.
     *                 If null, looks across all courses for the learner.
     * @return snapshot if the learner has any practice history; empty otherwise.
     */
    Optional<LearnerProgressSnapshot> getProgress(UUID userId, UUID courseId);
}

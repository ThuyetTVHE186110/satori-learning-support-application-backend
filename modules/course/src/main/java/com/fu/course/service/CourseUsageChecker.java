package com.fu.course.service;

import java.util.UUID;

/**
 * Interface to check for course usage across other modules.
 * This avoids circular dependencies between course and other modules (like class).
 */
public interface CourseUsageChecker {
    /**
     * Check if the course has active usage.
     * Implementations should throw a BusinessException if active usage is found.
     * 
     * @param courseId the ID of the course
     * @throws com.fu.common.exception.BusinessException if active usage is found
     */
    boolean checkActiveUsage(UUID courseId);
}

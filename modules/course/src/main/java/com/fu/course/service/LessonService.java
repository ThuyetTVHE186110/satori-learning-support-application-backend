package com.fu.course.service;

import com.fu.course.dto.request.CreateLessonRequest;
import com.fu.course.dto.request.UpdateLessonRequest;
import com.fu.course.dto.response.LessonResponse;
import com.fu.course.dto.response.LessonSummaryResponse;
import com.fu.course.entity.Lesson.ContentStatus;
import com.fu.course.entity.Lesson.LessonType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

/**
 * Service interface for Lesson management.
 */
public interface LessonService {

    // ===== CRUD =====

    /**
     * Create a new lesson within a course (MANAGER only).
     */
    LessonResponse createLesson(CreateLessonRequest request, UUID managerId);

    /**
     * Update an existing lesson (MANAGER only).
     */
    LessonResponse updateLesson(UUID lessonId, UpdateLessonRequest request, UUID managerId);

    /**
     * Get lesson by ID (full detail).
     */
    LessonResponse getLessonById(UUID lessonId);

    /**
     * Soft-delete a lesson (MANAGER only).
     */
    void deleteLesson(UUID lessonId, UUID managerId);

    // ===== Query =====

    /**
     * Get all lessons for a course (manager view, includes all statuses).
     */
    List<LessonSummaryResponse> getLessonsByCourseId(UUID courseId);

    /**
     * Get all lessons for a course with pagination.
     */
    Page<LessonSummaryResponse> getLessonsByCourseId(UUID courseId, ContentStatus status, Pageable pageable);

    /**
     * Get published lessons for a course (student view).
     */
    List<LessonSummaryResponse> getPublishedLessonsByCourseId(UUID courseId);

    /**
     * Search lessons by title keyword.
     */
    Page<LessonSummaryResponse> searchLessons(String keyword, Pageable pageable);

    /**
     * Search lessons within a specific course.
     */
    Page<LessonSummaryResponse> searchLessonsInCourse(UUID courseId, String keyword, ContentStatus status, Pageable pageable);

    // ===== Status Management =====

    /**
     * Publish a lesson (DRAFT → PUBLISHED).
     */
    LessonResponse publishLesson(UUID lessonId, UUID managerId);

    /**
     * Archive a lesson (any → ARCHIVED).
     */
    LessonResponse archiveLesson(UUID lessonId, UUID managerId);

    // ===== Counting =====

    /**
     * Count lessons in a course (non-deleted).
     */
    long countLessonsByCourseId(UUID courseId);

    // ===== Statistics =====

    /**
     * Lesson statistics for a course.
     */
    LessonStatistics getLessonStatistics(UUID courseId);

    record LessonStatistics(
        long totalLessons,
        long publishedLessons,
        long draftLessons,
        long archivedLessons
    ) {}
}

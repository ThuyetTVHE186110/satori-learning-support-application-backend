package com.fu.course.service;

import com.fu.course.dto.request.CreateCourseRequest;
import com.fu.course.dto.request.UpdateCourseRequest;
import com.fu.course.dto.response.CourseResponse;
import com.fu.course.dto.response.CourseSummaryResponse;
import com.fu.course.entity.Course.ContentStatus;
import com.fu.course.entity.Course.JlptLevel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Service interface for Course management
 */
public interface CourseService {

    /**
     * Create a new course (MANAGER only)
     */
    CourseResponse createCourse(CreateCourseRequest request, UUID managerId);

    /**
     * Create a new course with optional thumbnail upload (MANAGER only)
     */
    CourseResponse createCourse(CreateCourseRequest request, MultipartFile thumbnail, UUID managerId);

    /**
     * Update course (MANAGER only)
     */
    CourseResponse updateCourse(UUID courseId, UpdateCourseRequest request, MultipartFile thumbnail, UUID managerId);

    /**
     * Get course by ID
     */
    CourseResponse getCourseById(UUID courseId);

    /**
     * Get all courses (with pagination)
     */
    Page<CourseSummaryResponse> getAllCourses(Pageable pageable);

    /**
     * Get courses by status
     */
    Page<CourseSummaryResponse> getCoursesByStatus(ContentStatus status, Pageable pageable);

    /**
     * Get courses by JLPT level
     */
    Page<CourseSummaryResponse> getCoursesByJlptLevel(JlptLevel jlptLevel, Pageable pageable);

    /**
     * Get published courses only (for students)
     */
    List<CourseSummaryResponse> getPublishedCourses();

    /**
     * Get published courses by JLPT level (for students)
     */
    List<CourseSummaryResponse> getPublishedCoursesByLevel(JlptLevel jlptLevel);

    /**
     * Search courses by name
     */
    Page<CourseSummaryResponse> searchCourses(String keyword, Pageable pageable);

    /**
     * Publish course (change status to PUBLISHED)
     */
    CourseResponse publishCourse(UUID courseId, UUID managerId);

    /**
     * Archive course (change status to ARCHIVED)
     */
    CourseResponse archiveCourse(UUID courseId, UUID managerId);

    /**
     * Soft delete course (MANAGER only)
     */
    void deleteCourse(UUID courseId, UUID managerId);

    /**
     * Check if course exists by name
     */
    boolean existsByName(String name);

    /**
     * Update course thumbnail URL (MANAGER only)
     */
    CourseResponse updateThumbnail(UUID courseId, String thumbnailUrl, UUID managerId);

    /**
     * Get course statistics
     */
    CourseStatistics getCourseStatistics();

    /**
     * Inner class for statistics
     */
    record CourseStatistics(
        long totalCourses,
        long publishedCourses,
        long draftCourses,
        long archivedCourses,
        long coursesByN5,
        long coursesByN4,
        long coursesByN3,
        long coursesByN2,
        long coursesByN1
    ) {}
}

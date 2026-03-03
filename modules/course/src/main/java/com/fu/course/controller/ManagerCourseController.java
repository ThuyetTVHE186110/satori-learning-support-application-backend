package com.fu.course.controller;

import com.fu.common.response.ApiResponse;
import com.fu.common.response.PageResponse;
import com.fu.course.dto.request.CreateCourseRequest;
import com.fu.course.dto.request.UpdateCourseRequest;
import com.fu.course.dto.response.CourseResponse;
import com.fu.course.dto.response.CourseSummaryResponse;
import com.fu.course.entity.Course.CourseStatus;
import com.fu.course.entity.Course.JlptLevel;
import com.fu.course.service.CourseService;
import com.fu.course.service.CourseService.CourseStatistics;
import com.fu.infrastructure.cloudinary.CloudinaryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * REST Controller for Manager Course CRUD operations.
 *
 * All endpoints require ROLE_MANAGER.
 * Base path: /api/v1/manager/courses
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/manager/courses")
@RequiredArgsConstructor
@PreAuthorize("hasRole('MANAGER')")
public class ManagerCourseController {

    private final CourseService courseService;
    private final CloudinaryService cloudinaryService;

    /**
     * Create a new course with optional thumbnail upload.
     * POST /api/v1/manager/courses
     * Content-Type: multipart/form-data
     *   - "request" part: JSON course data
     *   - "thumbnail" part: image file (optional)
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<CourseResponse>> createCourse(
            @Valid @RequestPart("request") CreateCourseRequest request,
            @RequestPart(value = "thumbnail", required = false) MultipartFile thumbnail,
            @AuthenticationPrincipal UUID managerId) {

        log.info("Manager {} creating course: {}", managerId, request.getName());
        CourseResponse response = courseService.createCourse(request, thumbnail, managerId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Course created successfully", response));
    }

    /**
     * Update an existing course with optional thumbnail upload.
     * PUT /api/v1/manager/courses/{courseId}
     * Content-Type: multipart/form-data
     */
    @PutMapping(value = "/{courseId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<CourseResponse>> updateCourse(
            @PathVariable UUID courseId,
            @Valid @RequestPart("request") UpdateCourseRequest request,
            @RequestPart(value = "thumbnail", required = false) MultipartFile thumbnail,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        log.info("Manager {} updating course: {}", managerId, courseId);
        CourseResponse response = courseService.updateCourse(courseId, request, thumbnail, managerId);
        return ResponseEntity.ok(ApiResponse.success("Course updated successfully", response));
    }

    /**
     * Get course by ID (including non-published).
     * GET /api/v1/manager/courses/{courseId}
     */
    @GetMapping("/{courseId}")
    public ResponseEntity<ApiResponse<CourseResponse>> getCourseById(
            @PathVariable UUID courseId) {

        CourseResponse response = courseService.getCourseById(courseId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Get all courses with pagination and optional filters.
     * GET /api/v1/manager/courses?page=0&size=10&status=INACTIVE&level=N5&keyword=xxx
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<CourseSummaryResponse>>> getAllCourses(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) CourseStatus status,
            @RequestParam(required = false) JlptLevel level,
            @RequestParam(required = false) String keyword) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 100), Sort.by("orderIndex").ascending());

        Page<CourseSummaryResponse> coursePage;
        if (keyword != null && !keyword.isBlank()) {
            coursePage = courseService.searchCourses(keyword.trim(), pageable);
        } else if (status != null) {
            coursePage = courseService.getCoursesByStatus(status, pageable);
        } else if (level != null) {
            coursePage = courseService.getCoursesByJlptLevel(level, pageable);
        } else {
            coursePage = courseService.getAllCourses(pageable);
        }

        PageResponse<CourseSummaryResponse> pageResponse = PageResponse.of(
                coursePage.getContent(),
                coursePage.getNumber(),
                coursePage.getSize(),
                coursePage.getTotalElements(),
                coursePage.getTotalPages()
        );
        return ResponseEntity.ok(ApiResponse.success(pageResponse));
    }

    /**
     * Activate a course (INACTIVE -> ACTIVE).
     * PATCH /api/v1/manager/courses/{courseId}/activate
     */
    @PatchMapping("/{courseId}/activate")
    public ResponseEntity<ApiResponse<CourseResponse>> activateCourse(
            @PathVariable UUID courseId,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        log.info("Manager {} activating course: {}", managerId, courseId);
        CourseResponse response = courseService.activateCourse(courseId, managerId);
        return ResponseEntity.ok(ApiResponse.success("Course activated successfully", response));
    }

    /**
     * Deactivate a course (ACTIVE -> INACTIVE).
     * PATCH /api/v1/manager/courses/{courseId}/deactivate
     */
    @PatchMapping("/{courseId}/deactivate")
    public ResponseEntity<ApiResponse<CourseResponse>> deactivateCourse(
            @PathVariable UUID courseId,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        log.info("Manager {} deactivating course: {}", managerId, courseId);
        CourseResponse response = courseService.deactivateCourse(courseId, managerId);
        return ResponseEntity.ok(ApiResponse.success("Course deactivated successfully", response));
    }

    /**
     * Upload or update course thumbnail.
     * POST /api/v1/manager/courses/{courseId}/thumbnail
     */
    @PostMapping("/{courseId}/thumbnail")
    public ResponseEntity<ApiResponse<CourseResponse>> uploadThumbnail(
            @PathVariable UUID courseId,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal UUID managerId) {

        log.info("Manager {} uploading thumbnail for course: {}", managerId, courseId);

        // Upload image to Cloudinary
        String thumbnailUrl = cloudinaryService.uploadImage(file, "courses/thumbnails");

        // Update course with new thumbnail URL
        CourseResponse response = courseService.updateThumbnail(courseId, thumbnailUrl, managerId);
        return ResponseEntity.ok(ApiResponse.success("Thumbnail uploaded successfully", response));
    }

    /**
     * Soft-delete a course.
     * DELETE /api/v1/manager/courses/{courseId}
     */
    @DeleteMapping("/{courseId}")
    public ResponseEntity<ApiResponse<Void>> deleteCourse(
            @PathVariable UUID courseId,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        log.info("Manager {} deleting course: {}", managerId, courseId);
        courseService.deleteCourse(courseId, managerId);
        return ResponseEntity.ok(ApiResponse.success("Course deleted successfully", null));
    }

    /**
     * Get course statistics for dashboard.
     * GET /api/v1/manager/courses/statistics
     */
    @GetMapping("/statistics")
    public ResponseEntity<ApiResponse<CourseStatistics>> getCourseStatistics() {
        CourseStatistics statistics = courseService.getCourseStatistics();
        return ResponseEntity.ok(ApiResponse.success(statistics));
    }
}

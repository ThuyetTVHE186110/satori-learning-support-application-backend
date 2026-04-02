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
 * <p>
 * All endpoints require ROLE_MANAGER.
 * Base path: /api/v1/manager/courses
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/manager/courses")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CONTENT_MANAGER')")
public class ManagerCourseController {

    private final CourseService courseService;
    private final CloudinaryService cloudinaryService;

    /**
     * Create a new course
     * POST /api/v1/manager/courses
     * Content-Type: multipart/form-data
     * - "request" part: JSON course data
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<CourseResponse>> createCourse(
            @Valid @RequestBody CreateCourseRequest request,
            @AuthenticationPrincipal UUID managerId) {

        log.info("Manager {} creating course: {}", managerId, request.getName());
        CourseResponse response = courseService.createCourse(request, managerId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Tạo khóa học thành công.", response));
    }

    /**
     * Update an existing course
     * PUT /api/v1/manager/courses/{courseId}
     * Content-Type: multipart/form-data
     */
    @PutMapping(value = "/{courseId}")
    public ResponseEntity<ApiResponse<CourseResponse>> updateCourse(
            @PathVariable UUID courseId,
            @Valid @RequestBody UpdateCourseRequest request,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        log.info("Manager {} updating course: {}", managerId, courseId);
        CourseResponse response = courseService.updateCourse(courseId, request, managerId);
        return ResponseEntity.ok(ApiResponse.success("Cập nhật khóa học thành công!", response));
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
     * Supports combining multiple filter criteria simultaneously.
     * Always excludes courses with DELETED status.
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

        Page<CourseSummaryResponse> coursePage = courseService.filterCourses(status, level, keyword, pageable);

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
        return ResponseEntity.ok(ApiResponse.success("Kích hoạt khóa học thành công", response));
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
        return ResponseEntity.ok(ApiResponse.success("Ngưng kích hoạt khóa học thành công", response));
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
        return ResponseEntity.ok(ApiResponse.success("Xóa khóa học thành công.", null));
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

package com.fu.course.controller;

import com.fu.common.response.ApiResponse;
import com.fu.common.response.PageResponse;
import com.fu.course.dto.request.CreateLessonRequest;
import com.fu.course.dto.request.UpdateLessonRequest;
import com.fu.course.dto.response.LessonResponse;
import com.fu.course.dto.response.LessonSummaryResponse;
import com.fu.course.service.LessonService;
import com.fu.course.service.LessonService.LessonStatistics;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST Controller for Manager Lesson CRUD operations.
 *
 * All endpoints require ROLE_MANAGER.
 * Base path: /api/v1/manager/lessons
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/manager/lessons")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CONTENT_MANAGER')")
public class ManagerLessonController {

    private final LessonService lessonService;

    /**
     * Create a new lesson.
     * POST /api/v1/manager/lessons
     */
    @PostMapping
    public ResponseEntity<ApiResponse<LessonResponse>> createLesson(
            @Valid @RequestBody CreateLessonRequest request,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        log.info("Manager {} creating lesson: {}", managerId, request.getTitle());
        LessonResponse response = lessonService.createLesson(request, managerId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Lesson created successfully", response));
    }

    /**
     * Update an existing lesson.
     * PUT /api/v1/manager/lessons/{lessonId}
     */
    @PutMapping("/{lessonId}")
    public ResponseEntity<ApiResponse<LessonResponse>> updateLesson(
            @PathVariable UUID lessonId,
            @Valid @RequestBody UpdateLessonRequest request,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        log.info("Manager {} updating lesson: {}", managerId, lessonId);
        LessonResponse response = lessonService.updateLesson(lessonId, request, managerId);
        return ResponseEntity.ok(ApiResponse.success("Lesson updated successfully", response));
    }

    /**
     * Get lesson by ID (full detail, including non-published).
     * GET /api/v1/manager/lessons/{lessonId}
     */
    @GetMapping("/{lessonId}")
    public ResponseEntity<ApiResponse<LessonResponse>> getLessonById(
            @PathVariable UUID lessonId) {

        LessonResponse response = lessonService.getLessonById(lessonId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Get all lessons for a course with pagination.
     * GET /api/v1/manager/lessons?courseId=xxx&page=0&size=10&keyword=xxx
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<LessonSummaryResponse>>> getLessons(
            @RequestParam UUID courseId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 100), Sort.by("orderIndex").ascending());

        Page<LessonSummaryResponse> lessonPage;
        if (keyword != null && !keyword.isBlank()) {
            lessonPage = lessonService.searchLessonsInCourse(courseId, keyword.trim(), pageable);
        } else {
            lessonPage = lessonService.getLessonsByCourseId(courseId, pageable);
        }

        PageResponse<LessonSummaryResponse> pageResponse = PageResponse.of(
                lessonPage.getContent(),
                lessonPage.getNumber(),
                lessonPage.getSize(),
                lessonPage.getTotalElements(),
                lessonPage.getTotalPages()
        );
        return ResponseEntity.ok(ApiResponse.success(pageResponse));
    }

    /**
     * Publish a lesson (DRAFT → PUBLISHED).
     * PATCH /api/v1/manager/lessons/{lessonId}/publish
     */
    @PatchMapping("/{lessonId}/publish")
    public ResponseEntity<ApiResponse<LessonResponse>> publishLesson(
            @PathVariable UUID lessonId,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        log.info("Manager {} publishing lesson: {}", managerId, lessonId);
        LessonResponse response = lessonService.publishLesson(lessonId, managerId);
        return ResponseEntity.ok(ApiResponse.success("Lesson published successfully", response));
    }

    /**
     * Archive a lesson (any → ARCHIVED).
     * PATCH /api/v1/manager/lessons/{lessonId}/archive
     */
    @PatchMapping("/{lessonId}/archive")
    public ResponseEntity<ApiResponse<LessonResponse>> archiveLesson(
            @PathVariable UUID lessonId,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        log.info("Manager {} archiving lesson: {}", managerId, lessonId);
        LessonResponse response = lessonService.archiveLesson(lessonId, managerId);
        return ResponseEntity.ok(ApiResponse.success("Lesson archived successfully", response));
    }

    /**
     * Soft-delete a lesson.
     * DELETE /api/v1/manager/lessons/{lessonId}
     */
    @DeleteMapping("/{lessonId}")
    public ResponseEntity<ApiResponse<Void>> deleteLesson(
            @PathVariable UUID lessonId,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        log.info("Manager {} deleting lesson: {}", managerId, lessonId);
        lessonService.deleteLesson(lessonId, managerId);
        return ResponseEntity.ok(ApiResponse.success("Lesson deleted successfully", null));
    }

    /**
     * Get lesson statistics for a course.
     * GET /api/v1/manager/lessons/statistics?courseId=xxx
     */
    @GetMapping("/statistics")
    public ResponseEntity<ApiResponse<LessonStatistics>> getLessonStatistics(
            @RequestParam UUID courseId) {

        LessonStatistics statistics = lessonService.getLessonStatistics(courseId);
        return ResponseEntity.ok(ApiResponse.success(statistics));
    }
}

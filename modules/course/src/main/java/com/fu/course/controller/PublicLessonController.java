package com.fu.course.controller;

import com.fu.common.response.ApiResponse;
import com.fu.course.dto.response.LessonResponse;
import com.fu.course.dto.response.LessonSummaryResponse;
import com.fu.course.service.LessonService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST Controller for Student/Public Lesson access.
 * Only shows PUBLISHED lessons.
 *
 * Base path: /api/v1/courses/{courseId}/lessons
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/courses/{courseId}/lessons")
@RequiredArgsConstructor
public class PublicLessonController {

    private final LessonService lessonService;

    /**
     * Get all published lessons for a course (ordered by order_index).
     *
     * GET /api/v1/courses/{courseId}/lessons
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<LessonSummaryResponse>>> getPublishedLessons(
            @PathVariable UUID courseId) {

        log.debug("Fetching published lessons for course: {}", courseId);
        List<LessonSummaryResponse> lessons = lessonService.getPublishedLessonsByCourseId(courseId);
        return ResponseEntity.ok(ApiResponse.success(lessons));
    }

    /**
     * Get a single published lesson detail.
     *
     * GET /api/v1/courses/{courseId}/lessons/{lessonId}
     */
    @GetMapping("/{lessonId}")
    public ResponseEntity<ApiResponse<LessonResponse>> getLessonDetail(
            @PathVariable UUID courseId,
            @PathVariable UUID lessonId) {

        log.debug("Fetching lesson {} for course {}", lessonId, courseId);
        LessonResponse response = lessonService.getLessonById(lessonId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}

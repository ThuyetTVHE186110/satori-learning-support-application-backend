package com.fu.course.controller;

import com.fu.common.response.ApiResponse;
import com.fu.course.dto.response.CourseSummaryResponse;
import com.fu.course.entity.Course.JlptLevel;
import com.fu.course.service.CourseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for Student/Public Course access
 * 
 * Endpoints:
 * - GET /api/v1/courses              - Get all published courses
 * - GET /api/v1/courses/level/{level} - Get published courses by JLPT level
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/courses")
@RequiredArgsConstructor
public class PublicCourseController {

    private final CourseService courseService;

    /**
     * Get all published courses (for students/public)
     * 
     * GET /api/v1/courses
     * 
     * @return List of published courses
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<CourseSummaryResponse>>> getPublishedCourses() {
        log.debug("Fetching all published courses");
        
        List<CourseSummaryResponse> courses = courseService.getPublishedCourses();
        return ResponseEntity.ok(ApiResponse.success(courses));
    }

    /**
     * Get published courses by JLPT level
     * 
     * GET /api/v1/courses/level/{level}
     * 
     * @param level JLPT level (N5, N4, N3, N2, N1)
     * @return List of published courses for that level
     */
    @GetMapping("/level/{level}")
    public ResponseEntity<ApiResponse<List<CourseSummaryResponse>>> getPublishedCoursesByLevel(
            @PathVariable JlptLevel level) {
        
        log.debug("Fetching published courses for level: {}", level);
        
        List<CourseSummaryResponse> courses = courseService.getPublishedCoursesByLevel(level);
        return ResponseEntity.ok(ApiResponse.success(courses));
    }
}

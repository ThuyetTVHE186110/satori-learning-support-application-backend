package com.fu.course.controller;

import com.fu.common.response.ApiResponse;
import com.fu.common.response.PageResponse;
import com.fu.course.dto.request.CreateGrammarPointRequest;
import com.fu.course.dto.request.UpdateGrammarPointRequest;
import com.fu.course.dto.response.GrammarPointResponse;
import com.fu.course.entity.Course.JlptLevel;
import com.fu.course.entity.GrammarPoint.GrammarCategory;
import com.fu.course.service.GrammarPointService;
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
 * REST Controller for GrammarPoint CRUD operations.
 *
 * All write endpoints require ROLE_MANAGER.
 * Base path: /api/v1/manager/grammar-points
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/manager/grammar-points")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CONTENT_MANAGER')")
public class ManagerGrammarController {

    private final GrammarPointService grammarPointService;

    /**
     * Create a new grammar point.
     * POST /api/v1/manager/grammar-points
     */
    @PostMapping
    public ResponseEntity<ApiResponse<GrammarPointResponse>> createGrammarPoint(
            @Valid @RequestBody CreateGrammarPointRequest request,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        log.info("Manager {} creating grammar point: {}", managerId, request.getPattern());
        GrammarPointResponse response = grammarPointService.createGrammarPoint(request, managerId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Grammar point created successfully", response));
    }

    /**
     * Update an existing grammar point.
     * PUT /api/v1/manager/grammar-points/{grammarPointId}
     */
    @PutMapping("/{grammarPointId}")
    public ResponseEntity<ApiResponse<GrammarPointResponse>> updateGrammarPoint(
            @PathVariable UUID grammarPointId,
            @Valid @RequestBody UpdateGrammarPointRequest request,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        log.info("Manager {} updating grammar point: {}", managerId, grammarPointId);
        GrammarPointResponse response = grammarPointService.updateGrammarPoint(grammarPointId, request, managerId);
        return ResponseEntity.ok(ApiResponse.success("Grammar point updated successfully", response));
    }

    /**
     * Get grammar point by ID.
     * GET /api/v1/manager/grammar-points/{grammarPointId}
     */
    @GetMapping("/{grammarPointId}")
    public ResponseEntity<ApiResponse<GrammarPointResponse>> getGrammarPointById(
            @PathVariable UUID grammarPointId) {

        GrammarPointResponse response = grammarPointService.getGrammarPointById(grammarPointId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Get all grammar points with optional filters and keyword search.
     * GET /api/v1/manager/grammar-points?level=N5&category=PARTICLE&keyword=...&page=0&size=20
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<GrammarPointResponse>>> getAllGrammarPoints(
            @RequestParam(required = false) JlptLevel level,
            @RequestParam(required = false) GrammarCategory category,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 100),
                Sort.by("jlptLevel").ascending().and(Sort.by("pattern").ascending()));

        Page<GrammarPointResponse> grammarPage;
        if (keyword != null && !keyword.isBlank()) {
            grammarPage = grammarPointService.searchGrammarPoints(keyword.trim(), pageable);
        } else {
            grammarPage = grammarPointService.getAllGrammarPoints(level, category, pageable);
        }

        PageResponse<GrammarPointResponse> pageResponse = PageResponse.of(
                grammarPage.getContent(),
                grammarPage.getNumber(),
                grammarPage.getSize(),
                grammarPage.getTotalElements(),
                grammarPage.getTotalPages()
        );
        return ResponseEntity.ok(ApiResponse.success(pageResponse));
    }

    /**
     * Get all grammar points for a specific lesson.
     * GET /api/v1/manager/grammar-points/by-lesson/{lessonId}
     */
    @GetMapping("/by-lesson/{lessonId}")
    public ResponseEntity<ApiResponse<List<GrammarPointResponse>>> getGrammarPointsByLesson(
            @PathVariable UUID lessonId) {

        List<GrammarPointResponse> grammarPoints = grammarPointService.getGrammarPointsByLesson(lessonId);
        return ResponseEntity.ok(ApiResponse.success(grammarPoints));
    }

    /**
     * Soft-delete a grammar point.
     * DELETE /api/v1/manager/grammar-points/{grammarPointId}
     */
    @DeleteMapping("/{grammarPointId}")
    public ResponseEntity<ApiResponse<Void>> deleteGrammarPoint(
            @PathVariable UUID grammarPointId,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        log.info("Manager {} deleting grammar point: {}", managerId, grammarPointId);
        grammarPointService.deleteGrammarPoint(grammarPointId, managerId);
        return ResponseEntity.ok(ApiResponse.success("Grammar point deleted successfully", null));
    }
}

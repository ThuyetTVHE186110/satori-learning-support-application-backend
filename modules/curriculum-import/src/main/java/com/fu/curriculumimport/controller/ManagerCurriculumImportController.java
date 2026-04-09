package com.fu.curriculumimport.controller;

import com.fu.common.response.ApiResponse;
import com.fu.common.response.PageResponse;
import com.fu.curriculumimport.dto.response.*;
import com.fu.curriculumimport.entity.CurriculumImport.ImportStatus;
import com.fu.curriculumimport.service.CurriculumImportService;
import com.fu.curriculumimport.service.CurriculumImportService.LessonSummaryDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * REST Controller for Curriculum Import operations.
 * <p>
 * All endpoints require ROLE_MANAGER.
 * Base path: /api/v1/manager/curriculum-imports
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/manager/curriculum-imports")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CONTENT_MANAGER')")
public class ManagerCurriculumImportController {

    private final CurriculumImportService importService;

    /**
     * Upload a PDF and initiate curriculum import.
     * POST /api/v1/manager/curriculum-imports
     *
     * @param file     the PDF file to process
     * @param courseId optional course ID to link the import to
     * @return 202 Accepted with import status
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<CurriculumImportResponse>> uploadAndImport(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "courseId") UUID courseId,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        log.info("Manager {} uploading curriculum file: {}, courseId: {}",
                managerId, file.getOriginalFilename(), courseId);

        CurriculumImportResponse response = importService.initiateImport(file, courseId, managerId);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success("Import accepted and queued for processing", response));
    }

    /**
     * Get import status by ID.
     * GET /api/v1/manager/curriculum-imports/{importId}
     */
    @GetMapping("/{importId}")
    public ResponseEntity<ApiResponse<CurriculumImportResponse>> getImportStatus(
            @PathVariable UUID importId) {

        CurriculumImportResponse response = importService.getImportById(importId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * List all imports with pagination and optional filters.
     * GET /api/v1/manager/curriculum-imports?courseId=...&status=...&startDate=...&endDate=...&page=0&size=10
     *
     * @param courseId optional - filter by course
     * @param status optional - filter by import status
     * @param startDate optional - filter by creation date (inclusive), format: ISO 8601 (e.g., 2024-01-15T10:30:00)
     * @param endDate optional - filter by creation date (inclusive), format: ISO 8601 (e.g., 2024-01-15T23:59:59)
     * @param page page number (0-indexed, default: 0)
     * @param size page size (default: 10, max: 100)
     * @return paginated list of imports ordered by creation date (newest first)
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<CurriculumImportSummaryResponse>>> listImports(
            @RequestParam(required = false) UUID courseId,
            @RequestParam(required = false) ImportStatus status,
            @RequestParam(required = false) LocalDateTime startDate,
            @RequestParam(required = false) LocalDateTime endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        PageResponse<CurriculumImportSummaryResponse> response =
                importService.listImports(courseId, status, startDate, endDate, page, size);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Get chunks for a specific import.
     * GET /api/v1/manager/curriculum-imports/{importId}/chunks
     */
    @GetMapping("/{importId}/chunks")
    public ResponseEntity<ApiResponse<PageResponse<ImportChunkResponse>>> getImportChunks(
            @PathVariable UUID importId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageResponse<ImportChunkResponse> response = importService.getImportChunks(importId, page, size);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Retry a failed import.
     * POST /api/v1/manager/curriculum-imports/{importId}/retry
     */
    @PostMapping("/{importId}/retry")
    public ResponseEntity<ApiResponse<CurriculumImportResponse>> retryImport(
            @PathVariable UUID importId) {

        log.info("Retrying import: {}", importId);
        CurriculumImportResponse response = importService.retryImport(importId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success("Import queued for retry", response));
    }

    /**
     * Cancel an in-progress or queued import.
     * POST /api/v1/manager/curriculum-imports/{importId}/cancel
     */
    @PostMapping("/{importId}/cancel")
    public ResponseEntity<ApiResponse<CurriculumImportResponse>> cancelImport(
            @PathVariable UUID importId) {

        log.info("Cancelling import: {}", importId);
        CurriculumImportResponse response = importService.cancelImport(importId);
        return ResponseEntity.ok(ApiResponse.success("Import cancelled successfully", response));
    }

    /**
     * Approve a PENDING_APPROVAL import.
     * Transitions import status to COMPLETED; draft content remains in DB.
     * POST /api/v1/manager/curriculum-imports/{importId}/approve
     */
    @PostMapping("/{importId}/approve")
    public ResponseEntity<ApiResponse<CurriculumImportResponse>> approveImport(
            @PathVariable UUID importId,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        log.info("Manager {} approving import: {}", managerId, importId);
        CurriculumImportResponse response = importService.approveImport(importId, managerId);
        return ResponseEntity.ok(ApiResponse.success("Import approved successfully", response));
    }

    /**
     * Reject a PENDING_APPROVAL import.
     * Soft-deletes all lessons from this import and transitions status to CANCELLED.
     * POST /api/v1/manager/curriculum-imports/{importId}/reject
     */
    @PostMapping("/{importId}/reject")
    public ResponseEntity<ApiResponse<CurriculumImportResponse>> rejectImport(
            @PathVariable UUID importId,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        log.info("Manager {} rejecting import: {}", managerId, importId);
        CurriculumImportResponse response = importService.rejectImport(importId, managerId);
        return ResponseEntity.ok(ApiResponse.success("Import rejected and lessons removed", response));
    }

    /**
     * Rollback a COMPLETED import.
     * Soft-deletes lessons, purges embeddings, and transitions status to ROLLED_BACK.
     * Vocabulary and grammar points are retained as shared resources.
     * POST /api/v1/manager/curriculum-imports/{importId}/rollback
     */
    @PostMapping("/{importId}/rollback")
    public ResponseEntity<ApiResponse<CurriculumImportResponse>> rollbackImport(
            @PathVariable UUID importId,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        log.info("Manager {} rolling back import: {}", managerId, importId);
        CurriculumImportResponse response = importService.rollbackImport(importId, managerId);
        return ResponseEntity.ok(ApiResponse.success("Import rolled back successfully", response));
    }

    /**
     * Link a completed import to a course (or reassign to a different course).
     * Useful when an import was initiated without a courseId.
     * PATCH /api/v1/manager/curriculum-imports/{importId}/link-course?courseId=...
     */
    @PatchMapping("/{importId}/link-course")
    public ResponseEntity<ApiResponse<CurriculumImportResponse>> linkImportToCourse(
            @PathVariable UUID importId,
            @RequestParam UUID courseId,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        log.info("Manager {} linking import {} to course {}", managerId, importId, courseId);
        CurriculumImportResponse response = importService.linkToCourse(importId, courseId, managerId);
        return ResponseEntity.ok(ApiResponse.success("Import linked to course successfully", response));
    }

    /**
     * Get lessons created by a specific import.
     * GET /api/v1/manager/curriculum-imports/{importId}/lessons
     */
    @GetMapping("/{importId}/lessons")
    public ResponseEntity<ApiResponse<List<LessonSummaryDto>>> getImportLessons(
            @PathVariable UUID importId) {

        List<LessonSummaryDto> lessons = importService.getImportLessons(importId);
        return ResponseEntity.ok(ApiResponse.success(lessons));
    }

    /**
     * Get vocabulary extracted by a specific import.
     * GET /api/v1/manager/curriculum-imports/{importId}/vocabulary
     */
    @GetMapping("/{importId}/vocabulary")
    public ResponseEntity<ApiResponse<PageResponse<VocabularyResponse>>> getImportVocabulary(
            @PathVariable UUID importId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageResponse<VocabularyResponse> response = importService.getImportVocabulary(importId, page, size);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Get grammar points extracted by a specific import.
     * GET /api/v1/manager/curriculum-imports/{importId}/grammar-points
     */
    @GetMapping("/{importId}/grammar-points")
    public ResponseEntity<ApiResponse<PageResponse<GrammarPointResponse>>> getImportGrammarPoints(
            @PathVariable UUID importId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageResponse<GrammarPointResponse> response = importService.getImportGrammarPoints(importId, page, size);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Get import statistics for a specific course.
     * GET /api/v1/manager/curriculum-imports/statistics?courseId=...
     *
     * @param courseId required - course ID to get statistics for
     * @return statistics summary for the specified course
     */
    @GetMapping("/statistics")
    public ResponseEntity<ApiResponse<ImportStatisticsResponse>> getStatistics(
            @RequestParam UUID courseId) {
        log.debug("Fetching statistics for courseId: {}", courseId);
        ImportStatisticsResponse statistics = importService.getStatistics(courseId);
        return ResponseEntity.ok(ApiResponse.success(statistics));
    }
}

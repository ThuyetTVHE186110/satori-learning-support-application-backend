package com.fu.course.controller;

import com.fu.common.response.ApiResponse;
import com.fu.common.response.PageResponse;
import com.fu.course.dto.request.CreateVocabularyRequest;
import com.fu.course.dto.request.UpdateVocabularyRequest;
import com.fu.course.dto.response.VocabularyResponse;
import com.fu.course.entity.Course.JlptLevel;
import com.fu.course.service.VocabularyService;
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
 * REST Controller for Vocabulary CRUD operations.
 *
 * All write endpoints require ROLE_MANAGER.
 * Base path: /api/v1/manager/vocabulary
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/manager/vocabulary")
@RequiredArgsConstructor
@PreAuthorize("hasRole('MANAGER')")
public class ManagerVocabularyController {

    private final VocabularyService vocabularyService;

    /**
     * Create a new vocabulary entry.
     * POST /api/v1/manager/vocabulary
     */
    @PostMapping
    public ResponseEntity<ApiResponse<VocabularyResponse>> createVocabulary(
            @Valid @RequestBody CreateVocabularyRequest request,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        log.info("Manager {} creating vocabulary: {}", managerId, request.getJapaneseText());
        VocabularyResponse response = vocabularyService.createVocabulary(request, managerId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Vocabulary created successfully", response));
    }

    /**
     * Update an existing vocabulary entry.
     * PUT /api/v1/manager/vocabulary/{vocabularyId}
     */
    @PutMapping("/{vocabularyId}")
    public ResponseEntity<ApiResponse<VocabularyResponse>> updateVocabulary(
            @PathVariable UUID vocabularyId,
            @Valid @RequestBody UpdateVocabularyRequest request,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        log.info("Manager {} updating vocabulary: {}", managerId, vocabularyId);
        VocabularyResponse response = vocabularyService.updateVocabulary(vocabularyId, request, managerId);
        return ResponseEntity.ok(ApiResponse.success("Vocabulary updated successfully", response));
    }

    /**
     * Get vocabulary by ID.
     * GET /api/v1/manager/vocabulary/{vocabularyId}
     */
    @GetMapping("/{vocabularyId}")
    public ResponseEntity<ApiResponse<VocabularyResponse>> getVocabularyById(
            @PathVariable UUID vocabularyId) {

        VocabularyResponse response = vocabularyService.getVocabularyById(vocabularyId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Get all vocabulary with optional JLPT level filter and search.
     * GET /api/v1/manager/vocabulary?level=N5&keyword=...&page=0&size=20
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<VocabularyResponse>>> getAllVocabulary(
            @RequestParam(required = false) JlptLevel level,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, Math.min(size, 100),
                Sort.by("jlptLevel").ascending().and(Sort.by("japaneseText").ascending()));

        Page<VocabularyResponse> vocabPage;
        if (keyword != null && !keyword.isBlank()) {
            vocabPage = vocabularyService.searchVocabulary(keyword.trim(), pageable);
        } else {
            vocabPage = vocabularyService.getAllVocabulary(level, pageable);
        }

        PageResponse<VocabularyResponse> pageResponse = PageResponse.of(
                vocabPage.getContent(),
                vocabPage.getNumber(),
                vocabPage.getSize(),
                vocabPage.getTotalElements(),
                vocabPage.getTotalPages()
        );
        return ResponseEntity.ok(ApiResponse.success(pageResponse));
    }

    /**
     * Get all vocabulary for a specific lesson.
     * GET /api/v1/manager/vocabulary/by-lesson/{lessonId}
     */
    @GetMapping("/by-lesson/{lessonId}")
    public ResponseEntity<ApiResponse<List<VocabularyResponse>>> getVocabularyByLesson(
            @PathVariable UUID lessonId) {

        List<VocabularyResponse> vocabulary = vocabularyService.getVocabularyByLesson(lessonId);
        return ResponseEntity.ok(ApiResponse.success(vocabulary));
    }

    /**
     * Soft-delete a vocabulary entry.
     * DELETE /api/v1/manager/vocabulary/{vocabularyId}
     */
    @DeleteMapping("/{vocabularyId}")
    public ResponseEntity<ApiResponse<Void>> deleteVocabulary(
            @PathVariable UUID vocabularyId,
            @AuthenticationPrincipal(expression = "id") UUID managerId) {

        log.info("Manager {} deleting vocabulary: {}", managerId, vocabularyId);
        vocabularyService.deleteVocabulary(vocabularyId, managerId);
        return ResponseEntity.ok(ApiResponse.success("Vocabulary deleted successfully", null));
    }
}

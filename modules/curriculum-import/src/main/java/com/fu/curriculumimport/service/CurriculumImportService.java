package com.fu.curriculumimport.service;

import com.fu.common.response.PageResponse;
import com.fu.curriculumimport.dto.response.*;
import com.fu.curriculumimport.entity.CurriculumImport.ImportStatus;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Service interface for Curriculum Import operations.
 */
public interface CurriculumImportService {

    /**
     * Upload a PDF and initiate async import processing.
     *
     * @param file      the PDF file
     * @param courseId  optional course to link the import to
     * @param managerId the manager performing the upload
     * @return import response with initial status
     */
    CurriculumImportResponse initiateImport(MultipartFile file, UUID courseId, UUID managerId);

    /**
     * Get import by ID with full details.
     */
    CurriculumImportResponse getImportById(UUID importId);

    /**
     * List all imports with pagination and optional filters.
     */
    PageResponse<CurriculumImportSummaryResponse> listImports(
            UUID courseId, ImportStatus status, int page, int size);

    /**
     * Get chunks for a specific import.
     */
    PageResponse<ImportChunkResponse> getImportChunks(UUID importId, int page, int size);

    /**
     * Retry a failed import.
     */
    CurriculumImportResponse retryImport(UUID importId);

    /**
     * Cancel an in-progress import.
     */
    CurriculumImportResponse cancelImport(UUID importId);

    /**
     * Get lessons created by a specific import.
     */
    List<LessonSummaryDto> getImportLessons(UUID importId);

    /**
     * Get vocabulary extracted by a specific import.
     */
    PageResponse<VocabularyResponse> getImportVocabulary(UUID importId, int page, int size);

    /**
     * Get grammar points extracted by a specific import.
     */
    PageResponse<GrammarPointResponse> getImportGrammarPoints(UUID importId, int page, int size);

    /**
     * Get import statistics for dashboard.
     */
    ImportStatisticsResponse getStatistics();

    /**
     * Link a completed import (and its lessons) to a course.
     * Used when an import was initiated without a courseId, or to reassign.
     *
     * @param importId  the import to link
     * @param courseId  the course to link to
     * @param managerId the manager performing the action
     * @return updated import response
     */
    CurriculumImportResponse linkToCourse(UUID importId, UUID courseId, UUID managerId);

    /**
     * Lightweight DTO for lesson summary in import context
     */
    record LessonSummaryDto(
            UUID id,
            String title,
            String titleJapanese,
            String lessonType,
            Integer orderIndex,
            Integer pageStart,
            Integer pageEnd,
            String status
    ) {}
}

package com.fu.curriculumimport.service;

import com.fu.common.response.PageResponse;
import com.fu.curriculumimport.dto.response.*;
import com.fu.curriculumimport.entity.CurriculumImport.ImportStatus;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
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
     *
     * @param courseId optional - filter by course
     * @param status optional - filter by import status
     * @param startDate optional - filter by creation date (inclusive)
     * @param endDate optional - filter by creation date (inclusive)
     * @param page page number (0-indexed)
     * @param size page size
     * @return paginated list of imports ordered by creation date (newest first)
     */
    PageResponse<CurriculumImportSummaryResponse> listImports(
            UUID courseId, ImportStatus status, LocalDateTime startDate, LocalDateTime endDate, int page, int size);

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
     * Approve a PENDING_APPROVAL import.
     * Sets status to COMPLETED; draft content remains in the DB.
     *
     * @param importId  the import to approve
     * @param managerId the manager performing the approval
     * @return updated import response
     */
    CurriculumImportResponse approveImport(UUID importId, UUID managerId);

    /**
     * Reject a PENDING_APPROVAL import.
     * Soft-deletes all lessons created by this import and sets status to CANCELLED.
     * Vocabulary and GrammarPoint entries are retained as shared resources.
     *
     * @param importId  the import to reject
     * @param managerId the manager performing the rejection
     * @return updated import response
     */
    CurriculumImportResponse rejectImport(UUID importId, UUID managerId);

    /**
     * Rollback a COMPLETED import.
     * Soft-deletes lessons, purges embeddings, and sets status to ROLLED_BACK.
     * Vocabulary and GrammarPoint entries are retained as shared resources.
     *
     * @param importId  the import to rollback
     * @param managerId the manager performing the rollback
     * @return updated import response
     */
    CurriculumImportResponse rollbackImport(UUID importId, UUID managerId);

    /**
     * Lightweight DTO for lesson summary in import context
     */
    record LessonSummaryDto(
            UUID id,
            String title,
            String titleJapanese,
            Integer orderIndex,
            Integer pageStart,
            Integer pageEnd,
            String status
    ) {}
}

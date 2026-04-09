package com.fu.curriculumimport.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.common.exception.BusinessException;
import com.fu.common.response.PageResponse;
import com.fu.course.entity.GrammarPoint;
import com.fu.course.entity.Lesson;
import com.fu.course.entity.Vocabulary;
import com.fu.course.exception.CourseNotFoundException;
import com.fu.course.repository.*;
import com.fu.curriculumimport.dto.response.*;
import com.fu.curriculumimport.entity.CurriculumImport;
import com.fu.curriculumimport.entity.CurriculumImport.ImportStatus;
import com.fu.curriculumimport.entity.ImportChunk;
import com.fu.curriculumimport.exception.CurriculumImportNotFoundException;
import com.fu.curriculumimport.exception.ImportStateException;
import com.fu.curriculumimport.exception.InvalidImportFileException;
import com.fu.curriculumimport.mapper.CurriculumImportMapper;
import com.fu.curriculumimport.repository.CurriculumImportRepository;
import com.fu.curriculumimport.repository.ImportChunkRepository;
import com.fu.curriculumimport.service.CurriculumImportService;
import com.fu.curriculumimport.service.agent.ExtractionResult;
import com.fu.curriculumimport.service.embedding.EmbeddingService;
import com.fu.curriculumimport.service.ingestion.InMemoryMultipartFile;
import com.fu.curriculumimport.service.orchestrator.ImportOrchestrator;
import com.fu.curriculumimport.service.persistence.ImportPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Implementation of CurriculumImportService
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class CurriculumImportServiceImpl implements CurriculumImportService {

    private final CurriculumImportRepository importRepository;
    private final ImportChunkRepository chunkRepository;
    private final CourseRepository courseRepository;
    private final LessonRepository lessonRepository;
    private final VocabularyRepository vocabularyRepository;
    private final GrammarPointRepository grammarPointRepository;
    private final CurriculumImportMapper importMapper;
    private final ImportOrchestrator orchestrator;
    private final ImportPersistenceService persistenceService;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public CurriculumImportResponse initiateImport(MultipartFile file, UUID courseId, UUID managerId) {
        log.info("Initiating curriculum import by manager: {}, courseId: {}, file: {}",
                managerId, courseId, file.getOriginalFilename());

        // 1. Fast metadata checks (no file I/O): null, extension, size
        String filename = validateFileMetadata(file);

        // 2. Business checks: courseId and course existence
        // Do these before reading file bytes to avoid unnecessary I/O on invalid requests.
        if (courseId == null) {
            throw new BusinessException("VALIDATION_ERROR",
                    "courseId is required. Please select a course before uploading the curriculum.");
        }
        var course = courseRepository.findById(courseId)
                .orElseThrow(() -> new CourseNotFoundException(courseId));
        String courseName = course.getName();

        // 3. Content I/O: magic bytes validation (actual file content, not client-reported type)
        validateMagicBytes(file, filename);

        // Create import record
        CurriculumImport importEntity = CurriculumImport.builder()
                .originalFilename(file.getOriginalFilename())
                .fileUrl("pending://upload") // Will be updated after storage
                .fileSizeBytes(file.getSize())
                .fileType(file.getContentType())
                .courseId(courseId)
                .createdBy(managerId)
                .status(ImportStatus.QUEUED)
                .build();

        CurriculumImport saved = importRepository.save(importEntity);
        log.info("Import record created: {}", saved.getId());

        // Snapshot file bytes NOW — Spring's temp file backing the MultipartFile
        // will be deleted when the HTTP request completes, but the @Async executor
        // runs on a different thread AFTER the request is done.
        final InMemoryMultipartFile snapshot;
        try {
            snapshot = InMemoryMultipartFile.from(file);
        } catch (IOException e) {
            throw new InvalidImportFileException("Failed to read uploaded file: " + e.getMessage());
        }

        // Trigger async processing AFTER transaction commits to avoid race condition
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                orchestrator.processImportAsync(saved.getId(), snapshot);
            }
        });

        CurriculumImportResponse response = importMapper.toResponse(saved);
        response.setCourseName(courseName);
        return response;
    }

    @Override
    public CurriculumImportResponse getImportById(UUID importId) {
        log.debug("Fetching import by ID: {}", importId);
        CurriculumImport importEntity = findImportOrThrow(importId);
        CurriculumImportResponse response = importMapper.toResponse(importEntity);
        enrichWithCourseName(response, importEntity.getCourseId());
        return response;
    }

    @Override
    public PageResponse<CurriculumImportSummaryResponse> listImports(
            UUID courseId, ImportStatus status, LocalDateTime startDate, LocalDateTime endDate, int page, int size) {
        log.debug("Listing imports: courseId={}, status={}, startDate={}, endDate={}, page={}, size={}",
                courseId, status, startDate, endDate, page, size);

        // Validate date range if provided
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new BusinessException("Ngày kết thúc phải sau ngày bắt đầu.");
        }

        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<CurriculumImport> importPage;

        // Route to appropriate repository method based on which filters are provided
        boolean hasDateRange = startDate != null && endDate != null;

        if (hasDateRange) {
            // Date range is provided
            if (courseId != null && status != null) {
                importPage = importRepository.findByCourseIdAndStatusAndCreatedAtBetweenOrderByCreatedAtDesc(
                        courseId, status, startDate, endDate, pageable);
            } else if (courseId != null) {
                importPage = importRepository.findByCourseIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                        courseId, startDate, endDate, pageable);
            } else if (status != null) {
                importPage = importRepository.findByStatusAndCreatedAtBetweenOrderByCreatedAtDesc(
                        status, startDate, endDate, pageable);
            } else {
                importPage = importRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(
                        startDate, endDate, pageable);
            }
        } else {
            // No date range - use existing logic
            if (courseId != null && status != null) {
                importPage = importRepository.findByCourseIdAndStatusOrderByCreatedAtDesc(courseId, status, pageable);
            } else if (courseId != null) {
                importPage = importRepository.findByCourseIdOrderByCreatedAtDesc(courseId, pageable);
            } else if (status != null) {
                importPage = importRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
            } else {
                importPage = importRepository.findAllByOrderByCreatedAtDesc(pageable);
            }
        }

        List<CurriculumImportSummaryResponse> content = importPage.getContent().stream()
                .map(entity -> {
                    CurriculumImportSummaryResponse summary = importMapper.toSummaryResponse(entity);
                    enrichSummaryWithCourseName(summary, entity.getCourseId());
                    return summary;
                })
                .toList();

        return PageResponse.of(
                content,
                importPage.getNumber(),
                importPage.getSize(),
                importPage.getTotalElements(),
                importPage.getTotalPages()
        );
    }

    @Override
    public PageResponse<ImportChunkResponse> getImportChunks(UUID importId, int page, int size) {
        log.debug("Fetching chunks for import: {}", importId);
        findImportOrThrow(importId); // Verify import exists

        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<ImportChunk> chunkPage = chunkRepository
                .findByCurriculumImportIdOrderByChunkIndexAsc(importId, pageable);

        List<ImportChunkResponse> content = chunkPage.getContent().stream()
                .map(importMapper::toChunkResponse)
                .toList();

        return PageResponse.of(
                content,
                chunkPage.getNumber(),
                chunkPage.getSize(),
                chunkPage.getTotalElements(),
                chunkPage.getTotalPages()
        );
    }

    @Override
    @Transactional
    public CurriculumImportResponse retryImport(UUID importId) {
        log.info("Retrying import: {}", importId);
        CurriculumImport importEntity = findImportOrThrow(importId);

        if (!importEntity.canRetry()) {
            throw new ImportStateException(
                    "Can only retry failed imports. Current status: " + importEntity.getStatus() +
                            ", retry count: " + importEntity.getRetryCount() + "/" + importEntity.getMaxRetries());
        }

        importEntity.resetForRetry();
        importRepository.save(importEntity);

        // Re-trigger processing (file would need to be re-read from storage)
        // For now, mark as queued - a scheduler will pick it up
        log.info("Import {} queued for retry (attempt #{})", importId, importEntity.getRetryCount());

        CurriculumImportResponse response = importMapper.toResponse(importEntity);
        enrichWithCourseName(response, importEntity.getCourseId());
        return response;
    }

    @Override
    @Transactional
    public CurriculumImportResponse cancelImport(UUID importId) {
        log.info("Cancelling import: {}", importId);

        // Reload fresh from DB inside the transaction to avoid stale-state race with
        // the async pipeline thread that may have changed status since last read.
        CurriculumImport importEntity = findImportOrThrow(importId);

        if (importEntity.getStatus() == ImportStatus.COMPLETED ||
                importEntity.getStatus() == ImportStatus.CANCELLED ||
                importEntity.getStatus() == ImportStatus.ROLLED_BACK) {
            throw new ImportStateException(
                    "Cannot cancel an already " + importEntity.getStatus().name().toLowerCase() + " import");
        }
        if (importEntity.getStatus() == ImportStatus.PENDING_APPROVAL) {
            throw new ImportStateException(
                    "Import is awaiting approval. Use the reject endpoint to cancel it.");
        }

        // Signal the async pipeline to stop at its next checkpoint.
        orchestrator.requestCancel(importId);

        importEntity.markCancelled();
        importRepository.save(importEntity);

        CurriculumImportResponse response = importMapper.toResponse(importEntity);
        enrichWithCourseName(response, importEntity.getCourseId());
        return response;
    }

    @Override
    public List<LessonSummaryDto> getImportLessons(UUID importId) {
        log.debug("Fetching lessons for import: {}", importId);
        findImportOrThrow(importId);

        List<Lesson> lessons = lessonRepository.findByImportIdAndNotDeleted(importId);

        return lessons.stream()
                .map(l -> new LessonSummaryDto(
                        l.getId(),
                        l.getTitle(),
                        l.getTitleJapanese(),
                        l.getOrderIndex(),
                        l.getPageStart(),
                        l.getPageEnd(),
                        l.getStatus().name()
                ))
                .toList();
    }

    @Override
    public PageResponse<VocabularyResponse> getImportVocabulary(UUID importId, int page, int size) {
        log.debug("Fetching vocabulary for import: {}", importId);
        CurriculumImport importEntity = findImportOrThrow(importId);

        Pageable pageable = PageRequest.of(page, Math.min(size, 100));

        // Query vocabulary linked to THIS specific import through lessons,
        // not just the course (which would return vocab from ALL imports).
        Page<Vocabulary> vocabPage = vocabularyRepository
                .findByImportId(importId, pageable);

        List<VocabularyResponse> content = vocabPage.getContent().stream()
                .map(importMapper::toVocabularyResponse)
                .toList();

        return PageResponse.of(content, vocabPage.getNumber(), vocabPage.getSize(),
                vocabPage.getTotalElements(), vocabPage.getTotalPages());
    }

    @Override
    public PageResponse<GrammarPointResponse> getImportGrammarPoints(UUID importId, int page, int size) {
        log.debug("Fetching grammar points for import: {}", importId);
        CurriculumImport importEntity = findImportOrThrow(importId);

        Pageable pageable = PageRequest.of(page, Math.min(size, 100));

        // Query grammar linked to THIS specific import through lessons,
        // not just the course (which would return grammar from ALL imports).
        Page<GrammarPoint> grammarPage = grammarPointRepository
                .findByImportId(importId, pageable);

        List<GrammarPointResponse> content = grammarPage.getContent().stream()
                .map(importMapper::toGrammarPointResponse)
                .toList();

        return PageResponse.of(content, grammarPage.getNumber(), grammarPage.getSize(),
                grammarPage.getTotalElements(), grammarPage.getTotalPages());
    }

    @Override
    public ImportStatisticsResponse getStatistics(UUID courseId) {
        log.debug("Fetching import statistics for course: {}", courseId);

        // Validate course exists
        courseRepository.findById(courseId)
                .orElseThrow(() -> new CourseNotFoundException(courseId));

        long totalImports = importRepository.countByCourseId(courseId);
        long completedImports = importRepository.countByCourseIdAndStatus(courseId, ImportStatus.COMPLETED);
        long failedImports = importRepository.countByCourseIdAndStatus(courseId, ImportStatus.FAILED);
        long queuedImports = importRepository.countByCourseIdAndStatus(courseId, ImportStatus.QUEUED);
        long rollbackImports = importRepository.countByCourseIdAndStatus(courseId, ImportStatus.ROLLED_BACK);
        long pendingApprovalImports = importRepository.countByCourseIdAndStatus(courseId, ImportStatus.PENDING_APPROVAL);

        // Calculate in-progress
        long inProgressImports = totalImports - completedImports - failedImports - queuedImports
                - importRepository.countByCourseIdAndStatus(courseId, ImportStatus.CANCELLED);

        long totalLessons = importRepository.sumLessonsCreatedByCourseId(courseId);
        long totalVocab = importRepository.sumVocabularyCreatedByCourseId(courseId);
        long totalGrammar = importRepository.sumGrammarPointsCreatedByCourseId(courseId);
        long totalTokens = importRepository.sumTokensUsedByCourseId(courseId);
        BigDecimal totalCost = importRepository.sumActualCostUsdByCourseId(courseId);

        return ImportStatisticsResponse.builder()
                .totalImports(totalImports)
                .completedImports(completedImports)
                .failedImports(failedImports)
                .queuedImports(queuedImports)
                .rollbackImports(rollbackImports)
                .pendingApprovalImports(pendingApprovalImports)
                .inProgressImports(Math.max(0, inProgressImports))
                .totalLessonsCreated(totalLessons)
                .totalVocabularyCreated(totalVocab)
                .totalGrammarPointsCreated(totalGrammar)
                .totalTokensUsed(totalTokens)
                .totalCostUsd(totalCost != null ? totalCost : BigDecimal.ZERO)
                .totalValidationPassed(importRepository.sumValidationPassedByCourseId(courseId))
                .totalValidationFailed(importRepository.sumValidationFailedByCourseId(courseId))
                .totalReExtractions(importRepository.sumReExtractionsByCourseId(courseId))
                .totalEmbeddingsCreated(importRepository.sumEmbeddingsCreatedByCourseId(courseId))
                .totalHallucinatedItems(importRepository.sumHallucinatedItemsByCourseId(courseId))
                .build();
    }

    @Override
    @Transactional
    public CurriculumImportResponse linkToCourse(UUID importId, UUID courseId, UUID managerId) {
        log.info("Manager {} linking import {} to course {}", managerId, importId, courseId);

        CurriculumImport importEntity = findImportOrThrow(importId);

        var course = courseRepository.findById(courseId)
                .orElseThrow(() -> new CourseNotFoundException(courseId));

        // Update the import record
        importEntity.setCourseId(courseId);
        importRepository.save(importEntity);

        // Reassign existing lessons to the target course
        List<Lesson> lessons = lessonRepository.findByImportIdAndNotDeleted(importId);
        for (Lesson lesson : lessons) {
            lesson.setCourse(course);
            lessonRepository.save(lesson);
        }

        if (!lessons.isEmpty()) {
            log.info("Linked {} existing lessons from import {} to course {}", lessons.size(), importId, courseId);
        } else {
            // No lessons were created during the original import (courseId was null at upload time).
            // Re-run lesson persistence from stored extractedData — no LLM calls, zero cost.
            log.info("Import {} has 0 lessons — re-running lesson persistence from stored chunk data", importId);
            List<ExtractionResult> storedResults = deserializeChunkResults(importId);
            if (!storedResults.isEmpty()) {
                persistenceService.persistExtractedContent(importEntity, storedResults);
                log.info("Re-created {} lessons for import {} linked to course {}",
                        importEntity.getLessonsCreated(), importId, courseId);
            } else {
                log.warn("Import {} has no completed chunks with extractedData — cannot re-create lessons", importId);
            }
        }

        CurriculumImportResponse response = importMapper.toResponse(importEntity);
        response.setCourseName(course.getName());
        return response;
    }

    /**
     * Deserialize stored {@code extractedData} JSONB from completed chunks back into
     * {@link ExtractionResult} objects for lesson re-persistence.
     *
     * <p>Called by {@link #linkToCourse} when an import completed with {@code lessonsCreated=0}
     * because {@code courseId} was null at upload time. The extraction results were already
     * serialized and stored in {@code import_chunks.extracted_data} during the original run,
     * so no LLM calls are needed.
     */
    private List<ExtractionResult> deserializeChunkResults(UUID importId) {
        List<ImportChunk> chunks = chunkRepository
                .findByCurriculumImportIdOrderByChunkIndexAsc(importId);

        List<ExtractionResult> results = new ArrayList<>();
        for (ImportChunk chunk : chunks) {
            if (chunk.getStatus() != ImportChunk.ChunkStatus.COMPLETED
                    || chunk.getExtractedData() == null
                    || chunk.getExtractedData().isBlank()) {
                continue;
            }
            try {
                ExtractionResult result = objectMapper.readValue(
                        chunk.getExtractedData(), ExtractionResult.class);
                results.add(result);
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize extractedData for chunk {} of import {}: {}",
                        chunk.getChunkIndex(), importId, e.getMessage());
            }
        }
        log.info("Deserialized {} extraction results from {} chunks for import {}",
                results.size(), chunks.size(), importId);
        return results;
    }

    @Override
    @Transactional
    public CurriculumImportResponse approveImport(UUID importId, UUID managerId) {
        log.info("Manager {} approving import: {}", managerId, importId);
        CurriculumImport importEntity = findImportOrThrow(importId);

        if (importEntity.getStatus() != ImportStatus.PENDING_APPROVAL) {
            throw new ImportStateException(
                    "Can only approve imports in PENDING_APPROVAL status. Current status: "
                            + importEntity.getStatus());
        }

        importEntity.markCompleted();
        importRepository.save(importEntity);
        log.info("Import {} approved and marked COMPLETED", importId);

        CurriculumImportResponse response = importMapper.toResponse(importEntity);
        enrichWithCourseName(response, importEntity.getCourseId());
        return response;
    }

    @Override
    @Transactional
    public CurriculumImportResponse rejectImport(UUID importId, UUID managerId) {
        log.info("Manager {} rejecting import: {}", managerId, importId);
        CurriculumImport importEntity = findImportOrThrow(importId);

        if (importEntity.getStatus() != ImportStatus.PENDING_APPROVAL) {
            throw new ImportStateException(
                    "Can only reject imports in PENDING_APPROVAL status. Current status: "
                            + importEntity.getStatus());
        }

        persistenceService.softDeleteLessonsByImportId(importId, managerId);
        embeddingService.purgeEmbeddingsByImportId(importId);
        importEntity.markCancelled();
        importRepository.save(importEntity);
        log.info("Import {} rejected and marked CANCELLED; lessons soft-deleted, embeddings purged", importId);

        CurriculumImportResponse response = importMapper.toResponse(importEntity);
        enrichWithCourseName(response, importEntity.getCourseId());
        return response;
    }

    @Override
    @Transactional
    public CurriculumImportResponse rollbackImport(UUID importId, UUID managerId) {
        log.info("Manager {} rolling back import: {}", managerId, importId);
        CurriculumImport importEntity = findImportOrThrow(importId);

        if (importEntity.getStatus() != ImportStatus.COMPLETED) {
            throw new ImportStateException(
                    "Can only rollback imports in COMPLETED status. Current status: "
                            + importEntity.getStatus());
        }

        // 1. Soft-delete lessons + clean up join tables
        persistenceService.softDeleteLessonsByImportId(importId, managerId);

        // 2. Purge embeddings created by this import
        embeddingService.purgeEmbeddingsByImportId(importId);

        // 3. Mark as rolled back
        importEntity.markRolledBack();
        importRepository.save(importEntity);
        log.info("Import {} rolled back: lessons soft-deleted, embeddings purged", importId);

        CurriculumImportResponse response = importMapper.toResponse(importEntity);
        enrichWithCourseName(response, importEntity.getCourseId());
        return response;
    }

    // --- Helper methods ---

    /**
     * 100 MB — PPTX with embedded images can be larger than plain PDFs.
     */
    private static final long MAX_FILE_SIZE_BYTES = 100L * 1024 * 1024;

    private static final Set<String> ACCEPTED_EXTENSIONS = Set.of(".pdf", ".pptx");

    /**
     * PDF magic bytes: "%PDF-" (0x25 0x50 0x44 0x46 0x2D)
     * PPTX/XLSX/ZIP magic bytes: "PK\x03\x04" (Office Open XML is a ZIP archive)
     */
    private static final byte[] PDF_MAGIC  = {0x25, 0x50, 0x44, 0x46, 0x2D}; // %PDF-
    private static final byte[] PPTX_MAGIC = {0x50, 0x4B, 0x03, 0x04};        // PK..

    /**
     * Validates file metadata (no content I/O): null/empty, extension, size.
     *
     * @return lowercase filename for reuse in magic-bytes check
     */
    private String validateFileMetadata(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidImportFileException("File is required and must not be empty");
        }

        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        boolean extOk = ACCEPTED_EXTENSIONS.stream().anyMatch(filename::endsWith);

        if (!extOk) {
            throw new InvalidImportFileException(
                    "Chỉ chấp nhận các tệp PDF và PPTX. Tên tệp nhận được: " + file.getOriginalFilename());
        }

        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new InvalidImportFileException(String.format(
                    "File size %.1f MB exceeds the maximum limit of %d MB.",
                    file.getSize() / (1024.0 * 1024.0),
                    MAX_FILE_SIZE_BYTES / (1024 * 1024)));
        }

        return filename;
    }

    private void validateMagicBytes(MultipartFile file, String lowercaseFilename) {
        int headerLength = PDF_MAGIC.length; // 5 bytes — longest magic sequence
        byte[] header = new byte[headerLength];

        try (InputStream is = file.getInputStream()) {
            int bytesRead = is.read(header, 0, headerLength);
            if (bytesRead < 4) {
                throw new InvalidImportFileException("File quá nhỏ hoặc rỗng");
            }
        } catch (IOException e) {
            throw new InvalidImportFileException("Không thể đọc file để xác thực: " + e.getMessage());
        }

        if (lowercaseFilename.endsWith(".pdf")) {
            if (!startsWith(header, PDF_MAGIC)) {
                throw new InvalidImportFileException(
                        "File không phải PDF hợp lệ. Nội dung không khớp với định dạng PDF (%PDF-).");
            }
        } else if (lowercaseFilename.endsWith(".pptx")) {
            if (!startsWith(header, PPTX_MAGIC)) {
                throw new InvalidImportFileException(
                        "File không phải PPTX hợp lệ. Nội dung không khớp với định dạng Office Open XML (PK).");
            }
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        return Arrays.equals(Arrays.copyOf(data, prefix.length), prefix);
    }

    private CurriculumImport findImportOrThrow(UUID importId) {
        return importRepository.findById(importId)
                .orElseThrow(() -> new CurriculumImportNotFoundException(importId));
    }

    private void enrichWithCourseName(CurriculumImportResponse response, UUID courseId) {
        if (courseId != null) {
            courseRepository.findById(courseId)
                    .ifPresent(course -> response.setCourseName(course.getName()));
        }
    }

    private void enrichSummaryWithCourseName(CurriculumImportSummaryResponse response, UUID courseId) {
        if (courseId != null) {
            courseRepository.findById(courseId)
                    .ifPresent(course -> response.setCourseName(course.getName()));
        }
    }
}

package com.fu.curriculumimport.service.impl;

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
import java.math.BigDecimal;
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

    @Override
    @Transactional
    public CurriculumImportResponse initiateImport(MultipartFile file, UUID courseId, UUID managerId) {
        log.info("Initiating curriculum import by manager: {}, courseId: {}, file: {}",
                managerId, courseId, file.getOriginalFilename());

        // Validate file
        validateImportFile(file);

        // Validate course exists if provided
        String courseName = null;
        if (courseId != null) {
            var course = courseRepository.findById(courseId)
                    .orElseThrow(() -> new CourseNotFoundException(courseId));
            courseName = course.getName();
        }

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
            UUID courseId, ImportStatus status, int page, int size) {
        log.debug("Listing imports: courseId={}, status={}, page={}, size={}", courseId, status, page, size);

        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        Page<CurriculumImport> importPage;

        if (courseId != null && status != null) {
            importPage = importRepository.findByCourseIdAndStatusOrderByCreatedAtDesc(courseId, status, pageable);
        } else if (courseId != null) {
            importPage = importRepository.findByCourseIdOrderByCreatedAtDesc(courseId, pageable);
        } else if (status != null) {
            importPage = importRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
        } else {
            importPage = importRepository.findAllByOrderByCreatedAtDesc(pageable);
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
                        l.getLessonType().name(),
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
    public ImportStatisticsResponse getStatistics() {
        log.debug("Fetching import statistics");

        long totalImports = importRepository.count();
        long completedImports = importRepository.countByStatus(ImportStatus.COMPLETED);
        long failedImports = importRepository.countByStatus(ImportStatus.FAILED);
        long queuedImports = importRepository.countByStatus(ImportStatus.QUEUED);

        // Count in-progress (any status between QUEUED and COMPLETED/FAILED/CANCELLED)
        long inProgressImports = totalImports - completedImports - failedImports - queuedImports
                - importRepository.countByStatus(ImportStatus.CANCELLED);

        long totalLessons = importRepository.sumLessonsCreated();
        long totalVocab = importRepository.sumVocabularyCreated();
        long totalGrammar = importRepository.sumGrammarPointsCreated();
        long totalTokens = importRepository.sumTokensUsed();
        BigDecimal totalCost = importRepository.sumActualCostUsd();

        return ImportStatisticsResponse.builder()
                .totalImports(totalImports)
                .completedImports(completedImports)
                .failedImports(failedImports)
                .queuedImports(queuedImports)
                .inProgressImports(Math.max(0, inProgressImports))
                .totalLessonsCreated(totalLessons)
                .totalVocabularyCreated(totalVocab)
                .totalGrammarPointsCreated(totalGrammar)
                .totalTokensUsed(totalTokens)
                .totalCostUsd(totalCost != null ? totalCost : BigDecimal.ZERO)
                .totalValidationPassed(importRepository.sumValidationPassed())
                .totalValidationFailed(importRepository.sumValidationFailed())
                .totalReExtractions(importRepository.sumReExtractions())
                .totalEmbeddingsCreated(importRepository.sumEmbeddingsCreated())
                .totalHallucinatedItems(importRepository.sumHallucinatedItems())
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

        // Reassign all lessons that belong to this import to the target course
        List<Lesson> lessons = lessonRepository.findByImportIdAndNotDeleted(importId);
        for (Lesson lesson : lessons) {
            lesson.setCourse(course);
            lessonRepository.save(lesson);
        }

        log.info("Linked {} lessons from import {} to course {}", lessons.size(), importId, courseId);

        CurriculumImportResponse response = importMapper.toResponse(importEntity);
        response.setCourseName(course.getName());
        return response;
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

    /** 100 MB — PPTX with embedded images can be larger than plain PDFs. */
    private static final long MAX_FILE_SIZE_BYTES = 100L * 1024 * 1024;

    private static final Set<String> ACCEPTED_MIME_TYPES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.ms-powerpoint.presentation.macroEnabled.12",
            "application/zip"   // some browsers report PPTX as generic zip
    );

    private static final Set<String> ACCEPTED_EXTENSIONS = Set.of(".pdf", ".pptx");

    private void validateImportFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidImportFileException("File is required and must not be empty");
        }

        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        String filename    = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        boolean mimeOk     = ACCEPTED_MIME_TYPES.contains(contentType);
        boolean extOk      = ACCEPTED_EXTENSIONS.stream().anyMatch(filename::endsWith);

        if (!mimeOk && !extOk) {
            throw new InvalidImportFileException(
                    "Only PDF and PPTX files are accepted. Received MIME type: " + contentType
                            + ", filename: " + file.getOriginalFilename());
        }

        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new InvalidImportFileException(String.format(
                    "File size %.1f MB exceeds the maximum limit of %d MB.",
                    file.getSize() / (1024.0 * 1024.0),
                    MAX_FILE_SIZE_BYTES / (1024 * 1024)));
        }
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

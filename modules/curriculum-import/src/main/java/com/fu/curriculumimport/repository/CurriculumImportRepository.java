package com.fu.curriculumimport.repository;

import com.fu.curriculumimport.entity.CurriculumImport;
import com.fu.curriculumimport.entity.CurriculumImport.ImportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Repository for CurriculumImport entity
 */
@Repository
public interface CurriculumImportRepository extends JpaRepository<CurriculumImport, UUID> {

    /**
     * Find all imports with pagination
     */
    Page<CurriculumImport> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Find imports by status
     */
    Page<CurriculumImport> findByStatusOrderByCreatedAtDesc(ImportStatus status, Pageable pageable);

    /**
     * Find imports by courseId
     */
    Page<CurriculumImport> findByCourseIdOrderByCreatedAtDesc(UUID courseId, Pageable pageable);

    /**
     * Find imports by courseId and status
     */
    Page<CurriculumImport> findByCourseIdAndStatusOrderByCreatedAtDesc(
            UUID courseId, ImportStatus status, Pageable pageable);

    /**
     * Find queued imports for processing (FIFO)
     */
    List<CurriculumImport> findByStatusOrderByQueuedAtAsc(ImportStatus status);

    /**
     * Count by status
     */
    long countByStatus(ImportStatus status);

    /**
     * Count imports by course
     */
    long countByCourseId(UUID courseId);

    // --- Date range queries (new) ---

    /**
     * Find imports by date range only
     */
    Page<CurriculumImport> findByCreatedAtBetweenOrderByCreatedAtDesc(
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable);

    /**
     * Find imports by status and date range
     */
    Page<CurriculumImport> findByStatusAndCreatedAtBetweenOrderByCreatedAtDesc(
            ImportStatus status, LocalDateTime startDate, LocalDateTime endDate, Pageable pageable);

    /**
     * Find imports by courseId and date range
     */
    Page<CurriculumImport> findByCourseIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            UUID courseId, LocalDateTime startDate, LocalDateTime endDate, Pageable pageable);

    /**
     * Find imports by courseId, status, and date range
     */
    Page<CurriculumImport> findByCourseIdAndStatusAndCreatedAtBetweenOrderByCreatedAtDesc(
            UUID courseId, ImportStatus status, LocalDateTime startDate, LocalDateTime endDate, Pageable pageable);

    // --- Statistics queries ---

    @Query("SELECT COALESCE(SUM(ci.lessonsCreated), 0) FROM CurriculumImport ci WHERE ci.status = 'COMPLETED'")
    long sumLessonsCreated();

    @Query("SELECT COALESCE(SUM(ci.vocabularyCreated), 0) FROM CurriculumImport ci WHERE ci.status = 'COMPLETED'")
    long sumVocabularyCreated();

    @Query("SELECT COALESCE(SUM(ci.grammarPointsCreated), 0) FROM CurriculumImport ci WHERE ci.status = 'COMPLETED'")
    long sumGrammarPointsCreated();

    @Query("SELECT COALESCE(SUM(ci.tokensUsed), 0) FROM CurriculumImport ci")
    long sumTokensUsed();

    @Query("SELECT COALESCE(SUM(ci.actualCostUsd), 0) FROM CurriculumImport ci")
    BigDecimal sumActualCostUsd();

    // --- Agentic RAG statistics ---

    @Query("SELECT COALESCE(SUM(ci.validationPassed), 0) FROM CurriculumImport ci WHERE ci.status = 'COMPLETED'")
    long sumValidationPassed();

    @Query("SELECT COALESCE(SUM(ci.validationFailed), 0) FROM CurriculumImport ci WHERE ci.status = 'COMPLETED'")
    long sumValidationFailed();

    @Query("SELECT COALESCE(SUM(ci.reExtractions), 0) FROM CurriculumImport ci WHERE ci.status = 'COMPLETED'")
    long sumReExtractions();

    @Query("SELECT COALESCE(SUM(ci.embeddingsCreated), 0) FROM CurriculumImport ci WHERE ci.status = 'COMPLETED'")
    long sumEmbeddingsCreated();

    @Query("SELECT COALESCE(SUM(ci.hallucinatedItems), 0) FROM CurriculumImport ci WHERE ci.status = 'COMPLETED'")
    long sumHallucinatedItems();

    //
}

package com.fu.course.repository;

import com.fu.course.entity.Course.JlptLevel;
import com.fu.course.entity.GrammarPoint;
import com.fu.course.entity.GrammarPoint.GrammarCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for GrammarPoint entity
 */
@Repository
public interface GrammarPointRepository extends JpaRepository<GrammarPoint, UUID> {

    /**
     * Find grammar by pattern and JLPT level, excluding soft-deleted records.
     * Used by curriculum import deduplication to avoid resurrecting deleted items.
     */
    Optional<GrammarPoint> findByPatternAndJlptLevelAndDeletedAtIsNull(String pattern, JlptLevel jlptLevel);

    /**
     * Find grammar by pattern and JLPT level (includes soft-deleted).
     * @deprecated Use {@link #findByPatternAndJlptLevelAndDeletedAtIsNull} to exclude deleted records.
     */
    @Deprecated
    Optional<GrammarPoint> findByPatternAndJlptLevel(String pattern, JlptLevel jlptLevel);

    /**
     * Check if grammar point exists (non-deleted only)
     */
    boolean existsByPatternAndJlptLevelAndDeletedAtIsNull(String pattern, JlptLevel jlptLevel);

    /**
     * Find all non-deleted grammar points by JLPT level
     */
    Page<GrammarPoint> findByJlptLevelAndDeletedAtIsNull(JlptLevel jlptLevel, Pageable pageable);

    /**
     * Find all non-deleted grammar points by category
     */
    Page<GrammarPoint> findByCategoryAndDeletedAtIsNull(GrammarCategory category, Pageable pageable);

    /**
     * Find all non-deleted grammar points
     */
    Page<GrammarPoint> findByDeletedAtIsNull(Pageable pageable);

    /**
     * Find grammar linked to a specific lesson (non-deleted only)
     */
    @Query("SELECT g FROM GrammarPoint g JOIN LessonGrammar lg ON lg.grammarPoint.id = g.id " +
            "WHERE lg.lesson.id = :lessonId AND g.deletedAt IS NULL ORDER BY lg.orderIndex ASC")
    List<GrammarPoint> findByLessonId(@Param("lessonId") UUID lessonId);

    /**
     * Find grammar linked to a specific course (non-deleted only)
     */
    @Query("SELECT DISTINCT g FROM GrammarPoint g " +
            "JOIN LessonGrammar lg ON lg.grammarPoint.id = g.id " +
            "JOIN Lesson l ON l.id = lg.lesson.id " +
            "WHERE l.course.id = :courseId AND g.deletedAt IS NULL")
    Page<GrammarPoint> findByCourseId(@Param("courseId") UUID courseId, Pageable pageable);

    /**
     * Find grammar linked to a specific import (through lessons with import_id).
     * This is import-specific — only returns grammar created/linked by THIS import.
     */
    @Query("SELECT DISTINCT g FROM GrammarPoint g " +
            "JOIN LessonGrammar lg ON lg.grammarPoint.id = g.id " +
            "JOIN Lesson l ON l.id = lg.lesson.id " +
            "WHERE l.importId = :importId AND l.deletedAt IS NULL AND g.deletedAt IS NULL")
    Page<GrammarPoint> findByImportId(@Param("importId") UUID importId, Pageable pageable);

    /**
     * Search non-deleted grammar points by pattern or meaning
     */
    @Query("SELECT g FROM GrammarPoint g WHERE g.deletedAt IS NULL AND (" +
            "LOWER(g.pattern) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(g.meaningVi) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(g.meaningEn) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<GrammarPoint> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    /** IDs of non-deleted grammar points linked to a lesson — used by mastery dashboard. */
    @Query("SELECT g.id FROM GrammarPoint g JOIN LessonGrammar lg ON lg.grammarPoint.id = g.id " +
            "WHERE lg.lesson.id = :lessonId AND g.deletedAt IS NULL")
    List<UUID> findIdsByLessonId(@Param("lessonId") UUID lessonId);

    /**
     * IDs of non-deleted grammar points linked to a course via PUBLISHED lessons only.
     * Mirrors the Option B embedding gate so dashboard counts honor lesson lifecycle.
     */
    @Query("SELECT DISTINCT g.id FROM GrammarPoint g " +
            "JOIN LessonGrammar lg ON lg.grammarPoint.id = g.id " +
            "JOIN Lesson l ON l.id = lg.lesson.id " +
            "WHERE l.course.id = :courseId " +
            "  AND g.deletedAt IS NULL " +
            "  AND l.deletedAt IS NULL " +
            "  AND l.status = com.fu.course.entity.Lesson.ContentStatus.PUBLISHED")
    List<UUID> findIdsByCourseIdAndPublished(@Param("courseId") UUID courseId);
}

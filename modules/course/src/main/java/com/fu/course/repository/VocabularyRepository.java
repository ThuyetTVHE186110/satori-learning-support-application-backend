package com.fu.course.repository;

import com.fu.course.entity.Course.JlptLevel;
import com.fu.course.entity.Vocabulary;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.lang.ScopedValue;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Vocabulary entity
 */
@Repository
public interface VocabularyRepository extends JpaRepository<Vocabulary, UUID> {

    /**
     * Find vocabulary by japanese text and JLPT level, excluding soft-deleted records.
     * Used by curriculum import deduplication to avoid resurrecting deleted items.
     */
    Optional<Vocabulary> findByJapaneseTextAndJlptLevelAndDeletedAtIsNull(String japaneseText, JlptLevel jlptLevel);

    /**
     * Find vocabulary by japanese text and JLPT level (includes soft-deleted).
     *
     * @deprecated Use {@link #findByJapaneseTextAndJlptLevelAndDeletedAtIsNull} to exclude deleted records.
     */
    @Deprecated
    Optional<Vocabulary> findByJapaneseTextAndJlptLevel(String japaneseText, JlptLevel jlptLevel);

    /**
     * Check if vocabulary exists (ignores soft-deleted entries — the unique constraint
     * still applies on non-deleted rows, but for business logic we exclude deleted items)
     */
    boolean existsByJapaneseTextAndJlptLevelAndDeletedAtIsNull(String japaneseText, JlptLevel jlptLevel);

    /**
     * Find all non-deleted vocabulary by JLPT level
     */
    Page<Vocabulary> findByJlptLevelAndDeletedAtIsNull(JlptLevel jlptLevel, Pageable pageable);

    /**
     * Find all non-deleted vocabulary (no level filter)
     */
    Page<Vocabulary> findByDeletedAtIsNull(Pageable pageable);

    /**
     * Find vocabulary linked to a specific lesson via lesson_vocabulary (non-deleted only)
     */
    @Query("SELECT v FROM Vocabulary v JOIN LessonVocabulary lv ON lv.vocabulary.id = v.id " +
            "WHERE lv.lesson.id = :lessonId AND v.deletedAt IS NULL ORDER BY lv.orderIndex ASC")
    List<Vocabulary> findByLessonId(@Param("lessonId") UUID lessonId);

    /**
     * Find vocabulary linked to a specific course (non-deleted only)
     */
    @Query("SELECT DISTINCT v FROM Vocabulary v " +
            "JOIN LessonVocabulary lv ON lv.vocabulary.id = v.id " +
            "JOIN Lesson l ON l.id = lv.lesson.id " +
            "WHERE l.course.id = :courseId AND v.deletedAt IS NULL")
    Page<Vocabulary> findByCourseId(@Param("courseId") UUID courseId, Pageable pageable);

    /**
     * Find vocabulary linked to a specific import (through lessons with import_id).
     * This is import-specific — only returns vocab created/linked by THIS import.
     */
    @Query("SELECT DISTINCT v FROM Vocabulary v " +
            "JOIN LessonVocabulary lv ON lv.vocabulary.id = v.id " +
            "JOIN Lesson l ON l.id = lv.lesson.id " +
            "WHERE l.importId = :importId AND l.deletedAt IS NULL AND v.deletedAt IS NULL")
    Page<Vocabulary> findByImportId(@Param("importId") UUID importId, Pageable pageable);

    /**
     * Search non-deleted vocabulary by keyword (text, reading, or meaning)
     */
    @Query("SELECT v FROM Vocabulary v WHERE v.deletedAt IS NULL AND (" +
            "LOWER(v.japaneseText) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(v.reading) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(v.meaningVi) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(v.meaningEn) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<Vocabulary> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    Optional<Vocabulary> findVocabularyByJapaneseTextAndDeletedAtIsNull(String japaneseText);

    Optional<Vocabulary> findIdByJapaneseTextAndJlptLevel(String japaneseText, JlptLevel jlptLevel);
}

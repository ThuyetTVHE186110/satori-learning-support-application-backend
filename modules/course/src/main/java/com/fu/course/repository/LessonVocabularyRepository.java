package com.fu.course.repository;

import com.fu.course.entity.LessonVocabulary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Repository for LessonVocabulary junction entity
 */
@Repository
public interface LessonVocabularyRepository extends JpaRepository<LessonVocabulary, UUID> {

    List<LessonVocabulary> findByLessonIdOrderByOrderIndexAsc(UUID lessonId);

    boolean existsByLessonIdAndVocabularyId(UUID lessonId, UUID vocabularyId);

    void deleteByLessonId(UUID lessonId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM LessonVocabulary lv WHERE lv.lesson.id IN :lessonIds")
    void deleteByLessonIdIn(Collection<UUID> lessonIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM LessonVocabulary lv WHERE lv.vocabulary.id = :vocabularyId")
    void deleteByVocabularyId(@Param("vocabularyId") UUID vocabularyId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM LessonVocabulary lv WHERE lv.lesson.id = :lessonId AND lv.vocabulary.id = :vocabularyId")
    int deleteByLessonIdAndVocabularyId(@Param("lessonId") UUID lessonId,
                                        @Param("vocabularyId") UUID vocabularyId);

    /**
     * Count junctions for a lesson, excluding tombstone vocabulary (soft-deleted).
     * Keeps the count consistent with detail-view list which filters v.deletedAt IS NULL.
     */
    @Query("SELECT COUNT(lv) FROM LessonVocabulary lv " +
            "WHERE lv.lesson.id = :lessonId AND lv.vocabulary.deletedAt IS NULL")
    long countByLessonId(@Param("lessonId") UUID lessonId);

    /**
     * Count distinct active vocabulary linked to any non-deleted lesson of a course.
     */
    @Query("SELECT COUNT(DISTINCT lv.vocabulary.id) FROM LessonVocabulary lv " +
            "WHERE lv.lesson.course.id = :courseId " +
            "AND lv.lesson.deletedAt IS NULL " +
            "AND lv.vocabulary.deletedAt IS NULL")
    long countByCourseId(@Param("courseId") UUID courseId);

    /**
     * Count remaining junctions referencing a vocabulary across non-deleted lessons.
     * Used after junction wipe (e.g. import rollback) to detect orphan vocab.
     */
    @Query("SELECT COUNT(lv) FROM LessonVocabulary lv " +
            "WHERE lv.vocabulary.id = :vocabularyId AND lv.lesson.deletedAt IS NULL")
    long countByVocabularyId(@Param("vocabularyId") UUID vocabularyId);

    /**
     * Distinct vocabulary IDs linked to the given lessons. Snapshot used by import
     * rollback to identify orphan-cleanup candidates before junctions are wiped.
     */
    @Query("SELECT DISTINCT lv.vocabulary.id FROM LessonVocabulary lv WHERE lv.lesson.id IN :lessonIds")
    List<UUID> findVocabularyIdsByLessonIdIn(@Param("lessonIds") Collection<UUID> lessonIds);
}

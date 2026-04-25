package com.fu.course.repository;

import com.fu.course.entity.LessonGrammar;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Repository for LessonGrammar junction entity
 */
@Repository
public interface LessonGrammarRepository extends JpaRepository<LessonGrammar, UUID> {

    List<LessonGrammar> findByLessonIdOrderByOrderIndexAsc(UUID lessonId);

    boolean existsByLessonIdAndGrammarPointId(UUID lessonId, UUID grammarPointId);

    void deleteByLessonId(UUID lessonId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM LessonGrammar lg WHERE lg.lesson.id IN :lessonIds")
    void deleteByLessonIdIn(Collection<UUID> lessonIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM LessonGrammar lg WHERE lg.grammarPoint.id = :grammarPointId")
    void deleteByGrammarPointId(@Param("grammarPointId") UUID grammarPointId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM LessonGrammar lg WHERE lg.lesson.id = :lessonId AND lg.grammarPoint.id = :grammarPointId")
    int deleteByLessonIdAndGrammarPointId(@Param("lessonId") UUID lessonId,
                                          @Param("grammarPointId") UUID grammarPointId);

    /**
     * Count junctions for a lesson, excluding tombstone grammar points (soft-deleted).
     */
    @Query("SELECT COUNT(lg) FROM LessonGrammar lg " +
            "WHERE lg.lesson.id = :lessonId AND lg.grammarPoint.deletedAt IS NULL")
    long countByLessonId(@Param("lessonId") UUID lessonId);

    /**
     * Count distinct active grammar points linked to any non-deleted lesson of a course.
     */
    @Query("SELECT COUNT(DISTINCT lg.grammarPoint.id) FROM LessonGrammar lg " +
            "WHERE lg.lesson.course.id = :courseId " +
            "AND lg.lesson.deletedAt IS NULL " +
            "AND lg.grammarPoint.deletedAt IS NULL")
    long countByCourseId(@Param("courseId") UUID courseId);

    /**
     * Count remaining junctions referencing a grammar point across non-deleted lessons.
     */
    @Query("SELECT COUNT(lg) FROM LessonGrammar lg " +
            "WHERE lg.grammarPoint.id = :grammarPointId AND lg.lesson.deletedAt IS NULL")
    long countByGrammarPointId(@Param("grammarPointId") UUID grammarPointId);

    /**
     * Distinct grammar point IDs linked to the given lessons. Snapshot used by import
     * rollback to identify orphan-cleanup candidates before junctions are wiped.
     */
    @Query("SELECT DISTINCT lg.grammarPoint.id FROM LessonGrammar lg WHERE lg.lesson.id IN :lessonIds")
    List<UUID> findGrammarPointIdsByLessonIdIn(@Param("lessonIds") Collection<UUID> lessonIds);
}

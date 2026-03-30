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

    @Modifying
    @Query("DELETE FROM LessonGrammar lg WHERE lg.lesson.id IN :lessonIds")
    void deleteByLessonIdIn(Collection<UUID> lessonIds);

    long countByLessonId(UUID lessonId);

    @Query("SELECT COUNT(lg) FROM LessonGrammar lg WHERE lg.lesson.course.id = :courseId AND lg.lesson.deletedAt IS NULL")
    long countByCourseId(@Param("courseId") UUID courseId);
}

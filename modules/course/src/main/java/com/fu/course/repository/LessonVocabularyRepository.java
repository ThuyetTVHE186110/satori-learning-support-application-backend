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

    @Modifying
    @Query("DELETE FROM LessonVocabulary lv WHERE lv.lesson.id IN :lessonIds")
    void deleteByLessonIdIn(Collection<UUID> lessonIds);

    long countByLessonId(UUID lessonId);

    @Query("SELECT COUNT(lv) FROM LessonVocabulary lv WHERE lv.lesson.course.id = :courseId AND lv.lesson.deletedAt IS NULL")
    long countByCourseId(@Param("courseId") UUID courseId);
}

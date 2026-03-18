package com.fu.course.repository;

import com.fu.course.entity.LessonVocabulary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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

    long countByLessonId(UUID lessonId);
}

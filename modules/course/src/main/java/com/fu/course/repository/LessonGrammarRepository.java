package com.fu.course.repository;

import com.fu.course.entity.LessonGrammar;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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

    long countByLessonId(UUID lessonId);
}

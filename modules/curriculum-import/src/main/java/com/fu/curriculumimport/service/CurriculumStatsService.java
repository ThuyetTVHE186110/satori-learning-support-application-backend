package com.fu.curriculumimport.service;

import com.fu.course.repository.LessonGrammarRepository;
import com.fu.course.repository.LessonVocabularyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Curriculum statistics service — source-of-truth counts from entity tables.
 *
 * Reads from {@code learning_content.lesson_vocabulary} and
 * {@code learning_content.lesson_grammar} join tables, NOT from the vector
 * store ({@code progress.content_embeddings}).
 *
 * Vector metadata is an approximate, denormalized copy and is unreliable for
 * aggregate queries (incomplete metadata leads to under-counts). Entity join
 * tables are the relational source of truth — every persisted item is counted
 * regardless of embedding pipeline status.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CurriculumStatsService {

    private final LessonVocabularyRepository lessonVocabularyRepository;
    private final LessonGrammarRepository lessonGrammarRepository;

    /**
     * Vocabulary and grammar counts for a given scope.
     *
     * @param vocabularyCount number of vocabulary items
     * @param grammarCount    number of grammar points
     */
    public record ContentCount(long vocabularyCount, long grammarCount) {}

    /**
     * Count vocabulary and grammar points in the given scope.
     *
     * Scope resolution:
     *   lessonId != null → lesson-level count (exact for that lesson)
     *   lessonId == null → course-level count (all lessons in the course)
     *
     * @param courseId the course UUID (required)
     * @param lessonId the lesson UUID (null = course-wide count)
     * @return ContentCount with accurate counts from entity join tables
     */
    @Transactional(readOnly = true)
    public ContentCount countContent(UUID courseId, UUID lessonId) {
        try {
            if (lessonId != null) {
                long vocab   = lessonVocabularyRepository.countByLessonId(lessonId);
                long grammar = lessonGrammarRepository.countByLessonId(lessonId);
                log.debug("countContent (lesson): lessonId={} vocab={} grammar={}", lessonId, vocab, grammar);
                return new ContentCount(vocab, grammar);
            } else if (courseId != null) {
                long vocab   = lessonVocabularyRepository.countByCourseId(courseId);
                long grammar = lessonGrammarRepository.countByCourseId(courseId);
                log.debug("countContent (course): courseId={} vocab={} grammar={}", courseId, vocab, grammar);
                return new ContentCount(vocab, grammar);
            }
        } catch (Exception e) {
            log.warn("countContent failed (courseId={}, lessonId={}): {}", courseId, lessonId, e.getMessage());
        }
        return new ContentCount(0, 0);
    }
}

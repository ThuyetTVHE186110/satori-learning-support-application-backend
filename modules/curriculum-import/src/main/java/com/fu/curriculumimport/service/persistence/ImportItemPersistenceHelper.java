package com.fu.curriculumimport.service.persistence;

import com.fu.course.entity.*;
import com.fu.course.entity.Course.ContentStatus;
import com.fu.course.entity.Course.JlptLevel;
import com.fu.course.entity.GrammarPoint.GrammarCategory;
import com.fu.course.repository.*;
import com.fu.curriculumimport.service.agent.ExtractionResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Helper bean for persisting individual items in isolated transactions.
 *
 * Each public method runs in REQUIRES_NEW propagation, meaning it gets its own
 * independent database transaction. If one item fails (constraint violation,
 * type casting error, etc.), only that item's transaction is rolled back —
 * the caller's transaction (or lack thereof) is unaffected.
 *
 * This fixes the PostgreSQL "current transaction is aborted" cascade error
 * where a single failure in a mega-transaction poisons all subsequent queries.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ImportItemPersistenceHelper {

    private final VocabularyRepository vocabularyRepository;
    private final GrammarPointRepository grammarPointRepository;
    private final LessonRepository lessonRepository;
    private final LessonVocabularyRepository lessonVocabularyRepository;
    private final LessonGrammarRepository lessonGrammarRepository;
    private final ObjectMapper objectMapper;

    /**
     * Find existing vocabulary or create a new one — in its own transaction.
     * Returns null on failure (transaction rolls back independently).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Vocabulary findOrCreateVocabulary(ExtractionResult.ExtractedVocabulary ev, UUID createdBy) {
        try {
            JlptLevel level = parseJlptLevel(ev.getJlptLevel());

            return vocabularyRepository
                    .findByJapaneseTextAndJlptLevelAndDeletedAtIsNull(ev.getJapaneseText(), level)
                    .orElseGet(() -> vocabularyRepository.save(Vocabulary.builder()
                            .japaneseText(ev.getJapaneseText())
                            .reading(ev.getReading())
                            .romaji(ev.getRomaji())
                            .meaningVi(ev.getMeaningVi())
                            .meaningEn(ev.getMeaningEn())
                            .partOfSpeech(ev.getPartOfSpeech())
                            .jlptLevel(level)
                            .kanjiWriting(ev.getKanjiWriting())
                            .exampleSentences(serializeJson(ev.getExampleSentences()))
                            .status(ContentStatus.DRAFT)
                            .createdBy(createdBy)
                            .build()));
        } catch (Exception e) {
            log.warn("Failed to persist vocabulary '{}': {}", ev.getJapaneseText(), e.getMessage());
            return null;
        }
    }

    /**
     * Find existing grammar point or create a new one — in its own transaction.
     * Returns null on failure (transaction rolls back independently).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public GrammarPoint findOrCreateGrammarPoint(ExtractionResult.ExtractedGrammar eg, UUID createdBy) {
        try {
            JlptLevel level = parseJlptLevel(eg.getJlptLevel());

            return grammarPointRepository
                    .findByPatternAndJlptLevelAndDeletedAtIsNull(eg.getPattern(), level)
                    .orElseGet(() -> grammarPointRepository.save(GrammarPoint.builder()
                            .pattern(eg.getPattern())
                            .patternReading(eg.getPatternReading())
                            .meaningVi(eg.getMeaningVi())
                            .meaningEn(eg.getMeaningEn())
                            .shortExplanation(eg.getShortExplanationVi())
                            .detailedExplanation(eg.getDetailedExplanationVi())
                            .jlptLevel(level)
                            .category(parseGrammarCategory(eg.getCategory()))
                            .exampleSentences(serializeJson(eg.getExampleSentences()))
                            .commonMistakes(serializeJson(eg.getCommonMistakes()))
                            .status(ContentStatus.DRAFT)
                            .createdBy(createdBy)
                            .build()));
        } catch (Exception e) {
            log.warn("Failed to persist grammar '{}': {}", eg.getPattern(), e.getMessage());
            return null;
        }
    }

    /**
     * Link a vocabulary item to a lesson — in its own transaction.
     * Skips if the link already exists. Returns true on success.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean linkVocabularyToLesson(UUID lessonId, Vocabulary vocab, int orderIndex, boolean isKeyWord) {
        try {
            if (lessonVocabularyRepository.existsByLessonIdAndVocabularyId(lessonId, vocab.getId())) {
                return true;
            }
            Lesson lessonRef = lessonRepository.getReferenceById(lessonId);
            LessonVocabulary lv = LessonVocabulary.builder()
                    .lesson(lessonRef)
                    .vocabulary(vocab)
                    .orderIndex(orderIndex)
                    .isKeyWord(isKeyWord)
                    .build();
            lessonVocabularyRepository.save(lv);
            return true;
        } catch (Exception e) {
            log.warn("Failed to link vocabulary {} to lesson {}: {}", vocab.getId(), lessonId, e.getMessage());
            return false;
        }
    }

    /**
     * Link a grammar point to a lesson — in its own transaction.
     * Skips if the link already exists. Returns true on success.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean linkGrammarToLesson(UUID lessonId, GrammarPoint grammar, int orderIndex, boolean isKeyPoint) {
        try {
            if (lessonGrammarRepository.existsByLessonIdAndGrammarPointId(lessonId, grammar.getId())) {
                return true;
            }
            Lesson lessonRef = lessonRepository.getReferenceById(lessonId);
            LessonGrammar lg = LessonGrammar.builder()
                    .lesson(lessonRef)
                    .grammarPoint(grammar)
                    .orderIndex(orderIndex)
                    .isKeyPoint(isKeyPoint)
                    .build();
            lessonGrammarRepository.save(lg);
            return true;
        } catch (Exception e) {
            log.warn("Failed to link grammar {} to lesson {}: {}", grammar.getId(), lessonId, e.getMessage());
            return false;
        }
    }

    /**
     * Find an existing lesson by (importId, title) or create a new one — in its own transaction.
     *
     * <p>Prevents duplicate lessons when multiple extraction paths (structural chunker +
     * ContentExtractionAgent) independently resolve to the same lesson title for the same import.
     *
     * @return the found-or-created lesson, or null on failure
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Lesson findOrCreateLesson(Lesson lesson, UUID importId) {
        try {
            return lessonRepository
                    .findByImportIdAndTitleIgnoreCase(importId, lesson.getTitle().strip())
                    .orElseGet(() -> lessonRepository.save(lesson));
        } catch (Exception e) {
            log.warn("Failed to save lesson '{}': {}", lesson.getTitle(), e.getMessage());
            return null;
        }
    }

    private JlptLevel parseJlptLevel(String level) {
        try {
            return JlptLevel.valueOf(level != null ? level : "N5");
        } catch (IllegalArgumentException e) {
            return JlptLevel.N5;
        }
    }

    private GrammarCategory parseGrammarCategory(String category) {
        try {
            return GrammarCategory.valueOf(category);
        } catch (Exception e) {
            return GrammarCategory.OTHER;
        }
    }

    private String serializeJson(Object obj) {
        if (obj == null) return "[]";
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}

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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Helper bean for persisting items in isolated batch transactions.
 *
 * Each public method runs in REQUIRES_NEW propagation, meaning it gets its own
 * independent database transaction. Batches of up to 50 items are processed
 * per transaction. If one batch fails, only that batch's transaction is rolled
 * back — the caller's transaction is unaffected.
 *
 * This fixes the PostgreSQL "current transaction is aborted" cascade error
 * while reducing the number of transactions from 300-600 to ~12-24 per import.
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
     * Find an existing lesson by (importId, title) or create a new one — in its own transaction.
     *
     * <p>Prevents duplicate lessons when multiple extraction paths (structural chunker +
     * LessonMetadataParser) independently resolve to the same lesson title for the same import.
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

    // ── Batch methods ───────────────────────────────────────────────────────

    /**
     * Persist a batch of vocabulary items in a single transaction.
     * Returns the list of successfully persisted items (nulls excluded).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Vocabulary> findOrCreateVocabularyBatch(
            List<ExtractionResult.ExtractedVocabulary> items, UUID createdBy) {
        List<Vocabulary> result = new ArrayList<>(items.size());
        for (ExtractionResult.ExtractedVocabulary ev : items) {
            try {
                JlptLevel level = parseJlptLevel(ev.getJlptLevel());
                // Ensure at least one meaning is present (DB constraint: chk_vocabulary_meaning)
                final String safeMeaningVi;
                final String safeMeaningEn = ev.getMeaningEn();
                if ((ev.getMeaningVi() == null || ev.getMeaningVi().isBlank())
                        && (safeMeaningEn == null || safeMeaningEn.isBlank())) {
                    safeMeaningVi = ev.getJapaneseText(); // fallback to the word itself
                } else {
                    safeMeaningVi = ev.getMeaningVi();
                }

                Vocabulary vocab = vocabularyRepository
                        .findByJapaneseTextAndJlptLevelAndDeletedAtIsNull(ev.getJapaneseText(), level)
                        .orElseGet(() -> vocabularyRepository.save(Vocabulary.builder()
                                .japaneseText(ev.getJapaneseText())
                                .reading(ev.getReading())
                                .romaji(ev.getRomaji())
                                .meaningVi(safeMeaningVi)
                                .meaningEn(safeMeaningEn)
                                .partOfSpeech(ev.getPartOfSpeech())
                                .jlptLevel(level)
                                .kanjiWriting(ev.getKanjiWriting())
                                .exampleSentences(serializeJson(ev.getExampleSentences()))
                                .status(ContentStatus.DRAFT)
                                .createdBy(createdBy)
                                .build()));
                result.add(vocab);
            } catch (Exception e) {
                log.warn("Batch: failed to persist vocabulary '{}': {}", ev.getJapaneseText(), e.getMessage());
                result.add(null);
            }
        }
        return result;
    }

    /**
     * Persist a batch of grammar points in a single transaction.
     * Returns the list of successfully persisted items (nulls excluded).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<GrammarPoint> findOrCreateGrammarBatch(
            List<ExtractionResult.ExtractedGrammar> items, UUID createdBy) {
        List<GrammarPoint> result = new ArrayList<>(items.size());
        for (ExtractionResult.ExtractedGrammar eg : items) {
            try {
                JlptLevel level = parseJlptLevel(eg.getJlptLevel());
                // Ensure at least one meaning is present (DB constraint: chk_grammar_meaning)
                final String safeMeaningVi;
                final String safeMeaningEn = eg.getMeaningEn();
                if ((eg.getMeaningVi() == null || eg.getMeaningVi().isBlank())
                        && (safeMeaningEn == null || safeMeaningEn.isBlank())) {
                    safeMeaningVi = eg.getShortExplanationVi() != null ? eg.getShortExplanationVi()
                            : eg.getFunction() != null ? eg.getFunction()
                            : eg.getPattern();
                } else {
                    safeMeaningVi = eg.getMeaningVi();
                }

                GrammarPoint grammar = grammarPointRepository
                        .findByPatternAndJlptLevelAndDeletedAtIsNull(eg.getPattern(), level)
                        .orElseGet(() -> grammarPointRepository.save(GrammarPoint.builder()
                                .pattern(eg.getPattern())
                                .patternReading(eg.getPatternReading())
                                .meaningVi(safeMeaningVi)
                                .meaningEn(safeMeaningEn)
                                .shortExplanation(eg.getShortExplanationVi())
                                .detailedExplanation(eg.getDetailedExplanationVi())
                                .formationRules(eg.getFunction())
                                .tips(eg.getUsageNotes() != null ? String.join("\n", eg.getUsageNotes()) : null)
                                .relatedGrammar(eg.getRelatedTo() != null ? eg.getRelatedTo().toArray(String[]::new) : null)
                                .jlptLevel(level)
                                .category(parseGrammarCategory(eg.getCategory()))
                                .exampleSentences(serializeJson(eg.getExampleSentences()))
                                .commonMistakes(serializeJson(eg.getCommonMistakes()))
                                .status(ContentStatus.DRAFT)
                                .createdBy(createdBy)
                                .build()));
                result.add(grammar);
            } catch (Exception e) {
                log.warn("Batch: failed to persist grammar '{}': {}", eg.getPattern(), e.getMessage());
                result.add(null);
            }
        }
        return result;
    }

    /**
     * Link a batch of vocabulary items to a lesson in a single transaction.
     * Returns the number of successful links.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int linkVocabularyBatchToLesson(UUID lessonId, List<Vocabulary> vocabList,
                                            List<Boolean> keyFlags, int startIndex) {
        int linked = 0;
        Lesson lessonRef = lessonRepository.getReferenceById(lessonId);
        for (int i = 0; i < vocabList.size(); i++) {
            Vocabulary vocab = vocabList.get(i);
            if (vocab == null) continue;
            try {
                if (lessonVocabularyRepository.existsByLessonIdAndVocabularyId(lessonId, vocab.getId())) {
                    linked++;
                    continue;
                }
                LessonVocabulary lv = LessonVocabulary.builder()
                        .lesson(lessonRef)
                        .vocabulary(vocab)
                        .orderIndex(startIndex + i)
                        .isKeyWord(i < keyFlags.size() && keyFlags.get(i))
                        .build();
                lessonVocabularyRepository.save(lv);
                linked++;
            } catch (Exception e) {
                log.warn("Batch: failed to link vocab {} to lesson {}: {}",
                        vocab.getId(), lessonId, e.getMessage());
            }
        }
        return linked;
    }

    /**
     * Link a batch of grammar points to a lesson in a single transaction.
     * Returns the number of successful links.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int linkGrammarBatchToLesson(UUID lessonId, List<GrammarPoint> grammarList,
                                         List<Boolean> keyFlags, int startIndex) {
        int linked = 0;
        Lesson lessonRef = lessonRepository.getReferenceById(lessonId);
        for (int i = 0; i < grammarList.size(); i++) {
            GrammarPoint grammar = grammarList.get(i);
            if (grammar == null) continue;
            try {
                if (lessonGrammarRepository.existsByLessonIdAndGrammarPointId(lessonId, grammar.getId())) {
                    linked++;
                    continue;
                }
                LessonGrammar lg = LessonGrammar.builder()
                        .lesson(lessonRef)
                        .grammarPoint(grammar)
                        .orderIndex(startIndex + i)
                        .isKeyPoint(i < keyFlags.size() && keyFlags.get(i))
                        .build();
                lessonGrammarRepository.save(lg);
                linked++;
            } catch (Exception e) {
                log.warn("Batch: failed to link grammar {} to lesson {}: {}",
                        grammar.getId(), lessonId, e.getMessage());
            }
        }
        return linked;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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

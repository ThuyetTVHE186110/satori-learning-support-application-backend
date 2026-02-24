package com.fu.curriculumimport.service.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.course.entity.*;
import com.fu.course.entity.Course.JlptLevel;
import com.fu.course.entity.GrammarPoint.GrammarCategory;
import com.fu.course.repository.*;
import com.fu.curriculumimport.entity.CurriculumImport;
import com.fu.curriculumimport.entity.ImportChunk;
import com.fu.curriculumimport.repository.CurriculumImportRepository;
import com.fu.curriculumimport.repository.ImportChunkRepository;
import com.fu.curriculumimport.service.agent.ExtractionResult;
import com.fu.curriculumimport.service.ingestion.DocumentChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service handling all transactional persistence operations for the import pipeline.
 *
 * Extracted from ImportOrchestrator to fix the Spring @Transactional proxy issue:
 * Self-invocations bypass Spring AOP proxies, so transactional methods called
 * from within the same bean don't get transaction management.
 *
 * By placing these methods in a separate @Service bean, Spring correctly
 * creates a proxy and all @Transactional annotations work as expected.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ImportPersistenceService {

    private final CurriculumImportRepository importRepository;
    private final ImportChunkRepository chunkRepository;
    private final CourseRepository courseRepository;
    private final LessonRepository lessonRepository;
    private final VocabularyRepository vocabularyRepository;
    private final GrammarPointRepository grammarPointRepository;
    private final LessonVocabularyRepository lessonVocabularyRepository;
    private final LessonGrammarRepository lessonGrammarRepository;
    private final ObjectMapper objectMapper;

    /**
     * Persist document chunks to the database.
     *
     * @param importEntity  the parent import
     * @param documentChunks chunks from PDF parsing
     * @return persisted ImportChunk entities
     */
    @Transactional
    public List<ImportChunk> persistChunks(CurriculumImport importEntity,
                                            List<DocumentChunk> documentChunks) {
        List<ImportChunk> dbChunks = new ArrayList<>();

        for (DocumentChunk docChunk : documentChunks) {
            ImportChunk chunk = ImportChunk.builder()
                    .curriculumImport(importEntity)
                    .chunkIndex(docChunk.getChunkIndex())
                    .content(docChunk.getContent())
                    .chunkType(docChunk.getChunkType())
                    .pageStart(docChunk.getPageStart())
                    .pageEnd(docChunk.getPageEnd())
                    .tokenCount(docChunk.getEstimatedTokenCount())
                    .build();
            dbChunks.add(chunkRepository.save(chunk));
        }

        return dbChunks;
    }

    /**
     * Save an import chunk update.
     */
    @Transactional
    public void saveChunk(ImportChunk chunk) {
        chunkRepository.save(chunk);
    }

    /**
     * Update import entity progress.
     */
    @Transactional
    public void updateImportProgress(CurriculumImport importEntity) {
        importRepository.save(importEntity);
    }

    /**
     * Persist all extracted content (lessons, vocabulary, grammar) to the DB.
     * Handles deduplication for vocabulary and grammar by unique constraints.
     *
     * @param importEntity the parent import
     * @param results      list of extraction results from all chunks
     */
    @Transactional
    public void persistExtractedContent(CurriculumImport importEntity,
                                         List<ExtractionResult> results) {
        int lessonsCount = 0;
        int vocabCount = 0;
        int grammarCount = 0;

        Course course = null;
        if (importEntity.getCourseId() != null) {
            course = courseRepository.findById(importEntity.getCourseId()).orElse(null);
        }

        for (ExtractionResult result : results) {
            // Persist lessons
            for (ExtractionResult.ExtractedLesson el : result.getLessons()) {
                if (course == null) continue;

                Lesson lesson = Lesson.builder()
                        .course(course)
                        .importId(importEntity.getId())
                        .title(el.getTitle())
                        .titleJapanese(el.getTitleJapanese())
                        .description(el.getDescription())
                        .lessonType(parseLessonType(el.getLessonType()))
                        .content(el.getContent())
                        .objectives(el.getObjectives() != null
                                ? el.getObjectives().toArray(new String[0]) : null)
                        .pageStart(el.getPageStart())
                        .pageEnd(el.getPageEnd())
                        .orderIndex(el.getOrderIndex())
                        .processingStatus(Lesson.ProcessingStatus.COMPLETED)
                        .status(Lesson.ContentStatus.DRAFT)
                        .build();

                Lesson savedLesson = lessonRepository.save(lesson);
                lessonsCount++;

                // Link vocabulary
                int vocabOrder = 0;
                for (ExtractionResult.ExtractedVocabulary ev : result.getVocabulary()) {
                    Vocabulary vocab = findOrCreateVocabulary(ev, importEntity.getCreatedBy());
                    if (vocab != null) {
                        if (!lessonVocabularyRepository.existsByLessonIdAndVocabularyId(
                                savedLesson.getId(), vocab.getId())) {
                            LessonVocabulary lv = LessonVocabulary.builder()
                                    .lesson(savedLesson)
                                    .vocabulary(vocab)
                                    .orderIndex(vocabOrder++)
                                    .isKeyWord(ev.isKeyWord())
                                    .build();
                            lessonVocabularyRepository.save(lv);
                        }
                    }
                }

                // Link grammar
                int grammarOrder = 0;
                for (ExtractionResult.ExtractedGrammar eg : result.getGrammarPoints()) {
                    GrammarPoint grammar = findOrCreateGrammarPoint(eg, importEntity.getCreatedBy());
                    if (grammar != null) {
                        if (!lessonGrammarRepository.existsByLessonIdAndGrammarPointId(
                                savedLesson.getId(), grammar.getId())) {
                            LessonGrammar lg = LessonGrammar.builder()
                                    .lesson(savedLesson)
                                    .grammarPoint(grammar)
                                    .orderIndex(grammarOrder++)
                                    .isKeyPoint(eg.isKeyPoint())
                                    .build();
                            lessonGrammarRepository.save(lg);
                        }
                    }
                }
            }

            vocabCount += result.getVocabulary().size();
            grammarCount += result.getGrammarPoints().size();
        }

        importEntity.setLessonsCreated(lessonsCount);
        importEntity.setVocabularyCreated(vocabCount);
        importEntity.setGrammarPointsCreated(grammarCount);
        importRepository.save(importEntity);
    }

    /**
     * Find existing vocabulary or create new one (dedup by japanese_text + jlpt_level).
     */
    private Vocabulary findOrCreateVocabulary(ExtractionResult.ExtractedVocabulary ev, UUID createdBy) {
        try {
            JlptLevel level = JlptLevel.valueOf(ev.getJlptLevel() != null ? ev.getJlptLevel() : "N5");

            return vocabularyRepository
                    .findByJapaneseTextAndJlptLevel(ev.getJapaneseText(), level)
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
                            .createdBy(createdBy)
                            .build()));
        } catch (Exception e) {
            log.warn("Failed to persist vocabulary '{}': {}", ev.getJapaneseText(), e.getMessage());
            return null;
        }
    }

    /**
     * Find existing grammar point or create new one (dedup by pattern + jlpt_level).
     */
    private GrammarPoint findOrCreateGrammarPoint(ExtractionResult.ExtractedGrammar eg, UUID createdBy) {
        try {
            JlptLevel level = JlptLevel.valueOf(eg.getJlptLevel() != null ? eg.getJlptLevel() : "N5");

            return grammarPointRepository
                    .findByPatternAndJlptLevel(eg.getPattern(), level)
                    .orElseGet(() -> grammarPointRepository.save(GrammarPoint.builder()
                            .pattern(eg.getPattern())
                            .patternReading(eg.getPatternReading())
                            .meaningVi(eg.getMeaningVi())
                            .meaningEn(eg.getMeaningEn())
                            .shortExplanation(eg.getShortExplanation())
                            .detailedExplanation(eg.getDetailedExplanation())
                            .jlptLevel(level)
                            .category(parseGrammarCategory(eg.getCategory()))
                            .exampleSentences(serializeJson(eg.getExampleSentences()))
                            .commonMistakes(serializeJson(eg.getCommonMistakes()))
                            .createdBy(createdBy)
                            .build()));
        } catch (Exception e) {
            log.warn("Failed to persist grammar '{}': {}", eg.getPattern(), e.getMessage());
            return null;
        }
    }

    private Lesson.LessonType parseLessonType(String type) {
        try {
            return Lesson.LessonType.valueOf(type);
        } catch (Exception e) {
            return Lesson.LessonType.MIXED;
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

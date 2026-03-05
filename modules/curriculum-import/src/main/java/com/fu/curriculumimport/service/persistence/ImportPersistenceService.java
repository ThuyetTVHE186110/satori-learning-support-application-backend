package com.fu.curriculumimport.service.persistence;

import com.fu.course.entity.*;
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
 *
 * Individual item persistence (vocabulary, grammar, lesson-links) is delegated
 * to ImportItemPersistenceHelper which runs each operation in its own
 * REQUIRES_NEW transaction — preventing the PostgreSQL "current transaction
 * is aborted" cascade error.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ImportPersistenceService {

    private final CurriculumImportRepository importRepository;
    private final ImportChunkRepository chunkRepository;
    private final CourseRepository courseRepository;
    private final LessonRepository lessonRepository;
    private final LessonVocabularyRepository lessonVocabularyRepository;
    private final LessonGrammarRepository lessonGrammarRepository;
    private final ImportItemPersistenceHelper itemHelper;

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
     * Each lesson, vocabulary item, and grammar point is persisted in its own
     * isolated transaction via ImportItemPersistenceHelper (REQUIRES_NEW).
     * If one item fails, the rest continue processing normally.
     *
     * @param importEntity the parent import
     * @param results      list of extraction results from all chunks
     */
    public void persistExtractedContent(CurriculumImport importEntity,
                                         List<ExtractionResult> results) {
        int lessonsCount = 0;
        int vocabCount = 0;
        int grammarCount = 0;
        int failedItems = 0;

        Course course = null;
        if (importEntity.getCourseId() != null) {
            course = courseRepository.findById(importEntity.getCourseId()).orElse(null);
        }

        for (ExtractionResult result : results) {

            // 1. Persist vocabulary independently (not tied to lesson existence)
            List<Vocabulary> persistedVocab = new ArrayList<>();
            for (ExtractionResult.ExtractedVocabulary ev : result.getVocabulary()) {
                Vocabulary vocab = itemHelper.findOrCreateVocabulary(ev, importEntity.getCreatedBy());
                if (vocab != null) {
                    persistedVocab.add(vocab);
                } else {
                    failedItems++;
                }
            }

            // 2. Persist grammar independently (not tied to lesson existence)
            List<GrammarPoint> persistedGrammar = new ArrayList<>();
            for (ExtractionResult.ExtractedGrammar eg : result.getGrammarPoints()) {
                GrammarPoint grammar = itemHelper.findOrCreateGrammarPoint(eg, importEntity.getCreatedBy());
                if (grammar != null) {
                    persistedGrammar.add(grammar);
                } else {
                    failedItems++;
                }
            }

            // 3. Persist lessons and link vocab/grammar to them
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

                Lesson savedLesson = itemHelper.saveLesson(lesson);
                if (savedLesson == null) {
                    log.warn("Skipping lesson '{}' — failed to save", el.getTitle());
                    failedItems++;
                    continue;
                }
                lessonsCount++;

                // Link already-persisted vocabulary to lesson
                int vocabOrder = 0;
                for (int i = 0; i < persistedVocab.size(); i++) {
                    Vocabulary vocab = persistedVocab.get(i);
                    boolean isKeyWord = i < result.getVocabulary().size()
                            && result.getVocabulary().get(i).isKeyWord();
                    boolean linked = itemHelper.linkVocabularyToLesson(
                            savedLesson.getId(), vocab, vocabOrder++, isKeyWord);
                    if (!linked) failedItems++;
                }

                // Link already-persisted grammar to lesson
                int grammarOrder = 0;
                for (int i = 0; i < persistedGrammar.size(); i++) {
                    GrammarPoint grammar = persistedGrammar.get(i);
                    boolean isKeyPoint = i < result.getGrammarPoints().size()
                            && result.getGrammarPoints().get(i).isKeyPoint();
                    boolean linked = itemHelper.linkGrammarToLesson(
                            savedLesson.getId(), grammar, grammarOrder++, isKeyPoint);
                    if (!linked) failedItems++;
                }
            }

            vocabCount += persistedVocab.size();
            grammarCount += persistedGrammar.size();
        }

        if (failedItems > 0) {
            log.warn("Import {} completed with {} failed items", importEntity.getId(), failedItems);
        }

        importEntity.setLessonsCreated(lessonsCount);
        importEntity.setVocabularyCreated(vocabCount);
        importEntity.setGrammarPointsCreated(grammarCount);
        updateImportProgress(importEntity);
    }

    /**
     * Soft-delete all non-deleted lessons that belong to a rejected/rolled-back import.
     * Also hard-deletes the LessonVocabulary and LessonGrammar join records
     * to prevent orphaned rows.
     * Vocabulary and GrammarPoint entities are shared resources and are NOT deleted.
     *
     * @param importId  the import whose lessons should be removed
     * @param managerId the manager performing the rejection/rollback
     */
    @Transactional
    public void softDeleteLessonsByImportId(UUID importId, UUID managerId) {
        List<com.fu.course.entity.Lesson> lessons = lessonRepository.findByImportIdAndNotDeleted(importId);
        int joinRecordsRemoved = 0;
        for (com.fu.course.entity.Lesson lesson : lessons) {
            // Clean up join tables before soft-deleting the lesson
            lessonVocabularyRepository.deleteByLessonId(lesson.getId());
            lessonGrammarRepository.deleteByLessonId(lesson.getId());
            joinRecordsRemoved++;

            lesson.softDelete(managerId);
            lessonRepository.save(lesson);
        }
        log.info("Soft-deleted {} lessons (cleaned up join records for {}) for import {}",
                lessons.size(), joinRecordsRemoved, importId);
    }

    private Lesson.LessonType parseLessonType(String type) {
        try {
            return Lesson.LessonType.valueOf(type);
        } catch (Exception e) {
            return Lesson.LessonType.MIXED;
        }
    }
}

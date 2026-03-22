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

import java.util.*;

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
                    .lessonTitle(docChunk.getLessonTitle())
                    .lessonNumber(docChunk.getLessonNumber())
                    .sectionType(docChunk.getSectionType())
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
        Course course = null;
        if (importEntity.getCourseId() != null) {
            course = courseRepository.findById(importEntity.getCourseId()).orElse(null);
        }

        // Group results by lesson title so that oversized lessons that were split
        // into multiple sub-chunks are merged back into a single lesson entity.
        // Results without a lessonTitle are each treated as their own group (keyed by chunk index).
        LinkedHashMap<String, List<ExtractionResult>> lessonGroups = new LinkedHashMap<>();
        for (ExtractionResult result : results) {
            String groupKey = (result.getLessonTitle() != null && !result.getLessonTitle().isBlank())
                    ? result.getLessonTitle()
                    : "chunk-" + result.getChunkIndex();
            lessonGroups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(result);
        }

        int lessonsCount = 0;
        int vocabCount   = 0;
        int grammarCount = 0;
        int failedItems  = 0;

        for (Map.Entry<String, List<ExtractionResult>> entry : lessonGroups.entrySet()) {
            String groupKey      = entry.getKey();
            List<ExtractionResult> groupResults = entry.getValue();

            // 1. Persist all vocabulary for this lesson group
            List<Vocabulary> persistedVocab = new ArrayList<>();
            List<Boolean>    vocabKeyFlags  = new ArrayList<>();
            for (ExtractionResult result : groupResults) {
                for (ExtractionResult.ExtractedVocabulary ev : result.getVocabulary()) {
                    Vocabulary vocab = itemHelper.findOrCreateVocabulary(ev, importEntity.getCreatedBy());
                    if (vocab != null) {
                        persistedVocab.add(vocab);
                        vocabKeyFlags.add(ev.isKeyWord());
                    } else {
                        failedItems++;
                    }
                }
            }

            // 2. Persist all grammar for this lesson group
            List<GrammarPoint> persistedGrammar  = new ArrayList<>();
            List<Boolean>      grammarKeyFlags    = new ArrayList<>();
            for (ExtractionResult result : groupResults) {
                for (ExtractionResult.ExtractedGrammar eg : result.getGrammarPoints()) {
                    GrammarPoint grammar = itemHelper.findOrCreateGrammarPoint(eg, importEntity.getCreatedBy());
                    if (grammar != null) {
                        persistedGrammar.add(grammar);
                        grammarKeyFlags.add(eg.isKeyPoint());
                    } else {
                        failedItems++;
                    }
                }
            }

            // 3. Determine lessons to create for this group.
            //    Prefer explicit lessons returned by ContentExtractionAgent (MIXED chunks).
            //    VocabularyExtractionAgent / GrammarExtractionAgent always return empty lists,
            //    so we auto-generate one lesson per group using the detected lesson title.
            ExtractionResult firstResult = groupResults.get(0);
            // Collect all AI-generated lessons from sub-chunks then deduplicate by title.
            // Multiple sub-chunks of the same lesson each return a lessons[] entry → without
            // deduplication we'd persist N copies of "Bài 1" for a lesson split into N chunks.
            Map<String, ExtractionResult.ExtractedLesson> uniqueLessonMap = new LinkedHashMap<>();
            for (ExtractionResult gr : groupResults) {
                List<ExtractionResult.ExtractedLesson> grLessons =
                        gr.getLessons() != null ? gr.getLessons() : List.of();
                for (ExtractionResult.ExtractedLesson el : grLessons) {
                    String titleKey = el.getTitle() != null
                            ? el.getTitle().toLowerCase().strip() : "";
                    uniqueLessonMap.putIfAbsent(titleKey, el);
                }
            }
            List<ExtractionResult.ExtractedLesson> lessonsToCreate = new ArrayList<>(uniqueLessonMap.values());

            // When the chunker detected a canonical lesson title (lesson-aware chunking),
            // it is authoritative and overrides whatever the LLM extracted.
            // This prevents hallucinated titles like "Bài 2" for a chunk whose boundary
            // detector clearly identified it as "Bài 1" (common on TOC/intro pages).
            String detectedLessonTitle = firstResult.getLessonTitle();
            if (detectedLessonTitle != null && !detectedLessonTitle.isBlank()
                    && lessonsToCreate.size() == 1) {
                ExtractionResult.ExtractedLesson el = lessonsToCreate.get(0);
                String llmTitle = el.getTitle() != null ? el.getTitle().strip() : "";
                if (!detectedLessonTitle.equalsIgnoreCase(llmTitle)) {
                    log.info("Lesson title override: LLM='{}' → detected='{}' (chunk boundary wins)",
                            llmTitle, detectedLessonTitle);
                    lessonsToCreate = List.of(ExtractionResult.ExtractedLesson.builder()
                            .title(detectedLessonTitle)
                            .titleJapanese(el.getTitleJapanese())
                            .description(el.getDescription())
                            .objectives(el.getObjectives())
                            .pageStart(el.getPageStart())
                            .pageEnd(el.getPageEnd())
                            .orderIndex(firstResult.getLessonNumber() != null
                                    ? firstResult.getLessonNumber() : el.getOrderIndex())
                            .build());
                }
            }

            if (lessonsToCreate.isEmpty()
                    && (!persistedVocab.isEmpty() || !persistedGrammar.isEmpty())
                    && course != null) {

                // Only auto-generate a lesson when the chunk has a structural lesson title
                // (i.e. it was identified by LessonBoundaryDetector). Chunks without a
                // structural title are orphans (appendix pages, OCR-failed pages, etc.) —
                // their vocab/grammar are already persisted to shared tables, so there is
                // nothing useful to add by creating a noisy "Grammar — Chunk N" lesson.
                String detectedTitle = firstResult.getLessonTitle();
                if (detectedTitle == null || detectedTitle.isBlank()) {
                    log.info("Skipping lesson creation for orphan group '{}' ({} vocab, {} grammar) — no structural lesson boundary",
                            groupKey, persistedVocab.size(), persistedGrammar.size());
                } else {
                    int orderIdx = firstResult.getLessonNumber() != null
                            ? firstResult.getLessonNumber()
                            : firstResult.getChunkIndex();

                    lessonsToCreate = List.of(ExtractionResult.ExtractedLesson.builder()
                            .title(detectedTitle)
                            .description("Imported from " + importEntity.getOriginalFilename()
                                    + " — " + detectedTitle)
                            .orderIndex(orderIdx)
                            .pageStart(0)
                            .pageEnd(0)
                            .build());

                    log.info("Auto-generated lesson '{}' for group '{}' ({} vocab, {} grammar)",
                            detectedTitle, groupKey, persistedVocab.size(), persistedGrammar.size());
                }
            }

            // 4. Persist lessons and link vocab/grammar
            for (ExtractionResult.ExtractedLesson el : lessonsToCreate) {
                if (course == null) continue;

                Lesson lesson = Lesson.builder()
                        .course(course)
                        .importId(importEntity.getId())
                        .title(el.getTitle())
                        .titleJapanese(el.getTitleJapanese())
                        .description(el.getDescription())
                        .lessonType(Lesson.LessonType.MIXED)
                        .content(el.getContent())
                        .objectives(el.getObjectives() != null
                                ? el.getObjectives().toArray(new String[0]) : null)
                        .pageStart(el.getPageStart())
                        .pageEnd(el.getPageEnd())
                        .orderIndex(el.getOrderIndex())
                        .processingStatus(Lesson.ProcessingStatus.COMPLETED)
                        .status(Lesson.ContentStatus.DRAFT)
                        .build();

                Lesson savedLesson = itemHelper.findOrCreateLesson(lesson, importEntity.getId());
                if (savedLesson == null) {
                    log.warn("Skipping lesson '{}' — failed to save", el.getTitle());
                    failedItems++;
                    continue;
                }
                lessonsCount++;

                for (int i = 0; i < persistedVocab.size(); i++) {
                    boolean linked = itemHelper.linkVocabularyToLesson(
                            savedLesson.getId(), persistedVocab.get(i), i,
                            i < vocabKeyFlags.size() && vocabKeyFlags.get(i));
                    if (!linked) failedItems++;
                }

                for (int i = 0; i < persistedGrammar.size(); i++) {
                    boolean linked = itemHelper.linkGrammarToLesson(
                            savedLesson.getId(), persistedGrammar.get(i), i,
                            i < grammarKeyFlags.size() && grammarKeyFlags.get(i));
                    if (!linked) failedItems++;
                }
            }

            vocabCount   += persistedVocab.size();
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

}

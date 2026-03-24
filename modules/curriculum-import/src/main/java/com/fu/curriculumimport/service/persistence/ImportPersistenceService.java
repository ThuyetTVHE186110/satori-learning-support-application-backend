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
 * Batch persistence (vocabulary, grammar, lesson-links) is delegated to
 * ImportItemPersistenceHelper which runs batches of 50 items in REQUIRES_NEW
 * transactions — preventing the PostgreSQL "current transaction is aborted"
 * cascade error while minimizing transaction count.
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
     * Persist progress fields from {@code importEntity} to the database.
     *
     * <p>The async pipeline holds a single {@link CurriculumImport} reference for its
     * entire lifetime and calls this method many times. Each call runs in its own
     * transaction (via Spring AOP proxy). To avoid a stale-state merge (Hibernate
     * compares the in-memory entity's id/state against the DB row and throws
     * {@code StaleObjectStateException} when the row was already updated by a
     * different call), we reload the row fresh, copy only the mutable progress fields,
     * and then save the managed instance.
     */
    @Transactional
    public void updateImportProgress(CurriculumImport importEntity) {
        importRepository.findById(importEntity.getId()).ifPresent(fresh -> {
            fresh.setStatus(importEntity.getStatus());
            fresh.setCurrentStep(importEntity.getCurrentStep());
            fresh.setProgressPercent(importEntity.getProgressPercent());
            fresh.setPagesProcessed(importEntity.getPagesProcessed());
            fresh.setChunksCreated(importEntity.getChunksCreated());
            fresh.setVocabularyCreated(importEntity.getVocabularyCreated());
            fresh.setGrammarPointsCreated(importEntity.getGrammarPointsCreated());
            fresh.setLessonsCreated(importEntity.getLessonsCreated());
            fresh.setEmbeddingsCreated(importEntity.getEmbeddingsCreated());
            fresh.setTokensUsed(importEntity.getTokensUsed());
            fresh.setValidationPassed(importEntity.getValidationPassed());
            fresh.setValidationFailed(importEntity.getValidationFailed());
            fresh.setReExtractions(importEntity.getReExtractions());
            fresh.setHallucinatedItems(importEntity.getHallucinatedItems());
            fresh.setStartedAt(importEntity.getStartedAt());
            fresh.setCompletedAt(importEntity.getCompletedAt());
            fresh.setEstimatedCostUsd(importEntity.getEstimatedCostUsd());
            fresh.setActualCostUsd(importEntity.getActualCostUsd());
            fresh.setErrorMessage(importEntity.getErrorMessage());
            fresh.setErrorDetails(importEntity.getErrorDetails());
            importRepository.save(fresh);
        });
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
    @Transactional
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

            // 1. Collect all vocabulary items for this lesson group, deduplicating
            //    by normalized Japanese text to prevent the same word appearing twice
            //    when both table parser and LLM extract it (e.g. 先生 vs せんせい).
            List<ExtractionResult.ExtractedVocabulary> allVocabItems = new ArrayList<>();
            List<Boolean> vocabKeyFlags = new ArrayList<>();
            Set<String> seenNormalizedVocab = new LinkedHashSet<>();
            for (ExtractionResult result : groupResults) {
                for (ExtractionResult.ExtractedVocabulary ev : result.getVocabulary()) {
                    String normKey = normalizeVocabKey(ev.getJapaneseText());
                    if (normKey.isEmpty() || seenNormalizedVocab.add(normKey)) {
                        allVocabItems.add(ev);
                        vocabKeyFlags.add(ev.isKeyWord());
                    } else {
                        log.debug("Dedup: skipping duplicate vocab '{}' (normalized: '{}')",
                                ev.getJapaneseText(), normKey);
                    }
                }
            }

            // Batch persist vocabulary (50 items per transaction)
            List<Vocabulary> persistedVocab = new ArrayList<>();
            int batchSize = 50;
            for (int b = 0; b < allVocabItems.size(); b += batchSize) {
                int end = Math.min(b + batchSize, allVocabItems.size());
                List<Vocabulary> batchResult = itemHelper.findOrCreateVocabularyBatch(
                        allVocabItems.subList(b, end), importEntity.getCreatedBy());
                for (Vocabulary v : batchResult) {
                    if (v != null) {
                        persistedVocab.add(v);
                    } else {
                        failedItems++;
                    }
                }
            }

            // 2. Collect all grammar items for this lesson group, deduplicating by
            //    normalized pattern to prevent near-duplicates like "Danh từ は Danh từ です"
            //    vs "N は N です" from creating separate DB rows.
            List<ExtractionResult.ExtractedGrammar> allGrammarItems = new ArrayList<>();
            List<Boolean> grammarKeyFlags = new ArrayList<>();
            Set<String> seenNormalizedPatterns = new LinkedHashSet<>();
            for (ExtractionResult result : groupResults) {
                for (ExtractionResult.ExtractedGrammar eg : result.getGrammarPoints()) {
                    String normalized = normalizeGrammarPattern(eg.getPattern());
                    if (normalized.isEmpty() || seenNormalizedPatterns.add(normalized)) {
                        allGrammarItems.add(eg);
                        grammarKeyFlags.add(eg.isKeyPoint());
                    } else {
                        log.debug("Dedup: skipping duplicate grammar pattern '{}' (normalized: '{}')",
                                eg.getPattern(), normalized);
                    }
                }
            }

            // Note: removeSubsumedPatterns disabled — the suffix-matching heuristic was
            // too aggressive, removing valid patterns like "N は N ですか" (a real textbook
            // section) when "N か" existed. The normalized-string dedup above is sufficient.

            // Batch persist grammar (50 items per transaction)
            List<GrammarPoint> persistedGrammar = new ArrayList<>();
            for (int b = 0; b < allGrammarItems.size(); b += batchSize) {
                int end = Math.min(b + batchSize, allGrammarItems.size());
                List<GrammarPoint> batchResult = itemHelper.findOrCreateGrammarBatch(
                        allGrammarItems.subList(b, end), importEntity.getCreatedBy());
                for (GrammarPoint g : batchResult) {
                    if (g != null) {
                        persistedGrammar.add(g);
                    } else {
                        failedItems++;
                    }
                }
            }

            // 3. Determine lessons to create for this group.
            //    Prefer explicit lessons returned by LessonMetadataParser (MIXED chunks).
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

                // Batch link vocabulary to lesson (50 items per transaction)
                for (int b = 0; b < persistedVocab.size(); b += batchSize) {
                    int end = Math.min(b + batchSize, persistedVocab.size());
                    List<Boolean> batchFlags = vocabKeyFlags.subList(
                            Math.min(b, vocabKeyFlags.size()),
                            Math.min(end, vocabKeyFlags.size()));
                    int linked = itemHelper.linkVocabularyBatchToLesson(
                            savedLesson.getId(), persistedVocab.subList(b, end), batchFlags, b);
                    failedItems += (end - b) - linked;
                }

                // Batch link grammar to lesson (50 items per transaction)
                for (int b = 0; b < persistedGrammar.size(); b += batchSize) {
                    int end = Math.min(b + batchSize, persistedGrammar.size());
                    List<Boolean> batchFlags = grammarKeyFlags.subList(
                            Math.min(b, grammarKeyFlags.size()),
                            Math.min(end, grammarKeyFlags.size()));
                    int linked = itemHelper.linkGrammarBatchToLesson(
                            savedLesson.getId(), persistedGrammar.subList(b, end), batchFlags, b);
                    failedItems += (end - b) - linked;
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

    // ── Grammar pattern subsumption + normalization for dedup ─────────────────

    /**
     * Remove grammar patterns that are "expanded" specializations of a more abstract
     * pattern already in the list.
     *
     * <p>Examples of subsumption:
     * <ul>
     *   <li>"N は N ですか" is subsumed by "N か" (= Câu か)</li>
     *   <li>"N は N か" is subsumed by "N か"</li>
     *   <li>"N も N です" is subsumed by "N も" (= Danh từ も)</li>
     * </ul>
     *
     * <p>The rule: if pattern A ends with a particle/suffix X, and there exists
     * a shorter pattern B that is just "N X" (or "N + X"), then A is an expansion of B.
     */
    static void removeSubsumedPatterns(List<ExtractionResult.ExtractedGrammar> items,
                                        Set<String> seenNormalized) {
        // Build set of "short" abstract patterns (≤ 2 meaningful tokens after normalization)
        // e.g. "n か", "n も", "n + さん"
        Set<String> shortPatterns = new LinkedHashSet<>();
        for (String np : seenNormalized) {
            String cleaned = np.replace("+", "").replaceAll("\\s+", " ").strip();
            String[] tokens = cleaned.split("\\s+");
            if (tokens.length <= 2) {
                shortPatterns.add(cleaned);
            }
        }
        if (shortPatterns.isEmpty()) return;

        // Check each item: if its normalized pattern ends with the suffix of a short pattern,
        // and it's longer than that short pattern, it's an expansion → remove it.
        var it = items.iterator();
        while (it.hasNext()) {
            ExtractionResult.ExtractedGrammar eg = it.next();
            String norm = normalizeGrammarPattern(eg.getPattern())
                    .replace("+", "").replaceAll("\\s+", " ").strip();
            String[] tokens = norm.split("\\s+");
            if (tokens.length <= 2) continue; // short patterns are never subsumed

            String suffix = tokens[tokens.length - 1]; // e.g. "か", "も", "さん"

            for (String shortP : shortPatterns) {
                String[] shortTokens = shortP.split("\\s+");
                if (shortTokens.length < 2) continue;
                String shortSuffix = shortTokens[shortTokens.length - 1];

                // If the long pattern ends with same suffix as a short "N + suffix" pattern
                if (suffix.equals(shortSuffix) && !norm.equals(shortP)) {
                    log.debug("Dedup subsumption: removing '{}' (normalized: '{}') — "
                                    + "subsumed by short pattern '{}'",
                            eg.getPattern(), norm, shortP);
                    it.remove();
                    seenNormalized.remove(norm);
                    break;
                }
            }
        }
    }

    /**
     * Normalize a grammar pattern string for deduplication comparison.
     *
     * <p>Collapses common naming variations so that patterns like
     * "Danh từ は Danh từ です", "N は N です", "名詞 は 名詞 です"
     * all produce the same normalized key.
     *
     * <p>Normalization steps:
     * <ol>
     *   <li>Lowercase</li>
     *   <li>Strip tilde prefixes (～, 〜)</li>
     *   <li>Replace noun placeholders (Danh từ, danh từ, 名詞, N₁, N₂, noun) → "N"</li>
     *   <li>Collapse whitespace</li>
     * </ol>
     */
    static String normalizeGrammarPattern(String pattern) {
        if (pattern == null || pattern.isBlank()) return "";
        String s = pattern.toLowerCase()
                .replace("～", "").replace("〜", "").replace("~", "")
                .replace("–", "").replace("—", "");
        // Noun placeholders → N
        s = s.replaceAll("(?i)danh\\s*từ", "N")
                .replaceAll("名詞", "N")
                .replaceAll("(?i)noun", "N")
                .replaceAll("(?i)câu", "N")     // "Câu + か" → "N + か"
                .replaceAll("(?i)họ", "N")      // "Họ + さん" → "N + さん"
                .replaceAll("(?i)tên\\s*người", "N") // "Tên người + さん" → "N + さん"
                .replaceAll("N\\s*[₁₂₃₄₅]", "N")   // "N ₁" or "N₁" → "N"
                .replaceAll("n\\s*[₁₂₃₄₅]", "N")
                .replaceAll("N\\s*\\d", "N")           // "N 1", "N1", "N2" → "N"
                .replaceAll("\\bN\\d\\b", "N");
        // Strip standalone subscript digits that remain after replacements
        s = s.replaceAll("[₁₂₃₄₅₆₇₈₉₀]", "");
        // Normalize じゃ ありません → じゃありません (space-insensitive particle matching)
        s = s.replace("じゃ ありません", "じゃありません")
                .replace("では ありません", "ではありません");
        // Strip "+" connector (e.g. "N + さん" → "N さん")
        s = s.replace("+", " ");
        // Collapse whitespace
        s = s.replaceAll("\\s+", " ").strip();
        // Final lowercase to ensure "N" from replacements matches "n" from other paths
        s = s.toLowerCase();
        // Normalize standalone suffixes to "n suffix" form
        // e.g. "さん" → "n さん" to match "Tên người + さん" which normalizes to "n さん"
        if (s.matches("^[\\u3040-\\u30FFー]+$")) {
            s = "n " + s;
        }
        return s;
    }

    /**
     * Normalize a vocabulary Japanese text key for in-memory deduplication.
     *
     * <p>Strips tildes, parentheses, normalizes kana/kanji variants so that
     * entries like "～さん" and "〜さん", or "先生" and "せんせい" (when one is
     * the reading of the other) can be detected as duplicates.
     *
     * <p>Note: this does NOT resolve kanji→kana (e.g. "先生" ≠ "せんせい") because
     * that requires a dictionary lookup. For those cases, the DB-level dedup in
     * {@code ImportItemPersistenceHelper.findOrCreateVocabularyBatch()} handles it
     * via the {@code japaneseText} column match. This method catches:
     * <ul>
     *   <li>Tilde variants: ～さん, 〜さん → さん</li>
     *   <li>Parenthesized forms: (あの方), (おいくつ) → あの方, おいくつ</li>
     *   <li>Whitespace and connector differences</li>
     * </ul>
     */
    static String normalizeVocabKey(String japaneseText) {
        if (japaneseText == null || japaneseText.isBlank()) return "";
        String s = japaneseText.strip()
                .replace("～", "").replace("〜", "")
                .replace("–", "").replace("—", "");
        // Strip leading/trailing parentheses: (あの方) → あの方
        s = s.replaceAll("^\\((.+)\\)$", "$1");
        // Collapse whitespace
        s = s.replaceAll("\\s+", " ").strip();
        return s;
    }

}

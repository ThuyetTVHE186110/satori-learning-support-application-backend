package com.fu.curriculumimport.service.orchestrator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.course.repository.CourseRepository;
import com.fu.curriculumimport.config.AgenticRagProperties;
import com.fu.curriculumimport.entity.CurriculumImport;
import com.fu.curriculumimport.entity.ImportChunk;
import com.fu.curriculumimport.repository.CurriculumImportRepository;
import com.fu.curriculumimport.repository.ImportChunkRepository;
import com.fu.curriculumimport.service.agent.ClassificationResult;
import com.fu.curriculumimport.service.agent.ClassificationResult.ContentType;
import com.fu.curriculumimport.service.agent.ContentClassifierAgent;
import com.fu.curriculumimport.service.agent.ContentValidationAgent;
import com.fu.curriculumimport.service.agent.ExtractionResult;
import com.fu.curriculumimport.service.agent.GrammarExtractionAgent;
import com.fu.curriculumimport.service.agent.ValidationResult;
import com.fu.curriculumimport.service.agent.VocabularyExtractionAgent;
import com.fu.curriculumimport.service.embedding.EmbeddingService;
import com.fu.curriculumimport.service.embedding.RagContext;
import com.fu.curriculumimport.service.ingestion.DocumentChunk;
import com.fu.curriculumimport.service.ingestion.DocumentParseResult;
import com.fu.curriculumimport.service.ingestion.FileIngestionRouter;
import com.fu.curriculumimport.service.ingestion.LessonMetadataParser;
import com.fu.curriculumimport.service.ingestion.OcrTextPreprocessor;
import com.fu.curriculumimport.service.persistence.ImportPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Import Orchestrator — Full Agentic RAG Pipeline Coordinator.
 *
 * Implements the Orchestrator-Worker pattern with 7 pipeline stages:
 *
 * 1. EXTRACTING:    PDF → Text extraction via PDFBox
 * 2. CHUNKING:      Text → Sliding-window chunks (2000 chars, 200 overlap)
 * 3. CLASSIFYING:   ContentClassifierAgent → routes to specialized extractors
 * 4. AI_PROCESSING: Multi-agent extraction with RAG context injection
 *    - VocabularyExtractionAgent (VOCABULARY chunks)
 *    - GrammarExtractionAgent (GRAMMAR chunks)
 *    - Both agents in parallel for MIXED chunks + LessonMetadataParser for lesson metadata
 * 5. VALIDATING:    ContentValidationAgent (Corrective RAG loop)
 *    - If confidence < threshold → re-extract (up to maxReExtractions)
 * 6. EMBEDDING:     EmbeddingService → pgvector storage for future RAG retrieval
 * 7. PERSISTING:    Save lessons/vocabulary/grammar to DB (deduplicated)
 *
 * Features:
 * - Parallel chunk processing via CompletableFuture
 * - Cancel support via volatile flag + interrupt
 * - Corrective RAG loop with validation feedback
 * - RAG retrieval context injection before extraction
 * - Proper @Transactional via ImportPersistenceService
 *
 * References:
 * - Orchestrator-Worker Pattern (Anthropic, 2024)
 * - Corrective RAG (Yan et al., 2024)
 * - Agentic RAG (Singh, 2024)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ImportOrchestrator {

    private final FileIngestionRouter fileIngestionRouter;
    private final ContentClassifierAgent classifierAgent;
    private final VocabularyExtractionAgent vocabularyAgent;
    private final GrammarExtractionAgent grammarAgent;
    private final ContentValidationAgent validationAgent;
    private final EmbeddingService embeddingService;
    private final ImportPersistenceService persistenceService;
    private final CurriculumImportRepository importRepository;
    private final ImportChunkRepository chunkRepository;
    private final CourseRepository courseRepository;
    private final AgenticRagProperties ragProperties;
    private final ObjectMapper objectMapper;
    private final Executor importTaskExecutor;

    /**
     * Tracks active import futures for cancel support.
     */
    private final Map<UUID, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    /**
     * Global semaphore limiting concurrent LLM calls across all imports.
     * Prevents OpenAI rate limit exhaustion when multiple imports run simultaneously.
     * Initialized lazily after Spring injects ragProperties.
     */
    private volatile java.util.concurrent.Semaphore llmSemaphore;

    private java.util.concurrent.Semaphore getLlmSemaphore() {
        if (llmSemaphore == null) {
            synchronized (this) {
                if (llmSemaphore == null) {
                    llmSemaphore = new java.util.concurrent.Semaphore(
                            ragProperties.getMaxConcurrentLlmCalls());
                }
            }
        }
        return llmSemaphore;
    }

    /**
     * Process an import asynchronously — full Agentic RAG pipeline.
     * Main entry point triggered after file upload.
     *
     * @param importId the curriculum import ID
     * @param file     the uploaded PDF file
     */
    @Async("importTaskExecutor")
    public void processImportAsync(UUID importId, MultipartFile file) {
        log.info("Starting Agentic RAG import pipeline for: {}", importId);

        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        cancelFlags.put(importId, cancelFlag);

        CurriculumImport importEntity = importRepository.findById(importId)
                .orElseThrow(() -> new IllegalStateException("Import not found: " + importId));

        try {
            importEntity.markStarted();
            persistenceService.updateImportProgress(importEntity);

            // === STAGE 1: FILE INGESTION (PDF text / PDF scanned OCR / PPTX) ===
            checkCancelled(cancelFlag, importId);
            log.info("[{}] Stage 1/7: Ingesting file '{}' (auto-routing by type)…",
                    importId, file.getOriginalFilename());
            importEntity.updateProgress("EXTRACTING", new BigDecimal("5"));
            persistenceService.updateImportProgress(importEntity);

            DocumentParseResult parseResult = fileIngestionRouter.route(file);
            importEntity.setTotalPages(parseResult.getTotalPages());
            persistenceService.updateImportProgress(importEntity);

            // === STAGE 2: CHUNKING ===
            checkCancelled(cancelFlag, importId);
            log.info("[{}] Stage 2/7: Chunking document ({} chunks)...", importId, parseResult.getChunks().size());
            importEntity.updateProgress("CHUNKING", new BigDecimal("10"));
            persistenceService.updateImportProgress(importEntity);

            List<ImportChunk> dbChunks = persistenceService.persistChunks(importEntity, parseResult.getChunks());
            importEntity.setChunksCreated(dbChunks.size());
            persistenceService.updateImportProgress(importEntity);

            // Determine JLPT level
            String jlptLevel = resolveJlptLevel(importEntity);

            // === STAGE 3: CLASSIFICATION ===
            checkCancelled(cancelFlag, importId);
            log.info("[{}] Stage 3/7: Classifying {} chunks...", importId, dbChunks.size());
            importEntity.updateProgress("CLASSIFYING", new BigDecimal("15"));
            persistenceService.updateImportProgress(importEntity);

            // Heuristic classification — 0 LLM calls, all synchronous.
            // Structural sectionType → content-based regex → default MIXED.
            Map<Integer, ClassificationResult> classifications = new HashMap<>();

            for (int i = 0; i < parseResult.getChunks().size(); i++) {
                final DocumentChunk docChunk = parseResult.getChunks().get(i);
                final ImportChunk dbChunk = dbChunks.get(i);

                ClassificationResult classification = classifierAgent.heuristicClassify(docChunk);
                classifications.put(docChunk.getChunkIndex(), classification);
                dbChunk.setContentType(classification.getContentType().name());
                persistenceService.saveChunk(dbChunk);
            }

            log.info("[{}] Classified {} chunks via heuristics (0 LLM calls)", importId, dbChunks.size());

            // === STAGE 4: AI PROCESSING (Multi-Agent with RAG) ===
            checkCancelled(cancelFlag, importId);
            log.info("[{}] Stage 4/7: AI extraction with specialized agents...", importId);
            importEntity.updateProgress("AI_PROCESSING", new BigDecimal("25"));
            persistenceService.updateImportProgress(importEntity);

            List<ExtractionResult> allResults = new ArrayList<>();
            AtomicInteger totalTokens = new AtomicInteger(0);

            if (ragProperties.isParallelProcessing()) {
                allResults = processChunksParallel(
                        parseResult.getChunks(), dbChunks, classifications,
                        jlptLevel, importId, importEntity, totalTokens, cancelFlag);
            } else {
                allResults = processChunksSequential(
                        parseResult.getChunks(), dbChunks, classifications,
                        jlptLevel, importId, importEntity, totalTokens, cancelFlag);
            }

            // === STAGE 5: VALIDATION (Corrective RAG) ===
            checkCancelled(cancelFlag, importId);
            log.info("[{}] Stage 5/7: Validating extracted content (Corrective RAG)...", importId);
            importEntity.updateProgress("VALIDATING", new BigDecimal("75"));
            persistenceService.updateImportProgress(importEntity);

            int validationPassed = 0;
            int validationFailed = 0;
            int reExtractions = 0;
            int hallucinatedItems = 0;

            if (ragProperties.isValidationEnabled()) {
                // Pre-build index maps for O(1) lookup during validation loop
                Map<Integer, ImportChunk> dbChunkIndex = buildChunkIndexMap(dbChunks,
                        c -> c.getChunkIndex() != null ? c.getChunkIndex() : -1);
                Map<Integer, DocumentChunk> docChunkIndex = buildChunkIndexMap(parseResult.getChunks(),
                        DocumentChunk::getChunkIndex);

                for (int i = 0; i < allResults.size(); i++) {
                    checkCancelled(cancelFlag, importId);
                    ExtractionResult result = allResults.get(i);
                    ImportChunk dbChunk = findChunkByIndex(dbChunkIndex, result.getChunkIndex());
                    DocumentChunk sourceDocChunk = findDocChunkByIndex(docChunkIndex, result.getChunkIndex());

                    // Skip validation for trivially-skipped chunks (cover pages, separators)
                    if (dbChunk != null && "SKIPPED_TRIVIAL".equals(dbChunk.getValidationStatus())) {
                        validationPassed++;
                        continue;
                    }

                    // Skip validation for fast-path results (parser-extracted, 0 LLM tokens).
                    // MnNTableParser (confidence=0.95) and MarkdownVocabParser (confidence=0.90)
                    // produce deterministic output — Self-Reflection LLM calls are wasted tokens.
                    if (result.getTokensUsed() == 0 && result.getConfidence() >= 0.90) {
                        if (dbChunk != null) {
                            dbChunk.setValidationStatus("SKIPPED_FAST_PATH");
                            persistenceService.saveChunk(dbChunk);
                        }
                        validationPassed++;
                        log.debug("[{}] Chunk {} skipped validation — fast-path (confidence={}, tokens=0)",
                                importId, result.getChunkIndex(), result.getConfidence());
                        continue;
                    }

                    // 3-Layer Validation: Rule-Based + Self-Reflection + Grounding
                    ValidationResult validation = validationAgent.validateFull(
                            result, ragProperties.getConfidenceThreshold(),
                            sourceDocChunk, jlptLevel);

                    if (dbChunk != null) {
                        dbChunk.setValidationStatus(validation.getStatus().name());
                        dbChunk.setValidationDetails(serializeJson(validation));

                        // Track hallucination data from grounding verification
                        if (validation.getReflectionResult() != null) {
                            dbChunk.setReflectionScore(
                                    java.math.BigDecimal.valueOf(validation.getReflectionResult().getReflectionScore()));
                            dbChunk.setGroundingScore(
                                    java.math.BigDecimal.valueOf(validation.getReflectionResult().getGroundingScore()));
                            dbChunk.setHallucinatedCount(validation.getHallucinatedItemCount());
                            hallucinatedItems += validation.getHallucinatedItemCount();
                        }
                    }

                    if (validation.isNeedsReExtraction() &&
                            (dbChunk == null || dbChunk.getReExtractionCount() < ragProperties.getMaxReExtractions())) {

                        // Corrective RAG with 3-layer feedback: re-extract with combined hint
                        log.info("[{}] Chunk {} needs re-extraction (combined: {}, grounding: {}, hallucinated: {})",
                                importId, result.getChunkIndex(),
                                validation.getCombinedQualityScore(),
                                validation.getReflectionResult() != null
                                        ? validation.getReflectionResult().getGroundingScore() : "N/A",
                                validation.getHallucinatedItemCount());

                        DocumentChunk docChunk = findDocChunkByIndex(docChunkIndex, result.getChunkIndex());
                        if (docChunk != null) {
                            ContentType contentType = classifications.containsKey(result.getChunkIndex())
                                    ? classifications.get(result.getChunkIndex()).getContentType()
                                    : ContentType.MIXED;

                            RagContext ragContext = ragProperties.isRagRetrievalEnabled()
                                    ? embeddingService.retrieveContext(docChunk.getContent())
                                    : RagContext.empty();

                            // Use combined 3-layer feedback for re-extraction
                            String reExtractionFeedback = validation.getReExtractionHint();

                            ExtractionResult reExtracted = reExtractWithFeedback(
                                    docChunk, contentType, jlptLevel, ragContext, reExtractionFeedback);

                            // Replace strategy: use re-extraction when confidence is equal or higher.
                            // Per-section extraction (confidence=0.92) re-produces the same items
                            // with slightly different formatting (e.g. subscript digits vs regular),
                            // causing mergeNewItems to add duplicates. Replace avoids this.
                            if (reExtracted.getConfidence() >= result.getConfidence()) {
                                allResults.set(i, reExtracted);
                            } else {
                                // Only merge when re-extraction has lower confidence — preserves
                                // original grounded items while adding genuinely new ones.
                                mergeNewItems(result, reExtracted);
                            }
                            totalTokens.addAndGet(reExtracted.getTokensUsed());
                            reExtractions++;

                            if (dbChunk != null) {
                                dbChunk.setReExtractionCount(dbChunk.getReExtractionCount() + 1);
                            }
                        }
                        validationFailed++;
                    } else {
                        validationPassed++;
                    }

                    if (dbChunk != null) {
                        persistenceService.saveChunk(dbChunk);
                    }
                }
            } else {
                validationPassed = allResults.size();
            }

            importEntity.setValidationPassed(validationPassed);
            importEntity.setValidationFailed(validationFailed);
            importEntity.setReExtractions(reExtractions);
            importEntity.setHallucinatedItems(hallucinatedItems);

            // === STAGE 6: PERSISTENCE (before embedding to prevent orphan embeddings) ===
            checkCancelled(cancelFlag, importId);
            log.info("[{}] Stage 6/7: Persisting extracted content...", importId);
            importEntity.updateProgress("PERSISTING", new BigDecimal("85"));
            persistenceService.updateImportProgress(importEntity);

            // persistExtractedContent returns groupKey → lessonId so the embedding stage
            // can write lesson_id into vector metadata without any secondary lookup.
            // This works for both lesson-aware chunking (lessonTitle groupKey) and
            // character-based chunking (chunk-N groupKey), where lessonNumber is absent.
            Map<String, UUID> groupKeyToLessonId =
                    persistenceService.persistExtractedContent(importEntity, allResults);

            // === STAGE 7: EMBEDDING (after persistence — no orphan risk) ===
            checkCancelled(cancelFlag, importId);
            log.info("[{}] Stage 7/7: Generating vector embeddings...", importId);
            importEntity.updateProgress("EMBEDDING", new BigDecimal("92"));
            persistenceService.updateImportProgress(importEntity);

            UUID courseId = importEntity.getCourseId();

            int embeddingsCreated = 0;
            if (ragProperties.isEmbeddingEnabled()) {
                for (ExtractionResult result : allResults) {
                    checkCancelled(cancelFlag, importId);
                    // Derive the same groupKey used by persistExtractedContent to look up
                    // the lesson that was just saved for this result's chunk group.
                    String groupKey = (result.getLessonTitle() != null && !result.getLessonTitle().isBlank())
                            ? result.getLessonTitle()
                            : "chunk-" + result.getChunkIndex();
                    UUID lessonId = groupKeyToLessonId.get(groupKey);
                    embeddingsCreated += embeddingService.storeVocabularyEmbeddings(
                            result.getVocabulary(), importId, lessonId, courseId);
                    embeddingsCreated += embeddingService.storeGrammarEmbeddings(
                            result.getGrammarPoints(), importId, lessonId, courseId);
                }
            }
            // === STAGE 7b: EMBED LESSON CHUNKS cho chatbot RAG ===
            // Embed GRAMMAR_EXPLANATION, DIALOG, EXAMPLE chunks → chatbot có thể tìm kiếm
            // trong nội dung bài học, không chỉ vocab/grammar đã extract.
            if (ragProperties.isEmbeddingEnabled()) {
                // Build lessonTitle → lessonId from the same map for chunk-level embedding.
                Map<String, UUID> lessonTitleToId = groupKeyToLessonId.entrySet().stream()
                        .filter(e -> !e.getKey().startsWith("chunk-"))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
                embeddingsCreated += embedChatbotChunks(dbChunks, importEntity, jlptLevel, lessonTitleToId);
            }
            importEntity.setEmbeddingsCreated(embeddingsCreated);

            // === PENDING APPROVAL ===
            importEntity.setTokensUsed(totalTokens.get());
            importEntity.setActualCostUsd(estimateCost(totalTokens.get()));
            importEntity.markPendingApproval();
            persistenceService.updateImportProgress(importEntity);

            log.info("[{}] ✅ Import ready for manager approval! Lessons: {}, Vocab: {}, Grammar: {}, " +
                            "Embeddings: {}, Validations: {}/{}, Re-extractions: {}, Hallucinated: {}",
                    importId,
                    importEntity.getLessonsCreated(),
                    importEntity.getVocabularyCreated(),
                    importEntity.getGrammarPointsCreated(),
                    embeddingsCreated,
                    validationPassed, validationPassed + validationFailed,
                    reExtractions,
                    hallucinatedItems);

        } catch (CancelledException e) {
            log.info("[{}] Import cancelled by user", importId);
            importEntity.markCancelled();
            persistenceService.updateImportProgress(importEntity);
        } catch (Exception e) {
            log.error("[{}] Import failed: {}", importId, e.getMessage(), e);
            importEntity.markFailed(e.getMessage());
            persistenceService.updateImportProgress(importEntity);
        } finally {
            cancelFlags.remove(importId);
        }
    }

    /**
     * Process a retry import (triggered by scheduler).
     * Re-reads chunks from DB and re-processes.
     */
    @Async("importTaskExecutor")
    public void processRetryAsync(UUID importId) {
        log.info("Starting retry import processing for: {}", importId);

        CurriculumImport importEntity = importRepository.findById(importId)
                .orElseThrow(() -> new IllegalStateException("Import not found: " + importId));

        try {
            importEntity.markStarted();
            persistenceService.updateImportProgress(importEntity);

            // Re-use existing chunks from DB
            List<ImportChunk> existingChunks = chunkRepository
                    .findByCurriculumImportIdOrderByChunkIndexAsc(importId);

            if (existingChunks.isEmpty()) {
                importEntity.markFailed("No chunks found for retry - original file data lost");
                persistenceService.updateImportProgress(importEntity);
                return;
            }

            String jlptLevel = resolveJlptLevel(importEntity);

            // Convert DB chunks back to DocumentChunks for processing (preserving lesson metadata).
            // Note: fullPageContext is set to content (same as char-based chunks) because the
            // original page context is not stored in the DB. structuredTableRows and pageImages
            // are not available on retry — the text LLM path will be used for those chunks.
            List<DocumentChunk> docChunks = existingChunks.stream()
                    .map(c -> DocumentChunk.builder()
                            .chunkIndex(c.getChunkIndex())
                            .content(c.getContent())
                            .fullPageContext(c.getContent()) // best-effort: no page context on retry
                            .pageStart(c.getPageStart() != null ? c.getPageStart() : 0)
                            .pageEnd(c.getPageEnd() != null ? c.getPageEnd() : 0)
                            .chunkType(c.getChunkType())
                            .estimatedTokenCount(c.getTokenCount() != null ? c.getTokenCount() : 0)
                            .lessonTitle(c.getLessonTitle())
                            .lessonNumber(c.getLessonNumber())
                            .sectionType(c.getSectionType())
                            .build())
                    .toList();

            // Re-classify and re-process
            AtomicBoolean cancelFlag = new AtomicBoolean(false);
            cancelFlags.put(importId, cancelFlag);

            Map<Integer, ClassificationResult> classifications = new HashMap<>();
            for (int i = 0; i < docChunks.size(); i++) {
                ImportChunk dbChunk = existingChunks.get(i);
                // Skip chunks that already succeeded — avoids re-billing for a partial retry.
                if (dbChunk.getStatus() == ImportChunk.ChunkStatus.COMPLETED) {
                    // Preserve the existing classification so processChunks* can route correctly.
                    ContentType existingType = dbChunk.getContentType() != null
                            ? ContentType.valueOf(dbChunk.getContentType())
                            : ContentType.OTHER;
                    classifications.put(dbChunk.getChunkIndex(),
                            ClassificationResult.builder()
                                    .contentType(existingType)
                                    .confidence(1.0)
                                    .reasoning("retry-skip-completed")
                                    .build());
                    continue;
                }
                DocumentChunk docChunk = docChunks.get(i);
                ClassificationResult classification = classifierAgent.classify(docChunk);
                // Key by chunkIndex (not loop index) — consistent with main pipeline
                classifications.put(docChunk.getChunkIndex(), classification);
                dbChunk.setContentType(classification.getContentType().name());
                dbChunk.setStatus(ImportChunk.ChunkStatus.PENDING);
                persistenceService.saveChunk(dbChunk);
            }

            importEntity.updateProgress("AI_PROCESSING", new BigDecimal("30"));
            persistenceService.updateImportProgress(importEntity);

            AtomicInteger totalTokens = new AtomicInteger(0);
            List<ExtractionResult> allResults;
            if (ragProperties.isParallelProcessing()) {
                allResults = processChunksParallel(
                        docChunks, existingChunks, classifications,
                        jlptLevel, importId, importEntity, totalTokens, cancelFlag);
            } else {
                allResults = processChunksSequential(
                        docChunks, existingChunks, classifications,
                        jlptLevel, importId, importEntity, totalTokens, cancelFlag);
            }

            // Persist
            importEntity.updateProgress("PERSISTING", new BigDecimal("90"));
            persistenceService.updateImportProgress(importEntity);
            persistenceService.persistExtractedContent(importEntity, allResults);

            importEntity.setTokensUsed(totalTokens.get());
            importEntity.setActualCostUsd(estimateCost(totalTokens.get()));
            importEntity.markPendingApproval();
            persistenceService.updateImportProgress(importEntity);

            log.info("[{}] Retry import ready for manager approval!", importId);

        } catch (Exception e) {
            log.error("[{}] Retry import failed: {}", importId, e.getMessage(), e);
            importEntity.markFailed(e.getMessage());
            persistenceService.updateImportProgress(importEntity);
        } finally {
            cancelFlags.remove(importId);
        }
    }

    /**
     * Request cancellation of an active import.
     *
     * @param importId the import to cancel
     * @return true if the cancel flag was set
     */
    public boolean requestCancel(UUID importId) {
        AtomicBoolean flag = cancelFlags.get(importId);
        if (flag != null) {
            flag.set(true);
            log.info("Cancel requested for import: {}", importId);
            return true;
        }
        return false;
    }

    // ==========================================================================
    // PRIVATE METHODS
    // ==========================================================================

    /**
     * Process chunks in parallel using CompletableFuture.
     *
     * <p>Results are collected into a {@link ConcurrentHashMap} keyed by
     * {@code chunkIndex} to guarantee ordering is preserved regardless of
     * thread scheduling. The final list is sorted by chunkIndex before return.
     */
    private List<ExtractionResult> processChunksParallel(
            List<DocumentChunk> docChunks, List<ImportChunk> dbChunks,
            Map<Integer, ClassificationResult> classifications, String jlptLevel,
            UUID importId, CurriculumImport importEntity,
            AtomicInteger totalTokens, AtomicBoolean cancelFlag) {

        // keyed by chunkIndex → guarantees correct ordering after parallel writes
        ConcurrentHashMap<Integer, ExtractionResult> resultMap = new ConcurrentHashMap<>();
        int batchSize = ragProperties.getParallelBatchSize();

        // Pre-build index map for O(1) lookup
        Map<Integer, ImportChunk> dbChunkIndex = buildChunkIndexMap(dbChunks,
                c -> c.getChunkIndex() != null ? c.getChunkIndex() : -1);

        for (int batchStart = 0; batchStart < docChunks.size(); batchStart += batchSize) {
            if (cancelFlag.get()) break;

            int batchEnd = Math.min(batchStart + batchSize, docChunks.size());
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (int i = batchStart; i < batchEnd; i++) {
                final int idx = i;
                final DocumentChunk docChunk = docChunks.get(idx);
                // Defensive: find dbChunk by chunkIndex rather than positional index
                final ImportChunk dbChunk = findChunkByIndex(dbChunkIndex, docChunk.getChunkIndex());
                if (dbChunk == null) {
                    log.warn("[{}] No DB chunk found for docChunk index {}, skipping", importId, idx);
                    continue;
                }
                final ClassificationResult classification = classifications.getOrDefault(
                        docChunk.getChunkIndex(), ClassificationResult.fallback());

                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        if (cancelFlag.get()) return;
                        // Acquire semaphore to limit concurrent LLM calls
                        getLlmSemaphore().acquire();
                        try {
                            ExtractionResult result = processOneChunk(
                                    docChunk, dbChunk, classification, jlptLevel, importId);
                            // Key by chunkIndex — preserves order regardless of thread timing
                            resultMap.put(docChunk.getChunkIndex(), result);
                            totalTokens.addAndGet(result.getTokensUsed());
                        } finally {
                            getLlmSemaphore().release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("[{}] Chunk {} interrupted waiting for LLM semaphore", importId, idx);
                    } catch (Exception e) {
                        log.error("[{}] Parallel chunk {} failed: {}", importId, idx, e.getMessage());
                        dbChunk.markFailed(e.getMessage());
                        persistenceService.saveChunk(dbChunk);
                    }
                }, importTaskExecutor);

                futures.add(future);
            }

            // Wait for batch to complete with a configurable per-batch timeout.
            // Per-section grammar extraction: up to 6 sections × ~30 s per chunk,
            // batch size 3 → worst-case ~3 min + OpenAI retries → needs >5 min headroom.
            int batchTimeoutMinutes = ragProperties.getBatchTimeoutMinutes();
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(batchTimeoutMinutes, TimeUnit.MINUTES);
            } catch (TimeoutException e) {
                log.error("[{}] Batch starting at chunk {} timed out after {} minutes",
                        importId, batchStart, batchTimeoutMinutes);
                futures.forEach(f -> f.cancel(true));
            } catch (ExecutionException e) {
                log.error("[{}] Batch execution error: {}", importId, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[{}] Batch processing interrupted", importId);
                break;
            }

            // Update progress
            int progressPercent = 25 + (int) ((double) Math.min(batchEnd, docChunks.size())
                    / docChunks.size() * 50);
            importEntity.updateProgress("AI_PROCESSING", new BigDecimal(progressPercent));
            persistenceService.updateImportProgress(importEntity);
        }

        // Return results sorted by chunkIndex to match the original chunk ordering
        return resultMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Process chunks sequentially (fallback mode).
     */
    private List<ExtractionResult> processChunksSequential(
            List<DocumentChunk> docChunks, List<ImportChunk> dbChunks,
            Map<Integer, ClassificationResult> classifications, String jlptLevel,
            UUID importId, CurriculumImport importEntity,
            AtomicInteger totalTokens, AtomicBoolean cancelFlag) {

        List<ExtractionResult> results = new ArrayList<>();

        for (int i = 0; i < docChunks.size(); i++) {
            if (cancelFlag.get()) break;

            DocumentChunk docChunk = docChunks.get(i);
            ImportChunk dbChunk = dbChunks.get(i);
            ClassificationResult classification = classifications.getOrDefault(
                    docChunk.getChunkIndex(), ClassificationResult.fallback());

            try {
                ExtractionResult result = processOneChunk(
                        docChunk, dbChunk, classification, jlptLevel, importId);
                results.add(result);
                totalTokens.addAndGet(result.getTokensUsed());

                // Update progress
                int progressPercent = 25 + (int) ((i + 1.0) / docChunks.size() * 50);
                importEntity.updateProgress("AI_PROCESSING", new BigDecimal(progressPercent));
                importEntity.setPagesProcessed(docChunk.getPageEnd());
                persistenceService.updateImportProgress(importEntity);

            } catch (Exception e) {
                log.error("[{}] Chunk {} processing failed: {}", importId, i, e.getMessage());
                dbChunk.markFailed(e.getMessage());
                persistenceService.saveChunk(dbChunk);
            }
        }

        return results;
    }

    /**
     * Process a single chunk through classification-aware extraction with RAG context.
     */
    private ExtractionResult processOneChunk(DocumentChunk docChunk, ImportChunk dbChunk,
                                              ClassificationResult classification, String jlptLevel,
                                              UUID importId) {
        // On retry, skip chunks that already completed successfully.
        if (dbChunk.getStatus() == ImportChunk.ChunkStatus.COMPLETED) {
            log.debug("[{}] Skipping already-COMPLETED chunk {}", importId, docChunk.getChunkIndex());
            return ExtractionResult.empty(docChunk.getChunkIndex(), 0);
        }

        dbChunk.setStatus(ImportChunk.ChunkStatus.PROCESSING);
        persistenceService.saveChunk(dbChunk);

        // ── Trivial-content guard ───────────────────────────────────────────
        // Apply OCR noise cleaning first (handles chunks stored before the cleaner
        // was introduced, e.g. during retry runs), then decide if there is enough
        // meaningful text to warrant any AI call.
        //
        // Cover/separator pages like "Lorhoctiengnhat.com / Bài 9 / 60 / I."
        // become trivially small after cleaning and MUST be skipped here — without
        // this guard the AI hallucinates plausible-looking content (e.g. "Bài 12")
        // with confidence=1.0, which passes validation and pollutes the DB.
        String cleanedContent = OcrTextPreprocessor.cleanOcrPage(docChunk.getContent());
        if (OcrTextPreprocessor.isTrivial(cleanedContent)) {
            log.info("[{}] Chunk {} skipped — trivial content after cleaning ({} chars raw)",
                    importId, docChunk.getChunkIndex(),
                    docChunk.getContent() == null ? 0 : docChunk.getContent().length());
            ExtractionResult skipped = buildSkippedResult(docChunk.getChunkIndex());
            dbChunk.markCompleted(serializeResult(skipped), BigDecimal.ONE, 0);
            dbChunk.setValidationStatus("SKIPPED_TRIVIAL");
            persistenceService.saveChunk(dbChunk);
            return skipped;
        }
        // Use cleaned content for all downstream agents
        docChunk.setContent(cleanedContent);

        // RAG Retrieval: find existing similar content for context
        RagContext ragContext = ragProperties.isRagRetrievalEnabled()
                ? embeddingService.retrieveContext(docChunk.getContent())
                : RagContext.empty();

        // Route to specialized agent based on classification
        ExtractionResult result = routeToAgent(docChunk, classification.getContentType(),
                jlptLevel, ragContext);

        // Propagate lesson metadata from the source chunk so the persistence layer
        // can group sub-chunks of the same lesson and generate correct titles.
        // lessonTitle and lessonNumber are propagated independently:
        // - lessonTitle drives grouping and lesson creation
        // - lessonNumber must always be set so Lesson.orderIndex gets the structural
        //   lesson number (1, 2, 3…) instead of falling back to chunkIndex.
        //   Without this, getLessonIdsByNumber returns {chunkIndex → lessonId} and
        //   the embedding lookup by lessonNumber misses every entry.
        if (docChunk.getLessonTitle() != null) {
            result.setLessonTitle(docChunk.getLessonTitle());
        }
        if (docChunk.getLessonNumber() != null) {
            result.setLessonNumber(docChunk.getLessonNumber());
        }

        // Update chunk with result
        dbChunk.markCompleted(
                serializeResult(result),
                BigDecimal.valueOf(result.getConfidence()),
                (int) result.getProcessingTimeMs()
        );
        dbChunk.setTokenCount(result.getTokensUsed());
        persistenceService.saveChunk(dbChunk);

        log.debug("[{}] Chunk {} processed: {} vocab, {} grammar (confidence: {})",
                importId, docChunk.getChunkIndex(),
                result.getVocabulary().size(), result.getGrammarPoints().size(),
                result.getConfidence());

        return result;
    }

    /**
     * Route chunk to appropriate specialized agent based on content type.
     *
     * <p>MIXED chunks are handled by calling BOTH specialized agents (vocabulary + grammar)
     * in parallel and merging their results. Lesson metadata is parsed from markdown
     * headers by {@link LessonMetadataParser} (0 LLM calls).
     */
    private ExtractionResult routeToAgent(DocumentChunk chunk, ContentType contentType,
                                           String jlptLevel, RagContext ragContext) {
        return switch (contentType) {
            case VOCABULARY -> vocabularyAgent.extractVocabulary(chunk, jlptLevel, ragContext);
            case GRAMMAR -> grammarAgent.extractGrammar(chunk, jlptLevel, ragContext);
            case EXERCISE, OTHER -> ExtractionResult.builder()
                    .chunkIndex(chunk.getChunkIndex())
                    .confidence(1.0)
                    .tokensUsed(0)
                    .processingTimeMs(0)
                    .lessons(Collections.emptyList())
                    .vocabulary(Collections.emptyList())
                    .grammarPoints(Collections.emptyList())
                    .build();
            case MIXED -> extractWithBothAgents(chunk, jlptLevel, ragContext);
            // CONVERSATION chunks (dialogs) — extract vocab only, not grammar.
            // Grammar from dialogs duplicates the authoritative GRAMMAR_EXPLANATION extraction.
            case CONVERSATION -> vocabularyAgent.extractVocabulary(chunk, jlptLevel, ragContext);
            default -> extractWithBothAgents(chunk, jlptLevel, ragContext);
        };
    }

    /**
     * Extract using BOTH specialized agents for MIXED/CONVERSATION chunks.
     * Runs vocab + grammar agents IN PARALLEL to avoid timeout.
     * Lesson metadata is parsed directly from markdown headers (0 LLM calls).
     */
    private ExtractionResult extractWithBothAgents(DocumentChunk chunk, String jlptLevel,
                                                     RagContext ragContext) {
        long t0 = System.currentTimeMillis();

        // Run both specialized agents in parallel — they are independent LLM calls.
        // IMPORTANT: Do NOT use importTaskExecutor here — the batch processor already holds
        // threads from that pool. Submitting to the same pool causes thread starvation/deadlock.
        // ForkJoinPool.commonPool() (default for supplyAsync) is fine for IO-bound API calls.
        CompletableFuture<ExtractionResult> vocabFuture = CompletableFuture.supplyAsync(
                () -> vocabularyAgent.extractVocabulary(chunk, jlptLevel, ragContext));
        CompletableFuture<ExtractionResult> grammarFuture = CompletableFuture.supplyAsync(
                () -> grammarAgent.extractGrammar(chunk, jlptLevel, ragContext));

        // Lesson metadata: parse from markdown headers — 0 API calls
        ExtractionResult.ExtractedLesson parsedLesson = LessonMetadataParser.parseFromChunk(chunk);
        List<ExtractionResult.ExtractedLesson> lessons = parsedLesson != null
                ? List.of(parsedLesson) : Collections.emptyList();

        // Wait for both agents to complete
        ExtractionResult vocabResult;
        ExtractionResult grammarResult;
        try {
            vocabResult = vocabFuture.get(4, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("VocabularyAgent timed out/failed for chunk {}: {}", chunk.getChunkIndex(), e.getMessage());
            vocabResult = ExtractionResult.empty(chunk.getChunkIndex(), 0);
        }
        try {
            grammarResult = grammarFuture.get(4, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("GrammarAgent timed out/failed for chunk {}: {}", chunk.getChunkIndex(), e.getMessage());
            grammarResult = ExtractionResult.empty(chunk.getChunkIndex(), 0);
        }

        // Merge: vocab from vocabAgent, grammar from grammarAgent, lessons from parser
        double mergedConfidence = Math.min(vocabResult.getConfidence(), grammarResult.getConfidence());
        int totalTokens = vocabResult.getTokensUsed() + grammarResult.getTokensUsed();

        return ExtractionResult.builder()
                .chunkIndex(chunk.getChunkIndex())
                .confidence(mergedConfidence > 0 ? mergedConfidence : 0.9)
                .tokensUsed(totalTokens)
                .processingTimeMs(System.currentTimeMillis() - t0)
                .lessons(lessons)
                .vocabulary(vocabResult.getVocabulary())
                .grammarPoints(grammarResult.getGrammarPoints())
                .build();
    }

    /**
     * Merge new items from re-extraction into the original result without removing
     * existing grounded items. Prevents data loss when re-extraction is more conservative.
     */
    private void mergeNewItems(ExtractionResult original, ExtractionResult reExtracted) {
        // Merge new vocab items not already present
        java.util.Set<String> existingVocab = new java.util.HashSet<>();
        for (var v : original.getVocabulary()) {
            if (v.getJapaneseText() != null) existingVocab.add(v.getJapaneseText());
        }
        for (var v : reExtracted.getVocabulary()) {
            if (v.getJapaneseText() != null && !existingVocab.contains(v.getJapaneseText())) {
                original.getVocabulary().add(v);
            }
        }

        // Merge new grammar points not already present
        java.util.Set<String> existingGrammar = new java.util.HashSet<>();
        for (var g : original.getGrammarPoints()) {
            if (g.getPattern() != null) existingGrammar.add(g.getPattern());
        }
        for (var g : reExtracted.getGrammarPoints()) {
            if (g.getPattern() != null && !existingGrammar.contains(g.getPattern())) {
                original.getGrammarPoints().add(g);
            }
        }
    }

    /**
     * Re-extract with validation feedback (Corrective RAG loop).
     */
    private ExtractionResult reExtractWithFeedback(DocumentChunk chunk, ContentType contentType,
                                                     String jlptLevel, RagContext ragContext,
                                                     String feedback) {
        return switch (contentType) {
            case VOCABULARY, CONVERSATION -> vocabularyAgent.reExtractWithFeedback(chunk, jlptLevel, ragContext, feedback);
            case GRAMMAR -> grammarAgent.reExtractWithFeedback(chunk, jlptLevel, ragContext, feedback);
            default -> {
                // For MIXED: re-extract with both specialized agents
                ExtractionResult vocabResult = vocabularyAgent.reExtractWithFeedback(
                        chunk, jlptLevel, ragContext, feedback);
                ExtractionResult grammarResult = grammarAgent.reExtractWithFeedback(
                        chunk, jlptLevel, ragContext, feedback);
                yield ExtractionResult.builder()
                        .chunkIndex(chunk.getChunkIndex())
                        .confidence(Math.min(vocabResult.getConfidence(), grammarResult.getConfidence()))
                        .tokensUsed(vocabResult.getTokensUsed() + grammarResult.getTokensUsed())
                        .processingTimeMs(vocabResult.getProcessingTimeMs() + grammarResult.getProcessingTimeMs())
                        .lessons(Collections.emptyList())
                        .vocabulary(vocabResult.getVocabulary())
                        .grammarPoints(grammarResult.getGrammarPoints())
                        .build();
            }
        };
    }

    /**
     * Resolve JLPT level from associated course.
     */
    private String resolveJlptLevel(CurriculumImport importEntity) {
        if (importEntity.getCourseId() == null) return "N5";
        return courseRepository.findById(importEntity.getCourseId())
                .map(c -> c.getJlptLevel().name())
                .orElse("N5");
    }

    /**
     * Check if import has been cancelled, throw if so.
     */
    private void checkCancelled(AtomicBoolean cancelFlag, UUID importId) {
        if (cancelFlag.get()) {
            throw new CancelledException("Import " + importId + " was cancelled");
        }
    }

    /**
     * Build index map for O(1) chunk lookup by chunkIndex.
     */
    private static <T> Map<Integer, T> buildChunkIndexMap(List<T> chunks,
                                                            java.util.function.ToIntFunction<T> indexExtractor) {
        Map<Integer, T> map = new HashMap<>(chunks.size());
        for (T chunk : chunks) {
            map.put(indexExtractor.applyAsInt(chunk), chunk);
        }
        return map;
    }

    private ImportChunk findChunkByIndex(Map<Integer, ImportChunk> indexMap, int index) {
        return indexMap.get(index);
    }

    private DocumentChunk findDocChunkByIndex(Map<Integer, DocumentChunk> indexMap, int index) {
        return indexMap.get(index);
    }

    private String serializeResult(ExtractionResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private String serializeJson(Object obj) {
        if (obj == null) return "{}";
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    /**
     * Estimate cost based on tokens used.
     * GPT-4o-mini: ~$0.15/1M input + $0.60/1M output ≈ $0.30/1M avg
     */
    private BigDecimal estimateCost(int tokensUsed) {
        return BigDecimal.valueOf(tokensUsed * 0.0000003);
    }

    /**
     * Build an extraction result for a trivially-skipped chunk.
     *
     * <p>Uses {@code confidence=1.0} so the validation loop does not treat this
     * as a "low-confidence" result and attempt re-extraction. The result carries
     * no vocabulary or grammar — it represents a chunk that was determined to
     * contain no learnable content (cover page, separator, OCR-noise page).
     */
    private ExtractionResult buildSkippedResult(int chunkIndex) {
        return ExtractionResult.builder()
                .chunkIndex(chunkIndex)
                .confidence(1.0)
                .tokensUsed(0)
                .processingTimeMs(0)
                .lessons(Collections.emptyList())
                .vocabulary(Collections.emptyList())
                .grammarPoints(Collections.emptyList())
                .build();
    }

    /**
     * Embed các ImportChunk có section_type hữu ích cho chatbot vào content_embeddings.
     * Section types được embed: GRAMMAR_EXPLANATION, DIALOG, EXAMPLE.
     * VOCABULARY và GRAMMAR_PATTERN đã được embed riêng qua storeVocabularyEmbeddings/storeGrammarEmbeddings.
     * Non-fatal: lỗi chỉ log, không ảnh hưởng pipeline chính.
     *
     * @param dbChunks     danh sách ImportChunk đã persist
     * @param importEntity entity CurriculumImport hiện tại
     * @param jlptLevel    cấp độ JLPT của import
     */
    private int embedChatbotChunks(List<ImportChunk> dbChunks,
                                    CurriculumImport importEntity,
                                    String jlptLevel,
                                    Map<String, UUID> lessonTitleToId) {
        if (dbChunks == null || dbChunks.isEmpty()) return 0;

        UUID courseId = importEntity.getCourseId();
        Set<String> chatbotSectionTypes = Set.of(
                "GRAMMAR_EXPLANATION", "DIALOG", "EXAMPLE");

        List<ImportChunk> chatbotChunks = dbChunks.stream()
                .filter(c -> c.getSectionType() != null
                        && chatbotSectionTypes.contains(c.getSectionType())
                        && c.getContent() != null
                        && !c.getContent().isBlank())
                .toList();

        if (chatbotChunks.isEmpty()) {
            log.debug("[{}] Không có lesson chunk nào cần embed cho chatbot", importEntity.getId());
            return 0;
        }

        log.info("[{}] Embedding {} lesson chunks cho chatbot (GRAMMAR_EXPLANATION/DIALOG/EXAMPLE)...",
                importEntity.getId(), chatbotChunks.size());

        int embedded = 0;
        for (ImportChunk chunk : chatbotChunks) {
            UUID lessonId = chunk.getLessonTitle() != null
                    ? lessonTitleToId.get(chunk.getLessonTitle())
                    : null;
            embeddingService.embedLessonChunk(chunk, courseId, lessonId, jlptLevel);
            embedded++;
        }

        log.info("[{}] Đã embed {} lesson chunks cho chatbot RAG", importEntity.getId(), embedded);
        return embedded;
    }

    /**
     * Custom exception for cancelled imports.
     */
    private static class CancelledException extends RuntimeException {
        public CancelledException(String message) {
            super(message);
        }
    }
}

package com.fu.curriculumimport.service.orchestrator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.course.repository.CourseRepository;
import com.fu.curriculumimport.config.AgenticRagProperties;
import com.fu.curriculumimport.entity.CurriculumImport;
import com.fu.curriculumimport.entity.ImportChunk;
import com.fu.curriculumimport.repository.CurriculumImportRepository;
import com.fu.curriculumimport.repository.ImportChunkRepository;
import com.fu.curriculumimport.service.agent.*;
import com.fu.curriculumimport.service.agent.ClassificationResult.ContentType;
import com.fu.curriculumimport.service.embedding.EmbeddingService;
import com.fu.curriculumimport.service.embedding.RagContext;
import com.fu.curriculumimport.service.ingestion.DocumentChunk;
import com.fu.curriculumimport.service.ingestion.DocumentParseResult;
import com.fu.curriculumimport.service.ingestion.FileIngestionRouter;
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
 *    - ContentExtractionAgent (MIXED/OTHER chunks — fallback)
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
    private final ContentExtractionAgent extractionAgent;
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

            // Use ConcurrentHashMap — classification futures write from multiple threads
            Map<Integer, ClassificationResult> classifications = new ConcurrentHashMap<>();
            List<CompletableFuture<Void>> classifyFutures = new ArrayList<>();

            for (int i = 0; i < parseResult.getChunks().size(); i++) {
                final int idx = i;
                final DocumentChunk docChunk = parseResult.getChunks().get(idx);
                final ImportChunk dbChunk = dbChunks.get(idx);

                classifyFutures.add(CompletableFuture.runAsync(() -> {
                    if (cancelFlag.get()) return;
                    ClassificationResult classification = classifierAgent.classify(docChunk);
                    classifications.put(idx, classification);
                    dbChunk.setContentType(classification.getContentType().name());
                    persistenceService.saveChunk(dbChunk);
                    log.debug("[{}] Chunk {} classified as {} (confidence: {})",
                            importId, idx, classification.getContentType(), classification.getConfidence());
                }, importTaskExecutor));
            }

            try {
                CompletableFuture.allOf(classifyFutures.toArray(new CompletableFuture[0]))
                        .get(10, TimeUnit.MINUTES);
            } catch (TimeoutException e) {
                log.error("[{}] Classification timed out — proceeding with partial results", importId);
            } catch (ExecutionException e) {
                log.error("[{}] Classification error: {}", importId, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancelledException("Import " + importId + " interrupted during classification");
            }

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
                for (int i = 0; i < allResults.size(); i++) {
                    checkCancelled(cancelFlag, importId);
                    ExtractionResult result = allResults.get(i);
                    ImportChunk dbChunk = findChunkByIndex(dbChunks, result.getChunkIndex());
                    DocumentChunk sourceDocChunk = findDocChunkByIndex(parseResult.getChunks(), result.getChunkIndex());

                    // Skip validation for trivially-skipped chunks (cover pages, separators)
                    if (dbChunk != null && "SKIPPED_TRIVIAL".equals(dbChunk.getValidationStatus())) {
                        validationPassed++;
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

                        DocumentChunk docChunk = findDocChunkByIndex(parseResult.getChunks(), result.getChunkIndex());
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

                            if (reExtracted.getConfidence() > result.getConfidence()) {
                                allResults.set(i, reExtracted);
                                totalTokens.addAndGet(reExtracted.getTokensUsed());
                            }
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

            // === STAGE 6: EMBEDDING ===
            checkCancelled(cancelFlag, importId);
            log.info("[{}] Stage 6/7: Generating vector embeddings...", importId);
            importEntity.updateProgress("EMBEDDING", new BigDecimal("85"));
            persistenceService.updateImportProgress(importEntity);

            int embeddingsCreated = 0;
            if (ragProperties.isEmbeddingEnabled()) {
                for (ExtractionResult result : allResults) {
                    checkCancelled(cancelFlag, importId);
                    embeddingsCreated += embeddingService.storeVocabularyEmbeddings(
                            result.getVocabulary(), importId);
                    embeddingsCreated += embeddingService.storeGrammarEmbeddings(
                            result.getGrammarPoints(), importId);
                }
            }
            importEntity.setEmbeddingsCreated(embeddingsCreated);

            // === STAGE 7: PERSISTENCE ===
            checkCancelled(cancelFlag, importId);
            log.info("[{}] Stage 7/7: Persisting extracted content...", importId);
            importEntity.updateProgress("PERSISTING", new BigDecimal("92"));
            persistenceService.updateImportProgress(importEntity);

            persistenceService.persistExtractedContent(importEntity, allResults);

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

            // Convert DB chunks back to DocumentChunks for processing (preserving lesson metadata)
            List<DocumentChunk> docChunks = existingChunks.stream()
                    .map(c -> DocumentChunk.builder()
                            .chunkIndex(c.getChunkIndex())
                            .content(c.getContent())
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
                ClassificationResult classification = classifierAgent.classify(docChunks.get(i));
                classifications.put(i, classification);
                existingChunks.get(i).setContentType(classification.getContentType().name());
                existingChunks.get(i).setStatus(ImportChunk.ChunkStatus.PENDING);
                persistenceService.saveChunk(existingChunks.get(i));
            }

            importEntity.updateProgress("AI_PROCESSING", new BigDecimal("30"));
            persistenceService.updateImportProgress(importEntity);

            AtomicInteger totalTokens = new AtomicInteger(0);
            List<ExtractionResult> allResults = processChunksSequential(
                    docChunks, existingChunks, classifications,
                    jlptLevel, importId, importEntity, totalTokens, cancelFlag);

            // Persist
            importEntity.updateProgress("PERSISTING", new BigDecimal("90"));
            persistenceService.updateImportProgress(importEntity);
            persistenceService.persistExtractedContent(importEntity, allResults);

            importEntity.setTokensUsed(totalTokens.get());
            importEntity.setActualCostUsd(estimateCost(totalTokens.get()));
            importEntity.markCompleted();
            persistenceService.updateImportProgress(importEntity);

            log.info("[{}] Retry import completed!", importId);

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

        for (int batchStart = 0; batchStart < docChunks.size(); batchStart += batchSize) {
            if (cancelFlag.get()) break;

            int batchEnd = Math.min(batchStart + batchSize, docChunks.size());
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (int i = batchStart; i < batchEnd; i++) {
                final int idx = i;
                final DocumentChunk docChunk = docChunks.get(idx);
                // Defensive: find dbChunk by chunkIndex rather than positional index
                final ImportChunk dbChunk = findChunkByIndex(dbChunks, docChunk.getChunkIndex());
                if (dbChunk == null) {
                    log.warn("[{}] No DB chunk found for docChunk index {}, skipping", importId, idx);
                    continue;
                }
                final ClassificationResult classification = classifications.getOrDefault(idx,
                        ClassificationResult.fallback());

                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        if (cancelFlag.get()) return;
                        ExtractionResult result = processOneChunk(
                                docChunk, dbChunk, classification, jlptLevel, importId);
                        // Key by chunkIndex — preserves order regardless of thread timing
                        resultMap.put(docChunk.getChunkIndex(), result);
                        totalTokens.addAndGet(result.getTokensUsed());
                    } catch (Exception e) {
                        log.error("[{}] Parallel chunk {} failed: {}", importId, idx, e.getMessage());
                        dbChunk.markFailed(e.getMessage());
                        persistenceService.saveChunk(dbChunk);
                    }
                }, importTaskExecutor);

                futures.add(future);
            }

            // Wait for batch to complete (with 5 minute timeout per batch)
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(5, TimeUnit.MINUTES);
            } catch (TimeoutException e) {
                log.error("[{}] Batch starting at chunk {} timed out after 5 minutes", importId, batchStart);
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
            ClassificationResult classification = classifications.getOrDefault(i,
                    ClassificationResult.fallback());

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
        if (docChunk.getLessonTitle() != null) {
            result.setLessonTitle(docChunk.getLessonTitle());
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
            default -> extractionAgent.extractContent(chunk, jlptLevel, ragContext); // MIXED, CONVERSATION
        };
    }

    /**
     * Re-extract with validation feedback (Corrective RAG loop).
     */
    private ExtractionResult reExtractWithFeedback(DocumentChunk chunk, ContentType contentType,
                                                     String jlptLevel, RagContext ragContext,
                                                     String feedback) {
        return switch (contentType) {
            case VOCABULARY -> vocabularyAgent.reExtractWithFeedback(chunk, jlptLevel, ragContext, feedback);
            case GRAMMAR -> grammarAgent.reExtractWithFeedback(chunk, jlptLevel, ragContext, feedback);
            default -> extractionAgent.extractContent(chunk, jlptLevel, ragContext); // fallback with RAG
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

    private ImportChunk findChunkByIndex(List<ImportChunk> chunks, int index) {
        return chunks.stream()
                .filter(c -> c.getChunkIndex() != null && c.getChunkIndex() == index)
                .findFirst().orElse(null);
    }

    private DocumentChunk findDocChunkByIndex(List<DocumentChunk> chunks, int index) {
        return chunks.stream()
                .filter(c -> c.getChunkIndex() == index)
                .findFirst().orElse(null);
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
     * Custom exception for cancelled imports.
     */
    private static class CancelledException extends RuntimeException {
        public CancelledException(String message) {
            super(message);
        }
    }
}

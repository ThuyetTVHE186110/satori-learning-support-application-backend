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
import com.fu.curriculumimport.service.ingestion.PdfIngestionService;
import com.fu.curriculumimport.service.persistence.ImportPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
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

    private final PdfIngestionService pdfIngestionService;
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

            // === STAGE 1: PDF EXTRACTION ===
            checkCancelled(cancelFlag, importId);
            log.info("[{}] Stage 1/7: Extracting text from PDF...", importId);
            importEntity.updateProgress("EXTRACTING", new BigDecimal("5"));
            persistenceService.updateImportProgress(importEntity);

            DocumentParseResult parseResult = pdfIngestionService.parsePdf(file);
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

            Map<Integer, ClassificationResult> classifications = new HashMap<>();
            for (int i = 0; i < parseResult.getChunks().size(); i++) {
                checkCancelled(cancelFlag, importId);
                DocumentChunk docChunk = parseResult.getChunks().get(i);
                ImportChunk dbChunk = dbChunks.get(i);

                ClassificationResult classification = classifierAgent.classify(docChunk);
                classifications.put(i, classification);

                // Update chunk with classification
                dbChunk.setContentType(classification.getContentType().name());
                persistenceService.saveChunk(dbChunk);

                log.debug("[{}] Chunk {} classified as {} (confidence: {})",
                        importId, i, classification.getContentType(), classification.getConfidence());
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

            // === COMPLETE ===
            importEntity.setTokensUsed(totalTokens.get());
            importEntity.setActualCostUsd(estimateCost(totalTokens.get()));
            importEntity.markCompleted();
            persistenceService.updateImportProgress(importEntity);

            log.info("[{}] ✅ Import completed! Lessons: {}, Vocab: {}, Grammar: {}, " +
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

            // Convert DB chunks back to DocumentChunks for processing
            List<DocumentChunk> docChunks = existingChunks.stream()
                    .map(c -> DocumentChunk.builder()
                            .chunkIndex(c.getChunkIndex())
                            .content(c.getContent())
                            .pageStart(c.getPageStart() != null ? c.getPageStart() : 0)
                            .pageEnd(c.getPageEnd() != null ? c.getPageEnd() : 0)
                            .chunkType(c.getChunkType())
                            .estimatedTokenCount(c.getTokenCount() != null ? c.getTokenCount() : 0)
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
     */
    private List<ExtractionResult> processChunksParallel(
            List<DocumentChunk> docChunks, List<ImportChunk> dbChunks,
            Map<Integer, ClassificationResult> classifications, String jlptLevel,
            UUID importId, CurriculumImport importEntity,
            AtomicInteger totalTokens, AtomicBoolean cancelFlag) {

        List<ExtractionResult> results = Collections.synchronizedList(new ArrayList<>());
        int batchSize = ragProperties.getParallelBatchSize();

        for (int batchStart = 0; batchStart < docChunks.size(); batchStart += batchSize) {
            if (cancelFlag.get()) break;

            int batchEnd = Math.min(batchStart + batchSize, docChunks.size());
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (int i = batchStart; i < batchEnd; i++) {
                final int idx = i;
                final DocumentChunk docChunk = docChunks.get(idx);
                final ImportChunk dbChunk = dbChunks.get(idx);
                final ClassificationResult classification = classifications.getOrDefault(idx,
                        ClassificationResult.fallback());

                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        if (cancelFlag.get()) return;
                        ExtractionResult result = processOneChunk(
                                docChunk, dbChunk, classification, jlptLevel, importId);
                        results.add(result);
                        totalTokens.addAndGet(result.getTokensUsed());
                    } catch (Exception e) {
                        log.error("[{}] Parallel chunk {} failed: {}", importId, idx, e.getMessage());
                        dbChunk.markFailed(e.getMessage());
                        persistenceService.saveChunk(dbChunk);
                    }
                }, importTaskExecutor);

                futures.add(future);
            }

            // Wait for batch to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // Update progress
            int progressPercent = 25 + (int) ((double) Math.min(batchEnd, docChunks.size())
                    / docChunks.size() * 50);
            importEntity.updateProgress("AI_PROCESSING", new BigDecimal(progressPercent));
            persistenceService.updateImportProgress(importEntity);
        }

        return new ArrayList<>(results);
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

        // RAG Retrieval: find existing similar content for context
        RagContext ragContext = ragProperties.isRagRetrievalEnabled()
                ? embeddingService.retrieveContext(docChunk.getContent())
                : RagContext.empty();

        // Route to specialized agent based on classification
        ExtractionResult result = routeToAgent(docChunk, classification.getContentType(),
                jlptLevel, ragContext);

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
            default -> extractionAgent.extractContent(chunk, jlptLevel); // MIXED, CONVERSATION
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
            default -> extractionAgent.extractContent(chunk, jlptLevel); // fallback
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
     * Custom exception for cancelled imports.
     */
    private static class CancelledException extends RuntimeException {
        public CancelledException(String message) {
            super(message);
        }
    }
}

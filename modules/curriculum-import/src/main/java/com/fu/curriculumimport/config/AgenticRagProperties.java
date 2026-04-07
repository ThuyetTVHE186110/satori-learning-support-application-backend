package com.fu.curriculumimport.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the Agentic RAG pipeline.
 * Maps to `curriculum-import.agentic-rag` in application.yml.
 */
@Data
@Slf4j
@Component
@ConfigurationProperties(prefix = "curriculum-import.agentic-rag")
public class AgenticRagProperties {

    /**
     * Minimum confidence threshold for extraction results.
     * Below this, re-extraction is triggered (Corrective RAG).
     */
    private double confidenceThreshold = 0.6;

    /**
     * Maximum re-extraction attempts per chunk.
     */
    private int maxReExtractions = 2;

    /**
     * Number of similar items to retrieve for RAG context.
     */
    private int ragTopK = 5;

    /**
     * Minimum cosine similarity for RAG retrieval results.
     */
    private double ragSimilarityThreshold = 0.7;

    /**
     * Whether to enable parallel chunk processing.
     */
    private boolean parallelProcessing = true;

    /**
     * Max chunks to process in parallel.
     */
    private int parallelBatchSize = 5;

    /**
     * Enable embedding generation after extraction.
     */
    private boolean embeddingEnabled = true;

    /**
     * Enable content validation step.
     */
    private boolean validationEnabled = true;

    /**
     * Enable RAG context retrieval before extraction.
     */
    private boolean ragRetrievalEnabled = true;

    /**
     * Retry scheduler fixed delay in milliseconds.
     */
    private long retrySchedulerDelayMs = 30000;

    // ==================== Thread Pool Configuration ====================

    /**
     * Core pool size for import task executor.
     * Default 4 — handles classification + extraction batches concurrently.
     */
    private int threadPoolCoreSize = 4;

    /**
     * Max pool size for import task executor.
     * Default 8 — allows burst capacity for large textbooks.
     */
    private int threadPoolMaxSize = 8;

    /**
     * Queue capacity for import task executor.
     * Default 50 — prevents CallerRunsPolicy from blocking the main import thread.
     */
    private int threadPoolQueueCapacity = 50;

    // ==================== Self-Reflection (Self-RAG) ====================

    /**
     * Enable LLM-based self-reflection (Self-RAG).
     * When enabled, the LLM critiques its own extraction output
     * using reflection tokens: [IsRelevant], [IsSupported], [IsUseful].
     */
    private boolean selfReflectionEnabled = true;

    /**
     * Max source content chars to include in reflection prompt.
     * Prevents context window overflow for large chunks.
     */
    private int selfReflectionMaxSourceChars = 4000;

    // ==================== Grounding Verification (CRAG) ====================

    /**
     * Enable source grounding verification (anti-hallucination).
     * Cross-references extracted content against source text.
     */
    private boolean groundingEnabled = true;

    /**
     * Minimum grounding confidence threshold.
     * Items below this are flagged as potentially hallucinated.
     */
    private double groundingThreshold = 0.4;

    // ==================== Combined Scoring Weights ====================

    /**
     * Weight for rule-based validation in combined score.
     * Must sum to 1.0 with reflectionWeight and groundingWeight.
     */
    private double ruleBasedWeight = 0.30;

    /**
     * Weight for LLM self-reflection in combined score.
     */
    private double reflectionWeight = 0.35;

    /**
     * Weight for source grounding in combined score.
     */
    private double groundingWeight = 0.35;

    /**
     * Combined score threshold below which re-extraction is triggered.
     */
    private double combinedReExtractionThreshold = 0.5;

    /**
     * Maximum concurrent LLM calls across all imports.
     * Acts as a global semaphore to prevent OpenAI rate limit exhaustion
     * when multiple imports run in parallel.
     * Default 6 — allows 2 concurrent imports with batch size 3 each.
     */
    private int maxConcurrentLlmCalls = 6;

    /**
     * Per-batch timeout in minutes for parallel chunk processing.
     * Per-section grammar extraction can take 6 × ~30 s = 3 min per chunk;
     * with batch size 3 and OpenAI retry, worst case is ~10 min.
     * Default 15 — provides ample headroom without masking true hangs.
     */
    private int batchTimeoutMinutes = 15;

    /**
     * Minutes an import can stay in a processing state before being considered
     * stuck and eligible for retry. Must exceed the longest expected processing
     * time for the largest supported document.
     * A 200-page textbook: OCR ~21 min + AI ~60 min = ~80 min total.
     * Default 120 — prevents false-positive retry triggers for large imports.
     */
    private int stuckImportThresholdMinutes = 120;

    @PostConstruct
    public void validate() {
        double weightSum = ruleBasedWeight + reflectionWeight + groundingWeight;
        if (Math.abs(weightSum - 1.0) > 0.01) {
            throw new IllegalStateException(
                    "Agentic RAG scoring weights must sum to 1.0, got: " + weightSum
                            + " (rule=" + ruleBasedWeight + ", reflection=" + reflectionWeight
                            + ", grounding=" + groundingWeight + ")");
        }
        if (confidenceThreshold < 0 || confidenceThreshold > 1) {
            throw new IllegalStateException("confidenceThreshold must be between 0 and 1, got: " + confidenceThreshold);
        }
        if (groundingThreshold < 0 || groundingThreshold > 1) {
            throw new IllegalStateException("groundingThreshold must be between 0 and 1, got: " + groundingThreshold);
        }
        log.info("Agentic RAG config validated — weights sum={}, confidence={}, grounding={}, parallel={}",
                String.format("%.2f", weightSum), confidenceThreshold, groundingThreshold, parallelProcessing);
    }
}

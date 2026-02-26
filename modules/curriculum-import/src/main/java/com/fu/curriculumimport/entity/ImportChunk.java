package com.fu.curriculumimport.entity;

import com.fu.infrastructure.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * ImportChunk entity - Individual chunks extracted from a curriculum PDF.
 * Maps to system_admin.import_chunks table.
 *
 * Business: Each page/section of a PDF is split into chunks
 * for parallel AI processing.
 */
@Entity
@Table(name = "import_chunks", schema = "system_admin", indexes = {
        @Index(name = "idx_import_chunks_import", columnList = "import_id, status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
public class ImportChunk extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "import_id", nullable = false)
    @ToString.Exclude
    private CurriculumImport curriculumImport;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(name = "chunk_type", length = 50)
    private String chunkType;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "page_start")
    private Integer pageStart;

    @Column(name = "page_end")
    private Integer pageEnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    @Builder.Default
    private ChunkStatus status = ChunkStatus.PENDING;

    @Column(name = "extracted_data", columnDefinition = "jsonb")
    private String extractedData;

    @Column(name = "extraction_confidence", precision = 3, scale = 2)
    private BigDecimal extractionConfidence;

    @Column(name = "embedding_id")
    private UUID embeddingId;

    @Column(name = "token_count")
    private Integer tokenCount;

    @Column(name = "processing_time_ms")
    private Integer processingTimeMs;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    // --- Agentic RAG fields ---

    /**
     * Content type detected by classifier agent.
     */
    @Column(name = "content_type", length = 30)
    @Builder.Default
    private String contentType = "MIXED";

    /**
     * Validation agent result.
     */
    @Column(name = "validation_status", length = 20)
    @Builder.Default
    private String validationStatus = "PENDING";

    /**
     * Detailed validation results (JSON).
     */
    @Column(name = "validation_details", columnDefinition = "jsonb")
    private String validationDetails;

    /**
     * Number of re-extraction attempts (Corrective RAG).
     */
    @Column(name = "re_extraction_count")
    @Builder.Default
    private Integer reExtractionCount = 0;

    /**
     * Self-reflection score from LLM critique (0.0-1.0).
     */
    @Column(name = "reflection_score", precision = 3, scale = 2)
    private BigDecimal reflectionScore;

    /**
     * Source grounding score (0.0-1.0).
     */
    @Column(name = "grounding_score", precision = 3, scale = 2)
    private BigDecimal groundingScore;

    /**
     * Number of items flagged as hallucinated in this chunk.
     */
    @Column(name = "hallucinated_count")
    @Builder.Default
    private Integer hallucinatedCount = 0;

    /**
     * Chunk processing status
     */
    public enum ChunkStatus {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED
    }

    /**
     * Mark chunk as completed
     */
    public void markCompleted(String data, BigDecimal confidence, int processingMs) {
        this.status = ChunkStatus.COMPLETED;
        this.extractedData = data;
        this.extractionConfidence = confidence;
        this.processingTimeMs = processingMs;
        this.processedAt = LocalDateTime.now();
    }

    /**
     * Mark chunk as failed
     */
    public void markFailed(String error) {
        this.status = ChunkStatus.FAILED;
        this.errorMessage = error;
        this.processedAt = LocalDateTime.now();
    }
}

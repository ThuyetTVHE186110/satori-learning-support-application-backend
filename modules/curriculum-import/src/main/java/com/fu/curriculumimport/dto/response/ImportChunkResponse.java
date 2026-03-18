package com.fu.curriculumimport.dto.response;

import com.fu.curriculumimport.entity.ImportChunk.ChunkStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for ImportChunk
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportChunkResponse {

    private UUID id;
    private Integer chunkIndex;
    private String chunkType;
    private Integer pageStart;
    private Integer pageEnd;
    private ChunkStatus status;
    private BigDecimal extractionConfidence;
    private Integer tokenCount;
    private Integer processingTimeMs;
    private String errorMessage;
    private LocalDateTime processedAt;

    // Agentic RAG fields
    private String contentType;
    private String validationStatus;
    private Integer reExtractionCount;

    // Self-Reflection & Grounding fields
    private BigDecimal reflectionScore;
    private BigDecimal groundingScore;
    private Integer hallucinatedCount;
}

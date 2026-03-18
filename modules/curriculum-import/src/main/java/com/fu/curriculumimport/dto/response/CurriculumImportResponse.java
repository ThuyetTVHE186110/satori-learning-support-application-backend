package com.fu.curriculumimport.dto.response;

import com.fu.curriculumimport.entity.CurriculumImport.ImportStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for CurriculumImport entity.
 * Used for import status tracking.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CurriculumImportResponse {

    private UUID id;
    private String originalFilename;
    private String fileType;
    private Long fileSizeBytes;
    private Integer totalPages;

    private UUID courseId;
    private String courseName;

    private ImportStatus status;
    private String currentStep;

    private BigDecimal progressPercent;
    private Integer pagesProcessed;
    private Integer chunksCreated;
    private Integer embeddingsCreated;

    private Integer lessonsCreated;
    private Integer grammarPointsCreated;
    private Integer vocabularyCreated;

    private LocalDateTime queuedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime estimatedCompletionAt;

    private BigDecimal estimatedCostUsd;
    private BigDecimal actualCostUsd;
    private Integer tokensUsed;

    private String errorMessage;
    private Integer retryCount;
    private Integer maxRetries;

    // Agentic RAG tracking
    private Integer validationPassed;
    private Integer validationFailed;
    private Integer reExtractions;
    private Integer hallucinatedItems;

    private UUID createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

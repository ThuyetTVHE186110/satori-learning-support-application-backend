package com.fu.curriculumimport.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Statistics summary response for all curriculum imports
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportStatisticsResponse {

    private long totalImports;
    private long completedImports;
    private long failedImports;
    private long queuedImports;
    private long inProgressImports;

    private long totalLessonsCreated;
    private long totalVocabularyCreated;
    private long totalGrammarPointsCreated;

    private long totalTokensUsed;
    private BigDecimal totalCostUsd;

    // Agentic RAG metrics
    private long totalValidationPassed;
    private long totalValidationFailed;
    private long totalReExtractions;
    private long totalEmbeddingsCreated;
    private long totalHallucinatedItems;
}

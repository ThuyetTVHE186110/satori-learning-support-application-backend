package com.fu.curriculumimport.service.ingestion;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Result of parsing a PDF document.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentParseResult {

    private int totalPages;
    private String fullText;
    private List<String> pageTexts;
    private List<DocumentChunk> chunks;

    /**
     * Heading-aware semantic chunks produced by Document AI Layout Parser, when
     * {@code curriculum-import.ocr.document-ai-chunking-enabled=true}.
     * Always non-null but may be empty (e.g. native-text PDFs, OCR fallback paths,
     * or imports that don't run Document AI). Consumers route these directly to
     * {@code EmbeddingService} for RAG indexing — they're parallel to {@link #chunks}
     * (which feeds the agentic extraction pipeline) rather than a replacement.
     */
    @lombok.Builder.Default
    private List<LayoutChunk> documentAiChunks = java.util.List.of();
}

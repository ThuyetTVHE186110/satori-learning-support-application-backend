package com.fu.curriculumimport.service.ingestion;

import java.util.List;

/**
 * Wrapper returned by {@link OcrClient#extractBatch(java.util.List, byte[])}
 * — carries both per-page OCR results and (optionally) heading-aware semantic
 * chunks produced by Document AI Layout Parser.
 *
 * <p>{@code documentChunks} is non-empty only when
 * {@code curriculum-import.ocr.document-ai-chunking-enabled=true} and the
 * Layout Parser actually returned a {@code chunked_document}. In every other
 * case it's an empty list — callers must treat absence as "no chunks
 * available" rather than an error.
 *
 * @param pages           one entry per input page, in page order
 * @param documentChunks  semantic chunks for the entire document (may be empty)
 */
public record OcrBatchResult(List<OcrTextResult> pages, List<LayoutChunk> documentChunks) {

    public static OcrBatchResult of(List<OcrTextResult> pages) {
        return new OcrBatchResult(pages, List.of());
    }

    public static OcrBatchResult empty() {
        return new OcrBatchResult(List.of(), List.of());
    }

    public boolean hasDocumentChunks() {
        return documentChunks != null && !documentChunks.isEmpty();
    }
}

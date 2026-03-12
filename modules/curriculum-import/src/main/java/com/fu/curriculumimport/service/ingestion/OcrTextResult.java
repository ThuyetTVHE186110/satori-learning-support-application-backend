package com.fu.curriculumimport.service.ingestion;

import java.util.List;

/**
 * Result returned by {@link OcrClient#extractText(java.awt.image.BufferedImage)}.
 *
 * <p>In addition to plain text, the result may carry structured table rows
 * detected by the OCR provider (currently populated by {@link GoogleVisionOcrClient}).
 * When non-empty, {@link MnNTableParser} can convert them to vocabulary items
 * without an LLM call.
 *
 * @param text               extracted plain text (may be empty string)
 * @param confidence         average line-level confidence [0.0, 1.0]; 0.0 when OCR failed
 * @param structuredTableRows optional list of tab-separated table rows detected on the page
 */
public record OcrTextResult(String text, double confidence, List<String> structuredTableRows) {

    /** Convenience constructor without table rows (backward compatible). */
    public OcrTextResult(String text, double confidence) {
        this(text, confidence, List.of());
    }

    /** Sentinel value for when the OCR service is unavailable or returned nothing. */
    public static OcrTextResult empty() {
        return new OcrTextResult("", 0.0, List.of());
    }

    public boolean hasText() {
        return text != null && !text.isBlank();
    }

    public boolean hasTableData() {
        return structuredTableRows != null && !structuredTableRows.isEmpty();
    }
}

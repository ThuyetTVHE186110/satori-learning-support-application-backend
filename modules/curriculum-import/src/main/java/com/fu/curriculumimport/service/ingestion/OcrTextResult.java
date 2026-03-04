package com.fu.curriculumimport.service.ingestion;

/**
 * Result returned by {@link OcrClient#extractText(java.awt.image.BufferedImage)}.
 *
 * @param text       extracted plain text (may be empty string)
 * @param confidence average line-level confidence [0.0, 1.0]; 0.0 when OCR failed
 */
public record OcrTextResult(String text, double confidence) {

    /** Sentinel value for when the OCR service is unavailable or returned nothing. */
    public static OcrTextResult empty() {
        return new OcrTextResult("", 0.0);
    }

    public boolean hasText() {
        return text != null && !text.isBlank();
    }
}

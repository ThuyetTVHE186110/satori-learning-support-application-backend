package com.fu.curriculumimport.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the OCR subsystem.
 * Bound from curriculum-import.ocr.* in application.yml.
 * Uses Google Cloud Vision DOCUMENT_TEXT_DETECTION.
 */
@Data
@Component
@ConfigurationProperties(prefix = "curriculum-import.ocr")
public class OcrProperties {

    /**
     * Whether OCR is enabled.
     * When false, ScannedPdfIngestionService raises an error instead of calling OCR.
     */
    private boolean enabled = true;

    /**
     * DPI used when rendering PDF pages to images.
     * 300 DPI is recommended for MnN scanned pages (furigana strokes preserved).
     * 150 DPI is a fallback for memory-constrained environments.
     */
    private int renderDpi = 300;

    /**
     * Characters-per-page threshold below which a PDF is treated as scanned.
     * Scanned PDFs usually yield fewer than 50 chars/page from PDFBox text extraction.
     */
    private double scannedPageThreshold = 100.0;

    /**
     * Minimum OCR confidence (0-1) to accept a page's result.
     * Pages below this threshold are still included but flagged in logs.
     */
    private double minConfidence = 0.5;

    /**
     * Path to the Google Cloud service-account JSON key file.
     * If empty, falls back to Application Default Credentials
     * ({@code GOOGLE_APPLICATION_CREDENTIALS} env var).
     */
    private String credentialsFile = "";

    /**
     * Number of pages sent in a single {@code batchAnnotateImages} call to Google Vision.
     * Google Vision supports up to 16 images per batch request.
     */
    private int googleVisionBatchSize = 8;
}

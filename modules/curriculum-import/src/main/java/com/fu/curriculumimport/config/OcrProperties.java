package com.fu.curriculumimport.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the OCR subsystem.
 * Bound from curriculum-import.ocr.* in application.yml.
 */
@Data
@Component
@ConfigurationProperties(prefix = "curriculum-import.ocr")
public class OcrProperties {

    /**
     * OCR provider selection.
     * <ul>
     *   <li>{@code paddle} — self-hosted PaddleOCR sidecar (default, free)</li>
     *   <li>{@code google_vision} — Google Cloud Vision Document OCR API
     *       (better accuracy for scanned books; requires GOOGLE_APPLICATION_CREDENTIALS)</li>
     *   <li>{@code auto} — try Google Vision first, fall back to PaddleOCR on error</li>
     * </ul>
     */
    private String provider = "paddle";

    /** Base URL of the PaddleOCR FastAPI sidecar. */
    private String serviceUrl = "http://localhost:8090";

    /** HTTP connect + read timeout in seconds. */
    private int timeoutSeconds = 60;

    /**
     * Whether the OCR sidecar is enabled.
     * When false, ScannedPdfIngestionService raises an error instead of calling OCR.
     */
    private boolean enabled = true;

    /**
     * DPI used when rendering PDF pages to images.
     * 200 DPI gives good accuracy while keeping image size manageable (~1.5 MB/page).
     * Use 300 for higher accuracy on dense kanji text (at higher memory cost).
     */
    private int renderDpi = 200;

    /**
     * Characters-per-page threshold below which a PDF is treated as scanned.
     * A text-based PDF with Japanese content typically has >200 chars/page.
     * Scanned PDFs usually yield < 50 chars/page from PDFBox text extraction.
     */
    private double scannedPageThreshold = 100.0;

    /**
     * Minimum OCR confidence (0-1) to accept a page's result.
     * Pages with lower confidence are still included but flagged in logs.
     */
    private double minConfidence = 0.5;

    /**
     * Path to the Google Cloud service-account JSON key file.
     * Used when {@code provider=google_vision} or {@code provider=auto}.
     * If empty, the client falls back to Application Default Credentials (ADC /
     * {@code GOOGLE_APPLICATION_CREDENTIALS} env var).
     *
     * <p>Example: {@code environment/secrets/satori-489709-ce922a405186.json}
     */
    private String credentialsFile = "";
}

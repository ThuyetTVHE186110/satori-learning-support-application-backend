package com.fu.curriculumimport.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the OCR subsystem.
 * Bound from curriculum-import.ocr.* in application.yml.
 * Uses Google Cloud Document AI Layout Parser as the sole OCR provider,
 * with GPT-4o Vision as the Tier 2 fallback for chunks Document AI cannot cover.
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
     * Path to the Google Cloud service-account JSON key file used by Document AI.
     * If empty, falls back to Application Default Credentials
     * ({@code GOOGLE_APPLICATION_CREDENTIALS} env var).
     */
    private String credentialsFile = "";

    // ── Document AI Layout Parser ─────────────────────────────────────────────

    /**
     * Whether to enable Document AI Layout Parser for structured OCR.
     * Requires documentAiProjectId, documentAiLocation, and documentAiProcessorId to be set.
     * When disabled, scanned PDFs produce empty OCR text and rely entirely on the
     * GPT-4o Vision Tier 2 fallback in {@code VisionPageExtractor}.
     */
    private boolean documentAiEnabled = false;

    /**
     * Google Cloud project ID for Document AI (e.g. "satori-489709").
     * Same project used for Vision API is fine.
     */
    private String documentAiProjectId = "";

    /**
     * Document AI processor location. Must be "us" or "eu".
     * The regional endpoint is derived from this value.
     */
    private String documentAiLocation = "us";

    /**
     * Document AI processor ID (e.g. "abc123def456").
     * Create a Layout Parser processor in the Google Cloud Console under Document AI.
     */
    private String documentAiProcessorId = "";

    /**
     * Maximum time in minutes to wait for a Document AI processDocument call.
     * OCR on a full scanned PDF takes significantly longer than the default 60-second
     * gRPC deadline — 5 minutes covers typical 10-30 page Japanese textbook pages.
     */
    private int documentAiTimeoutMinutes = 5;

    /**
     * Minimum characters a Document AI page result must contain (excluding table rows)
     * to be accepted. Pages below this threshold (and with no structured table rows)
     * return empty OCR text — the GPT-4o Vision Tier 2 path in {@code VisionPageExtractor}
     * then handles those chunks from the rendered page images.
     * Set to 0 to accept any non-empty result.
     */
    private int documentAiMinCharsPerPage = 100;

    /**
     * Whether to request semantic chunking from Document AI Layout Parser.
     * When enabled, the response includes {@code document.chunked_document.chunks[]}
     * — pre-chunked, heading-aware text blocks suitable for direct RAG embedding.
     * Only applies when {@code documentAiEnabled} is also true.
     */
    private boolean documentAiChunkingEnabled = false;

    /**
     * Target chunk size (in tokens) for Document AI semantic chunking.
     * Google's Layout Parser default is 500. A value of 0 uses the service default.
     */
    private int documentAiChunkSize = 500;

    /**
     * Whether semantic chunks should include ancestor heading context
     * (e.g. "Bài 1 > Từ vựng") prepended to the chunk text. Improves RAG retrieval
     * quality by giving the embedder structural context.
     */
    private boolean documentAiIncludeAncestorHeadings = true;
}

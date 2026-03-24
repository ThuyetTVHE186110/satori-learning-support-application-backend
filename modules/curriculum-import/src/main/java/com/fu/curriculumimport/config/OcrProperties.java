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

    // ── Document AI Layout Parser (Tier 0) ────────────────────────────────────

    /**
     * Whether to enable Document AI Layout Parser as Tier 0 for structured table extraction.
     * When true, each scanned page is first sent to Document AI for proper table detection.
     * Falls back to Google Vision OCR if Document AI fails or returns empty.
     * Requires documentAiProjectId, documentAiLocation, and documentAiProcessorId to be set.
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
     * to be accepted without falling back to Google Vision.
     *
     * <p>Document AI Layout Parser can struggle with certain scanned PDFs, returning
     * 5–97 chars for pages that Google Vision extracts 900–1400 chars from.
     * Pages below this threshold (and with no structured table rows) are queued
     * for Vision re-processing instead. Set to 0 to disable and accept any non-empty result.
     *
     * <p>Default 100 — same as {@link #scannedPageThreshold}, which is the point below
     * which PDFBox native text extraction is considered inadequate for text PDFs.
     */
    private int documentAiMinCharsPerPage = 100;

    // ── Gemini Vision (Tier 2 replacement for GPT-4o) ─────────────────────────

    /**
     * Google AI API key for Gemini 2.5 Flash vision extraction.
     * Obtain from https://aistudio.google.com/app/apikey.
     * If blank, VisionPageExtractor falls back to GPT-4o (existing behaviour).
     */
    private String geminiApiKey = "";
}

package com.fu.curriculumimport.service.ingestion;

import com.fu.curriculumimport.config.OcrProperties;
import com.fu.curriculumimport.exception.InvalidImportFileException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OCR client — three-tier strategy for scanned Japanese textbook pages.
 *
 * <h3>Tier 0 — Document AI Layout Parser (optional, highest fidelity)</h3>
 * <p>When {@code curriculum-import.ocr.document-ai-enabled=true}, the original PDF
 * is sent to {@link DocumentAiLayoutClient} in a <b>single API call</b>.
 * Document AI returns semantically correct table cells (headerRows / bodyRows / cells
 * with TextAnchor), which eliminates the column-alignment errors that Google Vision's
 * Y-bucket heuristic can produce on multi-column vocabulary pages.
 * The full-PDF approach is also cheaper than per-page calls and avoids the lossy
 * JPEG render step entirely.
 * Any page for which Document AI returns empty text falls back to Tier 1.
 *
 * <h3>Tier 1 — Google Cloud Vision DOCUMENT_TEXT_DETECTION (always-on fallback)</h3>
 * <p>{@link GoogleVisionOcrClient} — superior CJK/furigana OCR quality, batch-capable
 * (up to 16 images per {@code batchAnnotateImages} call).
 * Detects table rows via bounding-box Y-column heuristic (±25 px buckets).
 *
 * <h3>Tier 2 — Vision-first LLM extraction</h3>
 * <p>Handled by {@link com.fu.curriculumimport.service.vision.VisionPageExtractor}
 * (not in this class). Activated when {@link MnNTableParser} returns empty results
 * after Tier 0 / Tier 1.
 */
@Slf4j
@Component
public class OcrClient {

    private final OcrProperties props;
    private final DocumentAiLayoutClient documentAiClient;
    private final GoogleVisionOcrClient googleVisionClient;

    public OcrClient(OcrProperties props, DocumentAiLayoutClient documentAiClient) {
        this.props = props;
        this.documentAiClient = documentAiClient;
        this.googleVisionClient = new GoogleVisionOcrClient(props.getCredentialsFile());
    }

    /**
     * OCR a single rendered page image (Vision-only path).
     *
     * <p>Document AI requires the original PDF bytes and is not applicable here.
     * Falls back directly to Google Vision (Tier 1).
     *
     * @param image page image from PDFRenderer
     * @return extracted text + confidence + optional table rows;
     *         {@link OcrTextResult#empty()} when Vision fails
     */
    public OcrTextResult extractText(BufferedImage image) {
        if (!props.isEnabled()) {
            throw new InvalidImportFileException(
                    "OCR service is disabled. Cannot process scanned PDF.");
        }
        try {
            byte[] jpeg = encodeToJpeg(image);
            List<OcrTextResult> results = googleVisionClient.extractTextBatch(List.of(jpeg));
            return results.isEmpty() ? OcrTextResult.empty() : results.get(0);
        } catch (InvalidImportFileException e) {
            throw e;
        } catch (Exception e) {
            log.error("OCR extractText failed: {}", e.getMessage(), e);
            return OcrTextResult.empty();
        }
    }

    /**
     * OCR all pages of a scanned PDF.
     *
     * <p>Strategy:
     * <ol>
     *   <li><b>Tier 0 (Document AI)</b>: if enabled and the PDF is within the 20 MB
     *       inline limit, the full PDF is sent in a single call. Each page in the
     *       response is mapped 1-to-1 with the entries in {@code jpegBytesList}.</li>
     *   <li><b>Tier 1 (Google Vision)</b>: pages for which Document AI returned empty
     *       text, or all pages when Document AI is disabled, are sent to Google Vision
     *       in batches of {@code googleVisionBatchSize} (default 16).</li>
     * </ol>
     *
     * @param jpegBytesList JPEG-encoded page images, in page order.
     *                      Entries may be {@code null} for pages that failed to render;
     *                      those positions receive {@link OcrTextResult#empty()} directly.
     * @param pdfBytes      raw bytes of the original PDF file, used by Tier 0.
     *                      Pass {@code null} to skip Document AI regardless of config.
     * @return one {@link OcrTextResult} per input entry, in the same order
     */
    public List<OcrTextResult> extractTextBatch(List<byte[]> jpegBytesList, byte[] pdfBytes) {
        if (!props.isEnabled()) {
            throw new InvalidImportFileException(
                    "OCR service is disabled. Cannot process scanned PDF.");
        }
        if (jpegBytesList.isEmpty()) return List.of();

        int total = jpegBytesList.size();
        OcrTextResult[] results = new OcrTextResult[total];

        // Pre-fill render failures (null entries) so they are never sent to any OCR API.
        for (int i = 0; i < total; i++) {
            if (jpegBytesList.get(i) == null) {
                results[i] = OcrTextResult.empty();
            }
        }

        // Indices still needing OCR after Tier 0.
        List<Integer> visionFallbackIndices = new ArrayList<>();
        List<byte[]>  visionFallbackJpegs   = new ArrayList<>();

        // ── Tier 0: Document AI — one call for the whole PDF ─────────────────
        if (documentAiClient.isAvailable() && pdfBytes != null) {
            int minChars = props.getDocumentAiMinCharsPerPage();
            List<OcrTextResult> docAiResults = documentAiClient.processDocument(pdfBytes);
            for (int i = 0; i < total; i++) {
                if (results[i] != null) continue; // already marked empty (render failure)

                if (i < docAiResults.size()) {
                    OcrTextResult r = docAiResults.get(i);
                    boolean hasTable = !r.structuredTableRows().isEmpty();
                    // Accept if: (a) table rows present (Document AI table extraction is
                    // superior to Vision), OR (b) text length meets the quality threshold.
                    // Pages below the threshold are often OCR noise (page numbers, watermarks)
                    // on scanned PDFs where Document AI underperforms Google Vision.
                    boolean sufficient = hasTable || (r.hasText() && r.text().length() >= minChars);
                    if (sufficient) {
                        results[i] = r;
                        log.debug("Tier 0 (Document AI) page {}/{}: {} chars, {} table rows",
                                i + 1, total, r.text().length(),
                                r.structuredTableRows().size());
                    } else {
                        log.debug("Tier 0 (Document AI) page {}/{}: {} chars < min {} (no tables) — queued for Vision fallback",
                                i + 1, total, r.hasText() ? r.text().length() : 0, minChars);
                        visionFallbackIndices.add(i);
                        visionFallbackJpegs.add(jpegBytesList.get(i));
                    }
                } else {
                    // Document AI returned fewer pages than the JPEG list — queue remainder.
                    visionFallbackIndices.add(i);
                    visionFallbackJpegs.add(jpegBytesList.get(i));
                }
            }
        } else {
            // Document AI disabled or no PDF bytes — send everything to Vision.
            for (int i = 0; i < total; i++) {
                if (results[i] != null) continue;
                visionFallbackIndices.add(i);
                visionFallbackJpegs.add(jpegBytesList.get(i));
            }
        }

        // ── Tier 1: Google Vision — batched ──────────────────────────────────
        if (!visionFallbackJpegs.isEmpty()) {
            int batchSize = props.getGoogleVisionBatchSize();
            log.debug("Tier 1 (Google Vision): {} pages in batches of {}",
                    visionFallbackJpegs.size(), batchSize);

            for (int start = 0; start < visionFallbackJpegs.size(); start += batchSize) {
                int end   = Math.min(start + batchSize, visionFallbackJpegs.size());
                List<byte[]> batch        = visionFallbackJpegs.subList(start, end);
                List<Integer> batchPageIds = visionFallbackIndices.subList(start, end);

                List<OcrTextResult> visionResults = callVisionBatch(batch);
                for (int j = 0; j < visionResults.size(); j++) {
                    int pageIdx = batchPageIds.get(j);
                    OcrTextResult vr = visionResults.get(j);
                    results[pageIdx] = vr;
                    if (vr.hasText()) {
                        log.debug("Tier 1 (Vision) page {}/{}: {} chars, {} table rows",
                                pageIdx + 1, total, vr.text().length(),
                                vr.structuredTableRows().size());
                    } else {
                        log.warn("Tier 1 (Vision) page {}/{}: OCR returned empty", pageIdx + 1, total);
                    }
                }
            }
        }

        // Guard: fill any remaining nulls (shouldn't happen, but defensive).
        for (int i = 0; i < total; i++) {
            if (results[i] == null) results[i] = OcrTextResult.empty();
        }

        return List.of(results);
    }

    /** Release gRPC channels on Spring context shutdown. */
    @PreDestroy
    public void close() {
        googleVisionClient.close();
        documentAiClient.close();
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /** Delegate a JPEG batch to Google Vision, returning a same-length result list. */
    private List<OcrTextResult> callVisionBatch(List<byte[]> jpegs) {
        try {
            return googleVisionClient.extractTextBatch(jpegs);
        } catch (Exception e) {
            log.error("Google Vision batch OCR error: {}", e.getMessage(), e);
            return Collections.nCopies(jpegs.size(), OcrTextResult.empty());
        }
    }

    /** Encode a BufferedImage to JPEG bytes for the single-image API path. */
    private byte[] encodeToJpeg(BufferedImage image) throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "jpg", baos);
        return baos.toByteArray();
    }
}

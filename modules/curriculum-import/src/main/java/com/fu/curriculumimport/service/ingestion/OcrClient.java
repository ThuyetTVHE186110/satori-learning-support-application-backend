package com.fu.curriculumimport.service.ingestion;

import com.fu.curriculumimport.config.OcrProperties;
import com.fu.curriculumimport.exception.InvalidImportFileException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * OCR client — single-tier strategy backed by Google Cloud Document AI Layout Parser.
 *
 * <p>The original PDF is sent to {@link DocumentAiLayoutClient} in one API call.
 * Document AI returns semantically correct table cells (headerRows / bodyRows / cells
 * with TextAnchor) and per-page text. Pages for which Document AI returns insufficient
 * text are left empty here; downstream
 * {@link com.fu.curriculumimport.service.vision.VisionPageExtractor} (Tier 2 — GPT-4o
 * Vision) then runs directly on the rendered page images for those chunks.
 */
@Slf4j
@Component
public class OcrClient {

    private final OcrProperties props;
    private final DocumentAiLayoutClient documentAiClient;

    public OcrClient(OcrProperties props, DocumentAiLayoutClient documentAiClient) {
        this.props = props;
        this.documentAiClient = documentAiClient;
    }

    /**
     * OCR all pages of a scanned PDF through Document AI.
     *
     * <p>Pages below {@code documentAiMinCharsPerPage} (and without structured tables)
     * are returned as {@link OcrTextResult#empty()} — the GPT-4o vision path in
     * {@code VisionPageExtractor} picks them up via the rendered page images attached
     * to each chunk.
     *
     * @param jpegBytesList JPEG-encoded page images, in page order. Only the size of
     *                      this list is used (to align result indices); null entries
     *                      receive {@link OcrTextResult#empty()}.
     * @param pdfBytes      raw bytes of the original PDF file, sent to Document AI.
     *                      When {@code null} every page returns empty.
     * @return one {@link OcrTextResult} per input entry, in the same order
     */
    public OcrBatchResult extractTextBatch(List<byte[]> jpegBytesList, byte[] pdfBytes) {
        if (!props.isEnabled()) {
            throw new InvalidImportFileException(
                    "OCR service is disabled. Cannot process scanned PDF.");
        }
        if (jpegBytesList.isEmpty()) return OcrBatchResult.empty();

        int total = jpegBytesList.size();
        OcrTextResult[] results = new OcrTextResult[total];
        List<LayoutChunk> documentChunks = List.of();

        // Pre-fill render failures so they never enter the Document AI path.
        for (int i = 0; i < total; i++) {
            if (jpegBytesList.get(i) == null) {
                results[i] = OcrTextResult.empty();
            }
        }

        if (documentAiClient.isAvailable() && pdfBytes != null) {
            int minChars = props.getDocumentAiMinCharsPerPage();
            OcrBatchResult docAi = documentAiClient.processDocument(pdfBytes);
            List<OcrTextResult> docAiResults = docAi.pages();
            documentChunks = docAi.documentChunks();
            for (int i = 0; i < total; i++) {
                if (results[i] != null) continue;
                if (i < docAiResults.size()) {
                    OcrTextResult r = docAiResults.get(i);
                    boolean hasTable = !r.structuredTableRows().isEmpty();
                    boolean sufficient = hasTable || (r.hasText() && r.text().length() >= minChars);
                    if (sufficient) {
                        results[i] = r;
                        log.debug("Document AI page {}/{}: {} chars, {} table rows",
                                i + 1, total, r.text().length(), r.structuredTableRows().size());
                    } else {
                        log.debug("Document AI page {}/{}: {} chars < min {} (no tables) — Tier 2 (GPT-4o Vision) will handle this chunk",
                                i + 1, total, r.hasText() ? r.text().length() : 0, minChars);
                        results[i] = OcrTextResult.empty();
                    }
                } else {
                    results[i] = OcrTextResult.empty();
                }
            }
        } else {
            if (!documentAiClient.isAvailable()) {
                log.warn("Document AI not available — all pages returned empty (Tier 2 vision will handle)");
            }
            for (int i = 0; i < total; i++) {
                if (results[i] == null) results[i] = OcrTextResult.empty();
            }
        }

        return new OcrBatchResult(new ArrayList<>(List.of(results)), documentChunks);
    }

    /** Release the gRPC channel on Spring context shutdown. */
    @PreDestroy
    public void close() {
        documentAiClient.close();
    }
}

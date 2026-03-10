package com.fu.curriculumimport.service.ingestion;

import com.fu.curriculumimport.config.OcrProperties;
import com.fu.curriculumimport.exception.InvalidImportFileException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonProperty;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;

/**
 * Multi-provider OCR client — routes to PaddleOCR sidecar or Google Cloud Vision
 * based on {@code curriculum-import.ocr.provider} configuration.
 *
 * <h3>Providers</h3>
 * <ul>
 *   <li>{@code paddle} — self-hosted PaddleOCR FastAPI sidecar (default, free,
 *       no network egress)</li>
 *   <li>{@code google_vision} — Google Cloud Vision DOCUMENT_TEXT_DETECTION
 *       (better accuracy for dense kanji + furigana; table structure detection)</li>
 *   <li>{@code auto} — tries Google Vision first; if it fails or the jar is absent,
 *       falls back to PaddleOCR automatically</li>
 * </ul>
 *
 * <h3>Structured table rows</h3>
 * <p>When Google Vision is active and detects a vocabulary table on a page, the
 * {@link OcrTextResult} includes tab-separated {@code structuredTableRows} that
 * {@link MnNTableParser} can convert to {@link com.fu.curriculumimport.service.agent.ExtractionResult.ExtractedVocabulary}
 * items without an LLM call.
 */
@Slf4j
@Component
public class OcrClient {

    private final OcrProperties props;
    private final RestClient restClient;
    private final GoogleVisionOcrClient googleVisionClient;

    public OcrClient(OcrProperties props, RestClient.Builder builder) {
        this.props = props;
        this.restClient = builder
                .baseUrl(props.getServiceUrl())
                .build();
        this.googleVisionClient = new GoogleVisionOcrClient(props.getCredentialsFile());
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Send a rendered page image to the configured OCR provider and receive text back.
     *
     * <p>Provider selection follows {@code curriculum-import.ocr.provider}:
     * <ul>
     *   <li>{@code google_vision} → Google Vision only</li>
     *   <li>{@code auto} → Google Vision with PaddleOCR fallback</li>
     *   <li>{@code paddle} (default) → PaddleOCR sidecar only</li>
     * </ul>
     *
     * @param image page image (from PDFRenderer)
     * @return extracted text + confidence + optional structured table rows;
     *         {@link OcrTextResult#empty()} on failure
     */
    public OcrTextResult extractText(BufferedImage image) {
        if (!props.isEnabled()) {
            throw new InvalidImportFileException(
                    "OCR service is disabled. Cannot process scanned PDF.");
        }

        String provider = props.getProvider() != null
                ? props.getProvider().toLowerCase().trim()
                : "paddle";

        return switch (provider) {
            case "google_vision" -> extractWithGoogleVision(image, false);
            case "auto"          -> extractWithGoogleVision(image, true);
            default              -> extractWithPaddle(image);
        };
    }

    // ── Provider implementations ─────────────────────────────────────────────

    private OcrTextResult extractWithGoogleVision(BufferedImage image, boolean fallbackToPaddle) {
        try {
            OcrTextResult result = googleVisionClient.extractText(image);
            if (result.hasText()) {
                log.debug("Google Vision OCR: {} chars, {} table rows",
                        result.text().length(), result.structuredTableRows().size());
                return result;
            }
            if (fallbackToPaddle) {
                log.info("Google Vision returned empty result — falling back to PaddleOCR");
                return extractWithPaddle(image);
            }
            return OcrTextResult.empty();
        } catch (Exception e) {
            log.warn("Google Vision OCR error: {} — {}", e.getMessage(),
                    fallbackToPaddle ? "falling back to PaddleOCR" : "returning empty");
            if (fallbackToPaddle) return extractWithPaddle(image);
            return OcrTextResult.empty();
        }
    }

    /**
     * Send image to the PaddleOCR FastAPI sidecar.
     *
     * <p>Uses the {@code rich_text} field from the v3 response if present
     * (contains furigana merged in bracket notation, e.g. {@code 先生[せんせい]}).
     * Falls back to the plain {@code text} field for older OCR service versions.
     */
    private OcrTextResult extractWithPaddle(BufferedImage image) {
        try {
            String base64 = encodeImage(image);
            OcrServiceResponse resp = restClient.post()
                    .uri("/ocr")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new OcrServiceRequest(base64))
                    .retrieve()
                    .body(OcrServiceResponse.class);

            if (resp == null || resp.text() == null) {
                log.warn("PaddleOCR service returned null response");
                return OcrTextResult.empty();
            }

            // Use rich_text (with furigana brackets) when available (v3+)
            String bestText = (resp.richText() != null && !resp.richText().isBlank())
                    ? resp.richText()
                    : resp.text();

            // v4+ structured table rows from the OCR service
            List<String> tableRows = resp.tableRows() != null ? resp.tableRows() : List.of();

            log.debug("PaddleOCR v{}: {} lines, furigana_merged={}, tables={}, conf={}, {}ms",
                    resp.version() != null ? resp.version() : "?",
                    resp.lineCount(), resp.furiganaMerged(),
                    tableRows.size(), resp.confidence(), resp.processingMs());

            return new OcrTextResult(bestText, resp.confidence(), tableRows);

        } catch (ResourceAccessException e) {
            log.warn("PaddleOCR service unreachable at {}: {}", props.getServiceUrl(), e.getMessage());
            return OcrTextResult.empty();
        } catch (org.springframework.web.client.HttpServerErrorException e) {
            log.error("PaddleOCR service returned {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return OcrTextResult.empty();
        } catch (Exception e) {
            log.error("Unexpected OCR client error", e);
            return OcrTextResult.empty();
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private String encodeImage(BufferedImage image) throws IOException {
        // JPEG uses ~5-10x less memory than PNG during encoding and produces a
        // much smaller HTTP payload — OCR quality is unaffected at this resolution.
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", baos);
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    // ── Internal DTOs (package-private records) ──────────────────────────────

    record OcrServiceRequest(String image) {}

    record OcrServiceResponse(
            String text,
            @JsonProperty("rich_text") String richText,
            double confidence,
            @JsonProperty("line_count") int lineCount,
            @JsonProperty("furigana_merged") int furiganaMerged,
            @JsonProperty("processing_ms") int processingMs,
            String version,
            @JsonProperty("table_rows") List<String> tableRows
    ) {}
}

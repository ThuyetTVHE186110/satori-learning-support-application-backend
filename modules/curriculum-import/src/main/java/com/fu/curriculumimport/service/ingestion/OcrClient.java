package com.fu.curriculumimport.service.ingestion;

import com.fu.curriculumimport.config.OcrProperties;
import com.fu.curriculumimport.exception.InvalidImportFileException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

/**
 * HTTP client that talks to the PaddleOCR FastAPI microservice.
 *
 * <p>On network failure the method returns {@link OcrTextResult#empty()} so the
 * caller can decide whether to fail-fast or fall through to a fallback.
 */
@Slf4j
@Component
public class OcrClient {

    private final OcrProperties props;
    private final RestClient restClient;

    public OcrClient(OcrProperties props, RestClient.Builder builder) {
        this.props = props;
        this.restClient = builder
                .baseUrl(props.getServiceUrl())
                .build();
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Send a rendered page image to the OCR service and receive plain text back.
     *
     * @param image page image (from PDFRenderer)
     * @return extracted text + confidence; {@link OcrTextResult#empty()} on failure
     */
    public OcrTextResult extractText(BufferedImage image) {
        if (!props.isEnabled()) {
            throw new InvalidImportFileException(
                    "OCR service is disabled. Cannot process scanned PDF.");
        }
        try {
            String base64 = encodeImage(image);
            OcrServiceResponse resp = restClient.post()
                    .uri("/ocr")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new OcrServiceRequest(base64))
                    .retrieve()
                    .body(OcrServiceResponse.class);

            if (resp == null || resp.text() == null) {
                log.warn("OCR service returned null response");
                return OcrTextResult.empty();
            }
            log.debug("OCR: {} lines, conf={}, {}ms",
                    resp.lineCount(), resp.confidence(), resp.processingMs());
            return new OcrTextResult(resp.text(), resp.confidence());

        } catch (ResourceAccessException e) {
            log.warn("OCR service unreachable at {}: {}", props.getServiceUrl(), e.getMessage());
            return OcrTextResult.empty();
        } catch (org.springframework.web.client.HttpServerErrorException e) {
            log.error("OCR service returned {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return OcrTextResult.empty();
        } catch (Exception e) {
            log.error("Unexpected OCR client error", e);
            return OcrTextResult.empty();
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private String encodeImage(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", baos);
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    // ── Internal DTOs (package-private records) ──────────────────────────────

    record OcrServiceRequest(String image) {}

    record OcrServiceResponse(String text, double confidence, int lineCount, int processingMs) {}
}

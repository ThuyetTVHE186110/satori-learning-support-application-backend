package com.fu.curriculumimport.service.ingestion;

import com.fu.curriculumimport.config.OcrProperties;
import com.fu.curriculumimport.exception.InvalidImportFileException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;

/**
 * OCR client — delegates to Google Cloud Vision DOCUMENT_TEXT_DETECTION.
 * <p>
 * For scanned PDF curriculum import, Google Vision provides superior accuracy
 * for dense kanji, furigana, and vocabulary table detection.
 * Requires {@code GOOGLE_APPLICATION_CREDENTIALS} env var or a credentials file
 * configured in {@code curriculum-import.ocr.credentials-file}.
 */
@Slf4j
@Component
public class OcrClient {

    private final OcrProperties props;
    private final GoogleVisionOcrClient googleVisionClient;

    public OcrClient(OcrProperties props) {
        this.props = props;
        this.googleVisionClient = new GoogleVisionOcrClient(props.getCredentialsFile());
    }

    /**
     * Send a rendered page image to Google Cloud Vision and receive text back.
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
        try {
            OcrTextResult result = googleVisionClient.extractText(image);
            if (result.hasText()) {
                log.debug("Google Vision OCR: {} chars, {} table rows",
                        result.text().length(), result.structuredTableRows().size());
            } else {
                log.warn("Google Vision returned empty result for page");
            }
            return result;
        } catch (Exception e) {
            log.error("Google Vision OCR error: {}", e.getMessage(), e);
            return OcrTextResult.empty();
        }
    }

    /**
     * OCR multiple pre-encoded JPEG pages via a single {@code batchAnnotateImages} call.
     *
     * @param jpegBytesList JPEG-encoded page images
     * @return one {@link OcrTextResult} per input, in the same order
     */
    public List<OcrTextResult> extractTextBatch(List<byte[]> jpegBytesList) {
        if (!props.isEnabled()) {
            throw new InvalidImportFileException(
                    "OCR service is disabled. Cannot process scanned PDF.");
        }
        if (jpegBytesList.isEmpty()) return List.of();
        try {
            return googleVisionClient.extractTextBatch(jpegBytesList);
        } catch (Exception e) {
            log.error("Google Vision batch OCR error: {}", e.getMessage(), e);
            return Collections.nCopies(jpegBytesList.size(), OcrTextResult.empty());
        }
    }

    /** Release the cached Google Vision gRPC channel on Spring context shutdown. */
    @PreDestroy
    public void close() {
        googleVisionClient.close();
    }
}

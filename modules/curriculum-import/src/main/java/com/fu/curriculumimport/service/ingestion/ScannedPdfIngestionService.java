package com.fu.curriculumimport.service.ingestion;

import com.fu.curriculumimport.config.OcrProperties;
import com.fu.curriculumimport.exception.InvalidImportFileException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Ingestion service for <b>scanned (image-based) PDFs</b>.
 *
 * <p>Pipeline per page:
 * <ol>
 *   <li>PDFBox {@link PDFRenderer} renders the page to a {@link BufferedImage} at
 *       the configured DPI.</li>
 *   <li>{@link OcrClient} sends the image to the PaddleOCR sidecar and receives
 *       plain Japanese/English text.</li>
 *   <li>Pages are accumulated and chunked the same way as text-based PDFs
 *       (character-based sliding window, 2 000 chars / 200 overlap).</li>
 * </ol>
 *
 * <p>ChunkType is set to {@code "SCANNED"} so downstream agents know the text
 * originated from OCR and may have lower fidelity than native text extraction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScannedPdfIngestionService {

    private static final int TARGET_CHUNK_SIZE = 2000;
    private static final int CHUNK_OVERLAP = 200;

    private final OcrProperties ocrProps;
    private final OcrClient ocrClient;

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * OCR-extract all pages of a scanned PDF and return structured chunks.
     *
     * @param file validated scanned PDF MultipartFile
     * @return parsed document with page texts and overlapping text chunks
     */
    public DocumentParseResult parse(MultipartFile file) {
        log.info("Scanned-PDF ingestion start: {}", file.getOriginalFilename());

        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            int totalPages = document.getNumberOfPages();
            log.info("Rendering {} pages at {} DPI…", totalPages, ocrProps.getRenderDpi());

            PDFRenderer renderer = new PDFRenderer(document);
            List<String> pageTexts = new ArrayList<>();
            StringBuilder fullText = new StringBuilder();
            int totalLinesOcrd = 0;

            for (int i = 0; i < totalPages; i++) {
                BufferedImage pageImage = renderer.renderImageWithDPI(
                        i, ocrProps.getRenderDpi(), ImageType.RGB);

                OcrTextResult ocr = ocrClient.extractText(pageImage);

                if (!ocr.hasText()) {
                    log.warn("Page {} OCR produced no text (conf={})", i + 1, ocr.confidence());
                    pageTexts.add("");
                } else {
                    if (ocr.confidence() < ocrProps.getMinConfidence()) {
                        log.warn("Page {} low OCR confidence: {}", i + 1, ocr.confidence());
                    }
                    pageTexts.add(ocr.text());
                    fullText.append(ocr.text()).append("\n\n");
                    totalLinesOcrd += ocr.text().split("\n").length;
                }
            }

            if (fullText.isEmpty()) {
                throw new InvalidImportFileException(
                        "OCR produced no text from this scanned PDF. "
                                + "Please check if the OCR service is running and the file is readable.");
            }

            List<DocumentChunk> chunks = createChunks(pageTexts);
            log.info("Scanned-PDF ingestion done: {} pages, {} OCR lines, {} chunks",
                    totalPages, totalLinesOcrd, chunks.size());

            return DocumentParseResult.builder()
                    .totalPages(totalPages)
                    .fullText(fullText.toString())
                    .pageTexts(pageTexts)
                    .chunks(chunks)
                    .build();

        } catch (InvalidImportFileException e) {
            throw e;
        } catch (IOException e) {
            log.error("Failed to render scanned PDF: {}", file.getOriginalFilename(), e);
            throw new InvalidImportFileException("Failed to process scanned PDF: " + e.getMessage());
        }
    }

    // ── Chunking (same sliding-window logic as PdfIngestionService) ─────────

    private List<DocumentChunk> createChunks(List<String> pageTexts) {
        List<DocumentChunk> chunks = new ArrayList<>();
        int chunkIndex = 0;
        StringBuilder current = new StringBuilder();
        int chunkPageStart = 1;

        for (int pi = 0; pi < pageTexts.size(); pi++) {
            String pageText = pageTexts.get(pi);
            int pageNum = pi + 1;

            if (current.isEmpty()) {
                chunkPageStart = pageNum;
            }
            current.append(pageText).append("\n\n");

            if (current.length() >= TARGET_CHUNK_SIZE) {
                chunks.add(buildChunk(chunkIndex++, current.toString().trim(),
                        chunkPageStart, pageNum));
                String overlap = current.substring(
                        Math.max(0, current.length() - CHUNK_OVERLAP));
                current = new StringBuilder(overlap);
                chunkPageStart = pageNum;
            }
        }

        if (!current.isEmpty()) {
            chunks.add(buildChunk(chunkIndex, current.toString().trim(),
                    chunkPageStart, pageTexts.size()));
        }
        return chunks;
    }

    private DocumentChunk buildChunk(int index, String content, int pageStart, int pageEnd) {
        return DocumentChunk.builder()
                .chunkIndex(index)
                .content(content)
                .pageStart(pageStart)
                .pageEnd(pageEnd)
                .chunkType("SCANNED")           // signals OCR origin to downstream agents
                .estimatedTokenCount(content.length() / 4)
                .build();
    }
}

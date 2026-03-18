package com.fu.curriculumimport.service.ingestion;

import com.fu.curriculumimport.config.OcrProperties;
import com.fu.curriculumimport.exception.InvalidImportFileException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(file.getInputStream()))) {
            int totalPages = document.getNumberOfPages();
            log.info("Rendering {} pages at {} DPI…", totalPages, ocrProps.getRenderDpi());

            PDFRenderer renderer = new PDFRenderer(document);
            // Pre-sized array so batch results can be stored at the correct page index.
            String[] pageTextArray = new String[totalPages];
            Arrays.fill(pageTextArray, "");
            StringBuilder fullText = new StringBuilder();
            int totalLinesOcrd = 0;
            // 0-based page index → structured table rows from OCR (may be empty for most pages)
            Map<Integer, List<String>> pageTableRows = new HashMap<>();

            int renderFailures = 0;
            int batchSize = ocrProps.getGoogleVisionBatchSize();

            // Retain JPEG bytes per page for vision-first extraction (Tier 2).
            // Memory estimate: 200 pages × ~400 KB JPEG ≈ 80 MB — acceptable for typical textbooks.
            Map<Integer, byte[]> pageImageMap = new HashMap<>();

            // Accumulate JPEG-encoded pages for the current batch.
            // Pages are encoded to JPEG immediately after rendering so the large
            // raw BufferedImage can be flushed before the next render, keeping
            // heap usage to O(batchSize × JPEG_size) ≈ O(batchSize × 300 KB).
            List<byte[]> batchJpeg   = new ArrayList<>(batchSize);
            List<Integer> batchPages = new ArrayList<>(batchSize); // 0-based page indices

            for (int i = 0; i < totalPages; i++) {
                BufferedImage pageImage = null;
                try {
                    pageImage = renderer.renderImageWithDPI(
                            i, ocrProps.getRenderDpi(), ImageType.RGB);
                    // Encode to JPEG right away so we can flush the raw image.
                    byte[] jpeg = encodeToJpeg(pageImage);
                    batchJpeg.add(jpeg);
                    batchPages.add(i);
                    pageImageMap.put(i, jpeg);  // retain for vision extraction
                } catch (Exception e) {
                    log.warn("Page {} render/encode failed ({}), skipping", i + 1, e.getMessage());
                    renderFailures++;
                } finally {
                    // A4 @ 300 DPI ≈ 25 MB per BufferedImage — flush immediately after JPEG encode.
                    if (pageImage != null) pageImage.flush();
                }

                boolean lastPage = (i == totalPages - 1);
                if (batchJpeg.size() == batchSize || (lastPage && !batchJpeg.isEmpty())) {
                    List<OcrTextResult> ocrResults = ocrClient.extractTextBatch(batchJpeg);
                    for (int j = 0; j < ocrResults.size(); j++) {
                        int pageIdx = batchPages.get(j);
                        OcrTextResult ocr = ocrResults.get(j);
                        if (!ocr.hasText()) {
                            log.warn("Page {} OCR produced no text (conf={})", pageIdx + 1, ocr.confidence());
                        } else {
                            if (ocr.confidence() < ocrProps.getMinConfidence()) {
                                log.warn("Page {} low OCR confidence: {}", pageIdx + 1, ocr.confidence());
                            }
                            String normalizedText = FuriganaNormalizer.normalize(ocr.text());
                            String cleanedText    = OcrTextPreprocessor.cleanOcrPage(normalizedText);
                            pageTextArray[pageIdx] = cleanedText;
                            fullText.append(cleanedText).append("\n\n");
                            totalLinesOcrd += cleanedText.split("\n").length;
                            if (ocr.hasTableData()) {
                                pageTableRows.put(pageIdx, ocr.structuredTableRows());
                            }
                        }
                    }
                    batchJpeg.clear();
                    batchPages.clear();
                    // Hint GC to reclaim flushed BufferedImage from this batch.
                    // JPEG bytes are still referenced by pageImageMap — do not null them.
                    System.gc();
                    log.debug("Batch OCR done: pages up to {} of {}", i + 1, totalPages);
                }
            }

            List<String> pageTexts = Arrays.asList(pageTextArray);

            if (renderFailures > 0) {
                log.warn("{} of {} pages failed to render (missing image codecs?)",
                        renderFailures, totalPages);
            }

            if (fullText.isEmpty()) {
                throw new InvalidImportFileException(
                        "OCR produced no text from this scanned PDF. "
                                + "Please check if the OCR service is running and the file is readable.");
            }

            List<DocumentChunk> chunks = createChunks(pageTexts, pageTableRows, pageImageMap);
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

    // ── Chunking ─────────────────────────────────────────────────────────────

    /**
     * Create chunks from OCR page texts.
     *
     * <p>Strategy (in priority order):
     * <ol>
     *   <li><b>Lesson-aware + section-aware</b>: if lesson boundaries ("Bài N") are
     *       detected, each lesson is first grouped (Layer 1), then split into
     *       MnN functional sections such as "Từ vựng", "Mẫu câu" (Layer 2).</li>
     *   <li><b>Character-based fallback</b>: sliding-window when no lesson markers found.</li>
     * </ol>
     *
     * @param pageTableRows 0-based page index → table rows from OCR (propagated to VOCABULARY chunks)
     * @param pageImageMap  0-based page index → JPEG bytes (propagated to VOCABULARY + GRAMMAR chunks)
     */
    private List<DocumentChunk> createChunks(List<String> pageTexts,
                                              Map<Integer, List<String>> pageTableRows,
                                              Map<Integer, byte[]> pageImageMap) {
        LinkedHashMap<Integer, Integer> boundaries = LessonBoundaryDetector.detectBoundaries(pageTexts);
        if (!boundaries.isEmpty()) {
            List<DocumentChunk> lessonChunks = LessonBoundaryDetector.createLessonChunks(
                    pageTexts, boundaries, "SCANNED");
            log.info("Layer 1 (lesson-aware, scanned): {} lessons → {} chunks",
                    boundaries.size(), lessonChunks.size());

            // Layer 2: MnN intra-lesson section splitting
            List<DocumentChunk> sectionChunks = applyMnNSectionSplitting(lessonChunks);

            // Propagate OCR table rows to VOCABULARY section chunks
            propagateTableRows(sectionChunks, pageTableRows);

            // Propagate page images to VOCABULARY + GRAMMAR chunks for vision-first extraction (Tier 2)
            propagatePageImages(sectionChunks, pageImageMap);

            log.info("Layer 2 (section-aware, scanned): {} lesson chunks → {} section chunks",
                    lessonChunks.size(), sectionChunks.size());
            return sectionChunks;
        }

        log.info("No lesson boundaries detected in scanned PDF — using character-based chunking");
        return createCharacterBasedChunks(pageTexts);
    }

    /**
     * Attach structured table rows from OCR to VOCABULARY section chunks.
     *
     * <p>Each vocabulary chunk spans one or more pages. For every page that has
     * table rows, aggregate them and set them on the matching chunk.
     */
    private void propagateTableRows(List<DocumentChunk> chunks,
                                     Map<Integer, List<String>> pageTableRows) {
        if (pageTableRows.isEmpty()) return;
        for (DocumentChunk chunk : chunks) {
            if (!MnNSectionSplitter.VOCABULARY.equals(chunk.getSectionType())) continue;
            List<String> allRows = new ArrayList<>();
            // pageStart/pageEnd are 1-based; pageTableRows uses 0-based index
            for (int p = chunk.getPageStart(); p <= chunk.getPageEnd(); p++) {
                List<String> rows = pageTableRows.get(p - 1);
                if (rows != null) allRows.addAll(rows);
            }
            if (!allRows.isEmpty()) {
                chunk.setStructuredTableRows(allRows);
                log.debug("Propagated {} table rows to VOCABULARY chunk {} (pages {}-{})",
                        allRows.size(), chunk.getChunkIndex(),
                        chunk.getPageStart(), chunk.getPageEnd());
            }
        }
    }

    /**
     * Attach JPEG page images to VOCABULARY, GRAMMAR_PATTERN, and GRAMMAR_EXPLANATION chunks.
     *
     * <p>The vision-first extraction path (Tier 2) in {@code VocabularyExtractionAgent}
     * and {@code GrammarExtractionAgent} reads these images when OCR text quality
     * is insufficient. Only sections that benefit from visual layout analysis receive images.
     */
    private void propagatePageImages(List<DocumentChunk> chunks,
                                      Map<Integer, byte[]> pageImageMap) {
        if (pageImageMap.isEmpty()) return;
        for (DocumentChunk chunk : chunks) {
            String sectionType = chunk.getSectionType();
            if (!MnNSectionSplitter.VOCABULARY.equals(sectionType)
                    && !MnNSectionSplitter.GRAMMAR_PATTERN.equals(sectionType)
                    && !MnNSectionSplitter.GRAMMAR_EXPLANATION.equals(sectionType)) {
                continue;
            }
            List<byte[]> images = new ArrayList<>();
            // pageStart/pageEnd are 1-based; pageImageMap uses 0-based index
            for (int p = chunk.getPageStart(); p <= chunk.getPageEnd(); p++) {
                byte[] img = pageImageMap.get(p - 1);
                if (img != null) images.add(img);
            }
            if (!images.isEmpty()) {
                chunk.setPageImages(images);
                log.debug("Propagated {} page image(s) to {} chunk {} (pages {}-{})",
                        images.size(), sectionType, chunk.getChunkIndex(),
                        chunk.getPageStart(), chunk.getPageEnd());
            }
        }
    }

    /**
     * Apply MnN section splitting to each lesson chunk and re-index the result.
     *
     * <p>Each section child chunk receives the parent lesson's full content as
     * {@code fullPageContext} so extraction agents can see sibling sections together.
     */
    private List<DocumentChunk> applyMnNSectionSplitting(List<DocumentChunk> lessonChunks) {
        List<DocumentChunk> result = new ArrayList<>();
        for (DocumentChunk lessonChunk : lessonChunks) {
            String fullLessonText = lessonChunk.getContent();
            List<DocumentChunk> sectionChunks = MnNSectionSplitter.split(lessonChunk);
            for (DocumentChunk section : sectionChunks) {
                section.setFullPageContext(fullLessonText);
            }
            result.addAll(sectionChunks);
        }
        for (int i = 0; i < result.size(); i++) {
            result.get(i).setChunkIndex(i);
        }
        return result;
    }

    private List<DocumentChunk> createCharacterBasedChunks(List<String> pageTexts) {
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

    private byte[] encodeToJpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", baos);
        return baos.toByteArray();
    }

    private DocumentChunk buildChunk(int index, String content, int pageStart, int pageEnd) {
        return DocumentChunk.builder()
                .chunkIndex(index)
                .content(content)
                .fullPageContext(content) // same as content for char-based chunks
                .pageStart(pageStart)
                .pageEnd(pageEnd)
                .chunkType("SCANNED")           // signals OCR origin to downstream agents
                .estimatedTokenCount(content.length() / 4)
                .build();
    }
}

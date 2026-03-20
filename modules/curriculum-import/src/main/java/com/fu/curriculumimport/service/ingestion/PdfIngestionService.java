package com.fu.curriculumimport.service.ingestion;

import com.fu.curriculumimport.exception.InvalidImportFileException;
import com.fu.curriculumimport.util.LlmResponseUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Service for parsing PDF documents and splitting into chunks.
 * Uses Apache PDFBox 3.x for text extraction.
 */
@Service
@Slf4j
public class PdfIngestionService {

    /**
     * Maximum file size: 50MB
     */
    private static final long MAX_FILE_SIZE = 100 * 1024 * 1024;

    /**
     * Target chunk size in characters (~500 tokens ≈ 2000 chars)
     */
    private static final int TARGET_CHUNK_SIZE = 2000;

    /**
     * Overlap between chunks for context continuity
     */
    private static final int CHUNK_OVERLAP = 200;

    /**
     * Parse a PDF file and return text chunks.
     *
     * @param file the uploaded MultipartFile
     * @return parsed result with page texts and chunks
     */
    public DocumentParseResult parsePdf(MultipartFile file) {
        validateFile(file);

        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            int totalPages = document.getNumberOfPages();
            log.info("Parsing PDF: {} ({} pages)", file.getOriginalFilename(), totalPages);

            // Extract text page by page (with furigana normalization)
            PDFTextStripper stripper = new PDFTextStripper();
            List<String> pageTexts = new ArrayList<>();

            for (int i = 1; i <= totalPages; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String pageText = stripper.getText(document).trim();
                pageTexts.add(FuriganaNormalizer.normalize(pageText));
            }

            // Create chunks
            List<DocumentChunk> chunks = createChunks(pageTexts);

            log.info("PDF parsed: {} pages, {} chunks created",
                    totalPages, chunks.size());

            return DocumentParseResult.builder()
                    .totalPages(totalPages)
                    .fullText(String.join("\n\n", pageTexts))
                    .pageTexts(pageTexts)
                    .chunks(chunks)
                    .build();

        } catch (IOException e) {
            log.error("Failed to parse PDF: {}", file.getOriginalFilename(), e);
            throw new InvalidImportFileException("Failed to parse PDF file: " + e.getMessage());
        }
    }

    /**
     * Validate the uploaded file.
     */
    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidImportFileException("File is required and must not be empty");
        }

        String contentType = file.getContentType();
        String filename = file.getOriginalFilename();

        if (contentType == null || !contentType.equals("application/pdf")) {
            if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
                throw new InvalidImportFileException(
                        "Only PDF file type is supported. Received: " + contentType);
            }
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new InvalidImportFileException(
                    String.format("File size exceeds maximum limit of %dMB. File size: %dMB",
                            MAX_FILE_SIZE / (1024 * 1024),
                            file.getSize() / (1024 * 1024)));
        }
    }

    /**
     * Split page texts into chunks.
     *
     * <p>Strategy (in priority order):
     * <ol>
     *   <li><b>Lesson-aware + section-aware</b>: if "Bài N" (or similar) boundaries
     *       are detected, one chunk per lesson is created first (Layer 1), then each
     *       lesson chunk is further split by MnN functional sections such as
     *       "Từ vựng", "Mẫu câu", "Ngữ pháp" (Layer 2). This produces fine-grained,
     *       semantically homogeneous chunks that the extraction agents can process with
     *       high accuracy and confidence.</li>
     *   <li><b>Character-based fallback</b>: sliding-window chunks of
     *       ~{@value #TARGET_CHUNK_SIZE} chars with {@value #CHUNK_OVERLAP}-char overlap,
     *       used when no lesson markers are found.</li>
     * </ol>
     */
    private List<DocumentChunk> createChunks(List<String> pageTexts) {
        // Layer 1: lesson-aware chunking
        LinkedHashMap<Integer, Integer> boundaries = LessonBoundaryDetector.detectBoundaries(pageTexts);
        if (!boundaries.isEmpty()) {
            List<DocumentChunk> lessonChunks = LessonBoundaryDetector.createLessonChunks(
                    pageTexts, boundaries, "TEXT");
            log.info("Layer 1 (lesson-aware): {} lessons → {} chunks", boundaries.size(), lessonChunks.size());

            // Layer 2: intra-lesson section splitting (MnN structural chunking)
            List<DocumentChunk> sectionChunks = applyMnNSectionSplitting(lessonChunks);
            log.info("Layer 2 (section-aware): {} lesson chunks → {} section chunks",
                    lessonChunks.size(), sectionChunks.size());
            return sectionChunks;
        }

        // Fallback: character-based sliding-window
        log.info("No lesson boundaries detected — using character-based chunking");
        return createCharacterBasedChunks(pageTexts);
    }

    /**
     * Apply MnN section splitting to each lesson chunk and re-index the result.
     *
     * <p>Each section child chunk receives the parent lesson's full content as
     * {@code fullPageContext} so extraction agents can see sibling sections
     * (grammar pattern + examples + explanation) on the same lesson page.
     */
    private List<DocumentChunk> applyMnNSectionSplitting(List<DocumentChunk> lessonChunks) {
        List<DocumentChunk> result = new ArrayList<>();
        for (DocumentChunk lessonChunk : lessonChunks) {
            String fullLessonText = lessonChunk.getContent();
            List<DocumentChunk> sectionChunks = MnNSectionSplitter.split(lessonChunk);
            // Propagate full lesson text as page context for every child section chunk
            for (DocumentChunk section : sectionChunks) {
                section.setFullPageContext(fullLessonText);
            }
            result.addAll(sectionChunks);
        }
        // Re-index all section chunks sequentially from 0
        for (int i = 0; i < result.size(); i++) {
            result.get(i).setChunkIndex(i);
        }
        return result;
    }

    /**
     * Classic sliding-window character-based chunking (original behaviour).
     */
    private List<DocumentChunk> createCharacterBasedChunks(List<String> pageTexts) {
        List<DocumentChunk> chunks = new ArrayList<>();
        int chunkIndex = 0;

        StringBuilder currentChunk = new StringBuilder();
        int chunkPageStart = 1;

        for (int pageIdx = 0; pageIdx < pageTexts.size(); pageIdx++) {
            String pageText = pageTexts.get(pageIdx);
            int pageNum = pageIdx + 1;

            if (currentChunk.isEmpty()) {
                chunkPageStart = pageNum;
            }

            currentChunk.append(pageText).append("\n\n");

            if (currentChunk.length() >= TARGET_CHUNK_SIZE) {
                String chunkContent = currentChunk.toString().trim();
                chunks.add(DocumentChunk.builder()
                        .chunkIndex(chunkIndex++)
                        .content(chunkContent)
                        .fullPageContext(chunkContent) // same as content for char-based chunks
                        .pageStart(chunkPageStart)
                        .pageEnd(pageNum)
                        .chunkType("TEXT")
                        .estimatedTokenCount(LlmResponseUtils.estimateTokens(currentChunk.toString()))
                        .build());

                String overlapText = currentChunk.substring(
                        Math.max(0, currentChunk.length() - CHUNK_OVERLAP));
                currentChunk = new StringBuilder(overlapText);
                chunkPageStart = pageNum;
            }
        }

        if (!currentChunk.isEmpty()) {
            String chunkContent = currentChunk.toString().trim();
            chunks.add(DocumentChunk.builder()
                    .chunkIndex(chunkIndex)
                    .content(chunkContent)
                    .fullPageContext(chunkContent) // same as content for char-based chunks
                    .pageStart(chunkPageStart)
                    .pageEnd(pageTexts.size())
                    .chunkType("TEXT")
                    .estimatedTokenCount(LlmResponseUtils.estimateTokens(currentChunk.toString()))
                    .build());
        }

        return chunks;
    }

}

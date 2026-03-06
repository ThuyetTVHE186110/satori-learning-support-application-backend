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
    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024;

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

            // Extract text page by page
            PDFTextStripper stripper = new PDFTextStripper();
            List<String> pageTexts = new ArrayList<>();

            for (int i = 1; i <= totalPages; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                pageTexts.add(stripper.getText(document).trim());
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
     * Split page texts into overlapping chunks.
     * Groups pages into chunks of ~TARGET_CHUNK_SIZE characters.
     */
    private List<DocumentChunk> createChunks(List<String> pageTexts) {
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

            // If chunk is large enough, finalize it
            if (currentChunk.length() >= TARGET_CHUNK_SIZE) {
                chunks.add(DocumentChunk.builder()
                        .chunkIndex(chunkIndex++)
                        .content(currentChunk.toString().trim())
                        .pageStart(chunkPageStart)
                        .pageEnd(pageNum)
                        .chunkType("TEXT")
                        .estimatedTokenCount(LlmResponseUtils.estimateTokens(currentChunk.toString()))
                        .build());

                // Keep overlap from end for context continuity
                String overlapText = currentChunk.substring(
                        Math.max(0, currentChunk.length() - CHUNK_OVERLAP));
                currentChunk = new StringBuilder(overlapText);
                chunkPageStart = pageNum;
            }
        }

        // Final chunk
        if (!currentChunk.isEmpty()) {
            chunks.add(DocumentChunk.builder()
                    .chunkIndex(chunkIndex)
                    .content(currentChunk.toString().trim())
                    .pageStart(chunkPageStart)
                    .pageEnd(pageTexts.size())
                    .chunkType("TEXT")
                    .estimatedTokenCount(LlmResponseUtils.estimateTokens(currentChunk.toString()))
                    .build());
        }

        return chunks;
    }

}

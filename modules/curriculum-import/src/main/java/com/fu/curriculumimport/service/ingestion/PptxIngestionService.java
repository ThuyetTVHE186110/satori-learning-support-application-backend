package com.fu.curriculumimport.service.ingestion;

import com.fu.curriculumimport.exception.InvalidImportFileException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.xslf.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Ingestion service for <b>PowerPoint (.pptx) files</b>.
 *
 * <h3>Mapping strategy</h3>
 * <ul>
 *   <li>1 slide → 1 {@link DocumentChunk} (= 1 future lesson).</li>
 *   <li>The slide index is the {@code pageStart}/{@code pageEnd} of the chunk.</li>
 *   <li>Content is rendered as structured plain-text with section headers so
 *       the downstream classifier agent can immediately identify vocabulary
 *       lists, grammar patterns, and notes without extra prompt engineering.</li>
 * </ul>
 *
 * <h3>Structured text format per slide</h3>
 * <pre>
 * [SLIDE 3] タイトル: 病院・クリニック
 *
 * === TỪ VỰNG ===
 * 待合室
 * 診察
 * 処方箋
 *
 * === NGỮ PHÁP ===
 * 〜ていただけませんか
 * 丁寧な依頼・お願いの表現
 * 例：少々お待ちいただけませんか。
 *
 * === GHI CHÚ ===
 * (speaker notes from the slide)
 * </pre>
 *
 * <p>ChunkType is set to {@code "SLIDE"}.
 */
@Slf4j
@Service
public class PptxIngestionService {

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Parse all slides in the PPTX file. Each slide becomes one chunk.
     *
     * @param file validated .pptx MultipartFile
     * @return parsed result; one chunk per slide
     */
    public DocumentParseResult parse(MultipartFile file) {
        log.info("PPTX ingestion start: {}", file.getOriginalFilename());

        try (XMLSlideShow pptx = new XMLSlideShow(file.getInputStream())) {
            List<XSLFSlide> slides = pptx.getSlides();
            int totalSlides = slides.size();
            log.info("PPTX has {} slides", totalSlides);

            List<String> pagTexts = new ArrayList<>();
            List<DocumentChunk> chunks = new ArrayList<>();
            StringBuilder fullText = new StringBuilder();

            for (int i = 0; i < totalSlides; i++) {
                XSLFSlide slide = slides.get(i);
                String slideText = buildStructuredSlideText(slide, i + 1);

                pagTexts.add(slideText);
                fullText.append(slideText).append("\n\n");

                chunks.add(DocumentChunk.builder()
                        .chunkIndex(i)
                        .content(slideText)
                        .pageStart(i + 1)
                        .pageEnd(i + 1)
                        .chunkType("SLIDE")
                        .estimatedTokenCount(slideText.length() / 4)
                        .build());
            }

            log.info("PPTX ingestion done: {} slides → {} chunks", totalSlides, chunks.size());

            return DocumentParseResult.builder()
                    .totalPages(totalSlides)
                    .fullText(fullText.toString())
                    .pageTexts(pagTexts)
                    .chunks(chunks)
                    .build();

        } catch (IOException e) {
            log.error("Failed to parse PPTX: {}", file.getOriginalFilename(), e);
            throw new InvalidImportFileException("Failed to parse PPTX file: " + e.getMessage());
        }
    }

    // ── Slide → Structured Text ─────────────────────────────────────────────

    /**
     * Convert a single slide to the structured plain-text format.
     * Sections: title, body shapes (potential vocab/grammar lists), tables, notes.
     */
    private String buildStructuredSlideText(XSLFSlide slide, int slideNumber) {
        StringBuilder sb = new StringBuilder();

        String title = extractTitle(slide);
        sb.append("[SLIDE ").append(slideNumber).append("] タイトル: ")
                .append(title.isEmpty() ? "(no title)" : title)
                .append("\n\n");

        // ── Body shapes (text boxes, content placeholders) ──────────────────
        List<String> bodyLines = new ArrayList<>();
        for (XSLFShape shape : slide.getShapes()) {
            if (isTitleShape(shape)) continue; // already handled above

            if (shape instanceof XSLFTextShape textShape) {
                String shapeText = extractTextShapeContent(textShape);
                if (!shapeText.isBlank()) {
                    bodyLines.add(shapeText);
                }
            } else if (shape instanceof XSLFTable table) {
                String tableText = extractTableContent(table);
                if (!tableText.isBlank()) {
                    bodyLines.add(tableText);
                }
            }
        }

        if (!bodyLines.isEmpty()) {
            sb.append("=== NỘI DUNG ===\n");
            bodyLines.forEach(line -> sb.append(line).append("\n"));
            sb.append("\n");
        }

        // ── Speaker notes ────────────────────────────────────────────────────
        String notes = extractNotes(slide);
        if (!notes.isBlank()) {
            sb.append("=== GHI CHÚ ===\n").append(notes).append("\n");
        }

        return sb.toString().trim();
    }

    // ── Title extraction ─────────────────────────────────────────────────────

    private String extractTitle(XSLFSlide slide) {
        for (XSLFShape shape : slide.getShapes()) {
            if (shape instanceof XSLFTextShape textShape && isTitlePlaceholder(textShape)) {
                String text = getPlainText(textShape).trim();
                if (!text.isEmpty()) return text;
            }
        }
        return "";
    }

    private boolean isTitleShape(XSLFShape shape) {
        if (!(shape instanceof XSLFTextShape textShape)) return false;
        return isTitlePlaceholder(textShape);
    }

    /**
     * Returns true when the shape is a title or centred-title placeholder.
     * Uses the {@link Placeholder} API (POI 5.x) instead of the removed TextType enum.
     */
    private boolean isTitlePlaceholder(XSLFTextShape textShape) {
        // 1. Check the OOXML placeholder type (most reliable)
        try {
            var phDetails = textShape.getPlaceholderDetails();
            if (phDetails != null) {
                Placeholder ph = phDetails.getPlaceholder();
                if (ph == Placeholder.TITLE || ph == Placeholder.CENTERED_TITLE) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // getPlaceholderDetails() can throw on malformed XML — fall through
        }
        // 2. Fallback: shape name heuristic (POI sets names like "Title 1")
        String name = textShape.getShapeName();
        return name != null && name.toLowerCase().contains("title");
    }

    // ── Text-shape content ────────────────────────────────────────────────────

    /**
     * Extract all text from a text-shape, preserving paragraph breaks.
     * Bullet points are emitted as individual lines (natural for vocab lists).
     */
    private String extractTextShapeContent(XSLFTextShape shape) {
        StringBuilder sb = new StringBuilder();
        for (XSLFTextParagraph para : shape.getTextParagraphs()) {
            StringBuilder paraText = new StringBuilder();
            for (XSLFTextRun run : para.getTextRuns()) {
                String raw = run.getRawText();
                if (raw != null) paraText.append(raw);
            }
            String line = paraText.toString().trim();
            if (!line.isEmpty()) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private String getPlainText(XSLFTextShape shape) {
        StringBuilder sb = new StringBuilder();
        for (XSLFTextParagraph para : shape.getTextParagraphs()) {
            for (XSLFTextRun run : para.getTextRuns()) {
                if (run.getRawText() != null) sb.append(run.getRawText());
            }
            sb.append(" ");
        }
        return sb.toString().trim();
    }

    // ── Table content ─────────────────────────────────────────────────────────

    /**
     * Render a table as tab-separated rows.
     * Many Japanese textbook slides use tables for vocab: 単語 | 読み方 | 意味
     */
    private String extractTableContent(XSLFTable table) {
        StringBuilder sb = new StringBuilder();
        for (XSLFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XSLFTableCell cell : row.getCells()) {
                cells.add(getPlainText(cell).trim());
            }
            String rowText = String.join("\t", cells).trim();
            if (!rowText.isBlank()) {
                sb.append(rowText).append("\n");
            }
        }
        return sb.toString().trim();
    }

    // ── Speaker notes ─────────────────────────────────────────────────────────

    private String extractNotes(XSLFSlide slide) {
        try {
            XSLFNotes notes = slide.getNotes();
            if (notes == null) return "";
            StringBuilder sb = new StringBuilder();
            for (XSLFShape shape : notes.getShapes()) {
                if (shape instanceof XSLFTextShape textShape) {
                    // First text box in notes is typically the slide body placeholder — skip
                    String shapeName = shape.getShapeName() == null ? "" : shape.getShapeName();
                    if (shapeName.toLowerCase().contains("slide image")) continue;
                    String text = extractTextShapeContent(textShape);
                    if (!text.isBlank()) sb.append(text).append("\n");
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.debug("Could not extract notes from slide: {}", e.getMessage());
            return "";
        }
    }
}

package com.fu.curriculumimport.service.ingestion;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a chunk of text extracted from a document page.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentChunk {

    private int chunkIndex;
    private String content;
    private int pageStart;
    private int pageEnd;
    private String chunkType; // "TEXT", "TABLE", "MIXED", "SCANNED", "SLIDE"
    private int estimatedTokenCount;

    /**
     * Lesson number detected from "Bài N" (or similar) markers.
     * Null when using character-based chunking (no lesson boundaries detected).
     */
    private Integer lessonNumber;

    /**
     * Human-readable lesson title, e.g. "Bài 1".
     * Null when using character-based chunking.
     */
    private String lessonTitle;

    /**
     * Functional section type within a lesson, set by {@link MnNSectionSplitter}.
     * Possible values: VOCABULARY, GRAMMAR_PATTERN, EXAMPLE, DIALOG,
     * GRAMMAR_EXPLANATION, EXERCISE, UNKNOWN.
     * Null when section-aware splitting was not applied.
     */
    private String sectionType;

    /**
     * Full text of the page / lesson this chunk was extracted from.
     *
     * <p>For section-aware chunks (from MnNSectionSplitter), this is the entire
     * parent lesson text — so extraction agents can see all sibling sections on
     * the same page (e.g. grammar pattern + examples + explanation together).
     *
     * <p>For character-based chunks this is identical to {@code content}.
     * Never null after ingestion.
     */
    private String fullPageContext;

    /**
     * Structured table rows extracted from this page by the OCR service (optional).
     *
     * <p>Each entry is one detected table row: a tab-separated string of cells,
     * e.g. {@code "1\t行きます\tいきます\t(động từ nhóm I) đi\t"}.
     * When non-empty, {@link com.fu.curriculumimport.service.ingestion.MnNTableParser}
     * can convert these directly into ExtractedVocabulary items without an LLM call.
     */
    private java.util.List<String> structuredTableRows;

    /**
     * JPEG-encoded page images for the pages covered by this chunk.
     *
     * <p>Only populated for {@code SCANNED} chunks whose section type is
     * {@code VOCABULARY}, {@code GRAMMAR_PATTERN}, or {@code GRAMMAR_EXPLANATION}.
     * The vision-first extraction path (Tier 2) sends these images directly to
     * GPT-4o instead of relying on OCR text, preserving the original table layout
     * and furigana positions.
     *
     * <p>Null for text-based chunks and sections that don't benefit from vision.
     */
    private java.util.List<byte[]> pageImages;
}

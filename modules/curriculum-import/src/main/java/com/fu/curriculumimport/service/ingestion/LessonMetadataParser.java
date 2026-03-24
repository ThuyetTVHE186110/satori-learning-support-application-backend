package com.fu.curriculumimport.service.ingestion;

import com.fu.curriculumimport.service.agent.ExtractionResult;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts lesson metadata (title, description) directly from chunk content
 * using structural patterns — no LLM call needed.
 *
 * <p>Reuses the same lesson-header regex as {@link LessonBoundaryDetector}
 * to detect "Bài N" / "Lesson N" / "第N課" markers. Description is taken
 * from the first 200 characters after the lesson header.
 *
 * <p>This replaces ContentExtractionAgent for MIXED chunks, eliminating
 * 1/3 of LLM API calls in the extraction stage.
 */
public final class LessonMetadataParser {

    /** Same pattern as LessonBoundaryDetector.LESSON_PATTERN. */
    private static final Pattern LESSON_PATTERN = Pattern.compile(
            "(?imu)" +
            "(?:^|\\n)[ \\t#]*" +
            "(?:" +
              "b[\\u00e0\\u00e1\\u1ea3\\u00e3\\u1ea1a4][il1]" +
              "|lesson" +
              "|\u7b2c" +
            ")" +
            "[ \\t]*(\\d{1,2})" +
            "(?=[ \\t\\u8ab2:\\-\\.\\u2013\\r\\n]|$)"
    );

    private LessonMetadataParser() {}

    /**
     * Parse lesson metadata from chunk content.
     *
     * @param chunk the document chunk
     * @return an ExtractedLesson with title and description, or {@code null}
     *         if no lesson marker is found (persistence layer will auto-generate)
     */
    public static ExtractionResult.ExtractedLesson parseFromChunk(DocumentChunk chunk) {
        if (chunk == null || chunk.getContent() == null) return null;

        String title = chunk.getLessonTitle();
        int orderIndex = chunk.getLessonNumber() != null ? chunk.getLessonNumber() : 0;

        // If no lesson title from chunker, try to detect from content
        if (title == null || title.isBlank()) {
            Matcher m = LESSON_PATTERN.matcher(chunk.getContent());
            if (m.find()) {
                int lessonNum = Integer.parseInt(m.group(1));
                title = "Bài " + lessonNum;
                orderIndex = lessonNum;
            } else {
                return null;
            }
        }

        // MnN textbooks don't have per-lesson descriptions — leave null
        // so managers can fill in descriptions manually if needed.
        return ExtractionResult.ExtractedLesson.builder()
                .title(title)
                .description(null)
                .orderIndex(orderIndex)
                .pageStart(chunk.getPageStart())
                .pageEnd(chunk.getPageEnd())
                .build();
    }

    private static String extractDescription(String content, String title) {
        // Find the lesson header position and take text after it
        int headerIdx = content.toLowerCase().indexOf(title.toLowerCase());
        int startIdx = headerIdx >= 0 ? headerIdx + title.length() : 0;

        // Skip whitespace/newlines after header
        while (startIdx < content.length() && Character.isWhitespace(content.charAt(startIdx))) {
            startIdx++;
        }

        int endIdx = Math.min(startIdx + 200, content.length());
        String desc = content.substring(startIdx, endIdx).strip();

        // Clean up: remove trailing incomplete word
        int lastSpace = desc.lastIndexOf(' ');
        if (lastSpace > 100 && endIdx < content.length()) {
            desc = desc.substring(0, lastSpace) + "…";
        }

        return desc.isEmpty() ? "Imported lesson — " + title : desc;
    }
}

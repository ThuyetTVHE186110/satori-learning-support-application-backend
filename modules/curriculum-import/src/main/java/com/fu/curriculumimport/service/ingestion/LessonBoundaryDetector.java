package com.fu.curriculumimport.service.ingestion;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects lesson boundaries in Japanese/Vietnamese textbook PDFs and creates
 * lesson-aware chunks instead of character-based sliding-window chunks.
 *
 * <p>Supports the following lesson-header patterns:
 * <ul>
 *   <li>Vietnamese (standard):  {@code Bài 1}, {@code Bai 1}, {@code BÀI 1}, {@code BAI 1}</li>
 *   <li>Vietnamese (OCR errors): {@code Bal 1} (i→l), {@code Ba1 1} (i→1), {@code B4i 1} (à→4),
 *       {@code Bái 1}, {@code Bải 1}, {@code Bãi 1} (other accent misreads)</li>
 *   <li>Vietnamese (inline title): {@code Bài 2 Đây là bút chì} (text after number is now matched)</li>
 *   <li>English: {@code Lesson 1}, {@code LESSON 1}</li>
 *   <li>Japanese: {@code 第1課}, {@code 第１課}</li>
 * </ul>
 *
 * <p>Activation rule: at least {@value #MIN_LESSONS_TO_ACTIVATE} distinct lesson
 * numbers must be detected before lesson-aware chunking is enabled.
 * Otherwise the caller should fall back to character-based chunking.
 */
@Slf4j
public final class LessonBoundaryDetector {

    /**
     * Max characters allowed in a single lesson chunk before it gets split.
     *
     * <p>Sizing rationale (Minna no Nihongo N5 / scanned Vietnamese translation):
     * <ul>
     *   <li>Each lesson spans ~5–6 pages on average.</li>
     *   <li>PaddleOCR typically produces ~1 500–2 500 chars per page for mixed
     *       Japanese/Vietnamese content.</li>
     *   <li>Worst case: 6 pages × 2 500 chars = ~15 000 chars per lesson.</li>
     * </ul>
     * Set to 18 000 to keep an entire lesson in a single chunk with headroom.
     * GPT-4o-mini context limit is 128 K tokens; 18 000 chars ≈ 4 500 tokens — well within budget.
     */
    public static final int MAX_LESSON_CHUNK_SIZE = 18_000;

    /**
     * Minimum number of distinct lesson numbers needed to activate lesson-aware chunking.
     * Lowered to 2 so short documents (e.g. a sample chapter) still get lesson-aware chunking.
     */
    private static final int MIN_LESSONS_TO_ACTIVATE = 2;

    /**
     * Matches lines containing a lesson header.
     * Groups:
     *   group 1 — lesson number (1–50)
     *
     * Vietnamese "Bài" matching:
     *  - Standard accented:  à = \u00e0  (Bài)
     *  - Other OCR-confused accents: á \u00e1, ả \u1ea3, ã \u00e3, ạ \u1ea1 (Bái/Bải/Bãi/Bại)
     *  - Plain form:         a           (Bai  — OCR drops diacritic)
     *  - Digit lookalike:    4           (B4i  — OCR reads à as 4 on poor scans)
     *  - Trailing i vs OCR confusion:
     *      i  (standard)
     *      l  (OCR reads i as l — very common)
     *      1  (OCR reads i as 1)
     * The {@code u} (UNICODE_CASE) flag ensures BÀI / BAI etc. also match.
     * The lesson number may follow directly without a space (e.g. "Bai3").
     *
     * Fix 1 — lookahead terminator (instead of consuming):
     *   Original: (?:[課:\\-.–\r\n]|$)  — required punctuation/newline directly after the number.
     *   Fixed:    (?=[ \t課:\\-.–\r\n]|$) — also allows space+text after number,
     *   so "Bài 2 Đây là bút chì" now matches (common Vietnamese textbook format).
     *   The lookahead also prevents "Bài 250" matching as lesson 25: after "25" the regex
     *   sees "0" which is not in the allowed set, causing a backtrack that ultimately fails.
     */
    private static final Pattern LESSON_PATTERN = Pattern.compile(
            "(?imu)" +                                        // case-insensitive + multiline + unicode-case
            "(?:^|\\n)[ \\t]*" +                             // start of line (with optional indent)
            "(?:" +
              // Vietnamese: standard Bài + OCR-confused accent variants + i/l/1 confusion
              "b[\\u00e0\\u00e1\\u1ea3\\u00e3\\u1ea1a4][il1]" +
              "|lesson" +                                     // English:  Lesson / LESSON
              "|\u7b2c" +                                     // Japanese: 第
            ")" +
            "[ \\t]*(\\d{1,2})" +                            // capture 1-2 digit lesson number
            // Fix 1: lookahead — space, tab, 課, punctuation, en-dash, newline, or EOL.
            // Non-consuming so the terminator character stays available to the next match.
            // Also blocks "Bài 250" from matching as lesson 25 (digit after number fails).
            "(?=[ \\t\\u8ab2:\\-\\.\\u2013\\r\\n]|$)"
    );

    private LessonBoundaryDetector() {}

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Detect which page each lesson starts on.
     *
     * @param pageTexts list of per-page text (index 0 = page 1)
     * @return sorted map of pageNumber → lessonNumber;
     *         <b>empty map</b> if not enough boundaries were found (use char-based chunking)
     */
    /**
     * Minimum distinct lesson numbers on a single page to classify it as a
     * table-of-contents (TOC) page and skip it from boundary detection.
     *
     * <p>A real lesson page typically contains only its own "Bài N" header.
     * A TOC page lists many lessons (e.g. "Bài 1 ... 5 / Bài 2 ... 10 / …").
     */
    private static final int TOC_PAGE_LESSON_THRESHOLD = 4;

    public static LinkedHashMap<Integer, Integer> detectBoundaries(List<String> pageTexts) {
        // lessonNumber → first page it appears on
        TreeMap<Integer, Integer> lessonFirstPage = new TreeMap<>();

        for (int i = 0; i < pageTexts.size(); i++) {
            String pageText = pageTexts.get(i);
            if (pageText == null || pageText.isBlank()) continue;

            // Collect all distinct lesson numbers on this page
            Set<Integer> lessonsOnPage = new LinkedHashSet<>();
            Matcher m = LESSON_PATTERN.matcher(pageText);
            while (m.find()) {
                int n = Integer.parseInt(m.group(1));
                if (n >= 1 && n <= 50) lessonsOnPage.add(n);
            }

            // Skip pages that look like a table of contents
            if (lessonsOnPage.size() >= TOC_PAGE_LESSON_THRESHOLD) {
                log.debug("Page {} skipped — looks like TOC ({} lesson numbers found): {}",
                        i + 1, lessonsOnPage.size(), lessonsOnPage);
                continue;
            }

            for (int lessonNum : lessonsOnPage) {
                // Only record first occurrence per lesson number
                lessonFirstPage.putIfAbsent(lessonNum, i + 1); // 1-based page
            }
        }

        if (lessonFirstPage.size() < MIN_LESSONS_TO_ACTIVATE) {
            log.debug("Only {} lesson boundary/boundaries detected — falling back to character chunking",
                    lessonFirstPage.size());
            return new LinkedHashMap<>();
        }

        log.info("Detected {} lesson boundaries: {}", lessonFirstPage.size(), lessonFirstPage);

        // Reorder as pageNumber → lessonNumber (sorted by page)
        LinkedHashMap<Integer, Integer> pageToLesson = new LinkedHashMap<>();
        lessonFirstPage.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())           // sort by page number
                .forEach(e -> pageToLesson.put(e.getValue(), e.getKey()));

        return pageToLesson;
    }

    /**
     * Create lesson-aware chunks using a page-by-page pass with secondary boundary detection.
     *
     * <h3>Two-tier boundary detection</h3>
     * <ol>
     *   <li><b>Primary</b>: boundaries from {@link #detectBoundaries} (pre-computed).</li>
     *   <li><b>Secondary scan</b>: on every page that is NOT a primary boundary, the page
     *       text is re-scanned with {@code LESSON_PATTERN}. If a lesson number
     *       <em>greater than the current lesson</em> is found, it is treated as a missed
     *       boundary and the current lesson chunk is flushed immediately.
     *       The "greater than" guard prevents false positives from back-references
     *       such as "Xem lại Bài 3" appearing deep inside lesson 7.</li>
     * </ol>
     *
     * <p>Oversized lessons (content {@literal >} {@value #MAX_LESSON_CHUNK_SIZE} chars)
     * are split at paragraph boundaries; all sub-chunks share the same
     * {@code lessonNumber} / {@code lessonTitle} so the persistence layer can
     * re-merge them into a single lesson entity.
     *
     * @param pageTexts      per-page OCR/text content (index 0 = page 1)
     * @param pageBoundaries output of {@link #detectBoundaries}
     * @param chunkType      chunk type tag ("TEXT" / "SCANNED")
     * @return ordered list of lesson-tagged {@link DocumentChunk}s
     */
    public static List<DocumentChunk> createLessonChunks(
            List<String> pageTexts,
            LinkedHashMap<Integer, Integer> pageBoundaries,
            String chunkType) {

        if (pageBoundaries.isEmpty()) return Collections.emptyList();

        List<DocumentChunk> chunks = new ArrayList<>();
        int[] chunkIndex = {0};          // boxed so flushLesson lambda can mutate it

        Integer currentLesson  = null;
        int     lessonStart    = 1;
        StringBuilder content  = new StringBuilder();

        for (int p = 1; p <= pageTexts.size(); p++) {
            String text = pageTexts.get(p - 1);

            // ── Determine if a new lesson starts on this page ────────────────
            Integer newLesson = pageBoundaries.get(p);   // primary boundary

            if (newLesson == null && text != null && !text.isBlank()) {
                // Secondary scan: re-run the same pattern on page text.
                // Only accept a lesson number strictly greater than current to
                // avoid splitting on back-references ("Xem lại Bài 3" in lesson 7).
                Integer scanned = firstLessonNumberIn(text);
                if (scanned != null && (currentLesson == null || scanned > currentLesson)) {
                    newLesson = scanned;
                    log.info("Secondary scan: missed boundary Bài {} found on page {} " +
                                    "(was inside chunk for Bài {})",
                            scanned, p, currentLesson);
                }
            }

            // ── Flush current lesson before starting the new one ─────────────
            if (newLesson != null && !newLesson.equals(currentLesson)) {
                if (currentLesson != null && !content.isEmpty()) {
                    chunkIndex[0] = flushLesson(chunks, chunkIndex[0],
                            content.toString().trim(),
                            lessonStart, p - 1, chunkType, currentLesson);
                }
                currentLesson = newLesson;
                lessonStart   = p;
                content       = new StringBuilder();
            }

            // ── Accumulate page text into current lesson ─────────────────────
            if (currentLesson != null && text != null && !text.isBlank()) {
                content.append(text).append("\n\n");
            }
        }

        // Flush the final lesson
        if (currentLesson != null && !content.isEmpty()) {
            flushLesson(chunks, chunkIndex[0],
                    content.toString().trim(),
                    lessonStart, pageTexts.size(), chunkType, currentLesson);
        }

        return chunks;
    }

    /**
     * Returns the first lesson number matched by {@link #LESSON_PATTERN} in {@code text},
     * or {@code null} if none found. Used by the secondary boundary scan.
     */
    private static Integer firstLessonNumberIn(String text) {
        Matcher m = LESSON_PATTERN.matcher(text);
        if (m.find()) {
            int num = Integer.parseInt(m.group(1));
            return (num >= 1 && num <= 50) ? num : null;
        }
        return null;
    }

    /**
     * Flush accumulated lesson content into one or more {@link DocumentChunk}s.
     * Oversized content is split at paragraph boundaries.
     *
     * @return the next available chunk index
     */
    private static int flushLesson(List<DocumentChunk> chunks, int chunkIndex,
                                    String content, int pageStart, int pageEnd,
                                    String chunkType, int lessonNum) {
        if (content.isEmpty()) return chunkIndex;

        String lessonTitle = "Bài " + lessonNum;

        if (content.length() <= MAX_LESSON_CHUNK_SIZE) {
            chunks.add(buildChunk(chunkIndex++, content, pageStart, pageEnd,
                    chunkType, lessonNum, lessonTitle));
        } else {
            List<String> segments = splitAtParagraphs(content, MAX_LESSON_CHUNK_SIZE);
            int totalChars = content.length();
            int totalPages = Math.max(1, pageEnd - pageStart + 1);
            int charOffset = 0;

            for (int i = 0; i < segments.size(); i++) {
                String segment = segments.get(i);
                // Estimate page range for this segment proportional to its char offset
                int segPageStart = pageStart + (int) ((double) charOffset / totalChars * totalPages);
                charOffset += segment.length();
                int segPageEnd = (i == segments.size() - 1)
                        ? pageEnd
                        : pageStart + (int) ((double) charOffset / totalChars * totalPages) - 1;
                // Clamp to lesson bounds
                segPageStart = Math.max(pageStart, Math.min(segPageStart, pageEnd));
                segPageEnd   = Math.max(segPageStart, Math.min(segPageEnd, pageEnd));
                chunks.add(buildChunk(chunkIndex++, segment, segPageStart, segPageEnd,
                        chunkType, lessonNum, lessonTitle));
            }
            log.debug("Lesson {} split into {} sub-chunks (total {} chars, pages {}-{})",
                    lessonNum, segments.size(), content.length(), pageStart, pageEnd);
        }
        return chunkIndex;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static DocumentChunk buildChunk(int idx, String content,
                                             int pageStart, int pageEnd,
                                             String chunkType,
                                             int lessonNum, String lessonTitle) {
        return DocumentChunk.builder()
                .chunkIndex(idx)
                .content(content)
                .pageStart(pageStart)
                .pageEnd(pageEnd)
                .chunkType(chunkType)
                .lessonNumber(lessonNum)
                .lessonTitle(lessonTitle)
                .estimatedTokenCount(content.length() / 4)
                .build();
    }

    /**
     * Split {@code text} into segments no larger than {@code maxSize},
     * preferring paragraph breaks ({@code \n\n}) over mid-sentence cuts.
     */
    static List<String> splitAtParagraphs(String text, int maxSize) {
        List<String> segments = new ArrayList<>();
        String[] paragraphs = text.split("\\n\\n+");
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            if (!current.isEmpty() && current.length() + para.length() + 2 > maxSize) {
                segments.add(current.toString().trim());
                current = new StringBuilder();
            }
            if (!current.isEmpty()) current.append("\n\n");
            current.append(para);

            // If a single paragraph exceeds maxSize, force-split it
            while (current.length() > maxSize) {
                segments.add(current.substring(0, maxSize).trim());
                current = new StringBuilder(current.substring(maxSize));
            }
        }

        if (!current.isEmpty()) {
            segments.add(current.toString().trim());
        }

        return segments;
    }
}

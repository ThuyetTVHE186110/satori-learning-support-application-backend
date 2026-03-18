package com.fu.curriculumimport.service.ingestion;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Layer-2 intra-lesson structural splitter for the <em>Minna no Nihongo</em> (MnN) series.
 *
 * <h3>Problem</h3>
 * After {@link LessonBoundaryDetector} groups pages into lesson-level chunks (Layer 1),
 * each lesson chunk is still a <em>heterogeneous blob</em> that contains vocabulary
 * lists, sentence patterns, example sentences, dialogues, grammar notes, and exercises
 * all mixed together. Sending such a blob to a single extraction agent forces it to
 * guess the structure, leading to missed items, hallucinations, and low confidence.
 *
 * <h3>Solution: Functional-Block Segmentation</h3>
 * This splitter detects MnN section headers and breaks each lesson chunk into
 * semantically homogeneous <em>child chunks</em>. Each child chunk is tagged with a
 * {@link DocumentChunk#getSectionType()}, which the downstream
 * {@link com.fu.curriculumimport.service.agent.ContentClassifierAgent} uses as a
 * structural signal — bypassing expensive LLM classification for well-known sections.
 *
 * <h3>MnN lesson structure (Vietnamese-translation edition)</h3>
 * <ol>
 *   <li><b>Từ vựng</b>  (語彙 / Kotoba)     — vocabulary list</li>
 *   <li><b>Mẫu câu</b>  (文型 / Bunkei)     — core sentence patterns</li>
 *   <li><b>Ví dụ</b>    (例文 / Reibun)      — illustrative examples</li>
 *   <li><b>Hội thoại</b>(会話 / Kaiwa)      — dialogue</li>
 *   <li><b>Ngữ pháp</b> (文法 / Bunpou)     — grammar explanation notes</li>
 *   <li><b>Bài tập</b>  (練習 / Renshuu)    — practice exercises (skipped by extractor)</li>
 * </ol>
 *
 * <h3>Activation</h3>
 * At least {@value #MIN_SECTIONS_TO_ACTIVATE} distinct sections must be detected
 * before splitting is applied. Otherwise the original chunk is returned unchanged
 * (with {@code sectionType = UNKNOWN}).
 *
 * <h3>Parent-Child traceability</h3>
 * All child chunks inherit the parent's {@code lessonNumber} and {@code lessonTitle},
 * so the persistence layer can still group them back into a single lesson entity.
 */
@Slf4j
public final class MnNSectionSplitter {

    // ── Section type constants ───────────────────────────────────────────────

    /** Vocabulary list section (語彙 / Từ vựng). */
    public static final String VOCABULARY          = "VOCABULARY";
    /** Core sentence patterns (文型 / Mẫu câu). */
    public static final String GRAMMAR_PATTERN     = "GRAMMAR_PATTERN";
    /** Illustrative example sentences (例文 / Ví dụ). */
    public static final String EXAMPLE             = "EXAMPLE";
    /** Dialogue / conversation section (会話 / Hội thoại). */
    public static final String DIALOG              = "DIALOG";
    /** Grammar explanation notes (文法 / Ngữ pháp). */
    public static final String GRAMMAR_EXPLANATION = "GRAMMAR_EXPLANATION";
    /** Practice exercises (練習 / Bài tập) — extraction is skipped for this type. */
    public static final String EXERCISE            = "EXERCISE";
    /** Unknown / unclassified — falls back to LLM classification. */
    public static final String UNKNOWN             = "UNKNOWN";

    /** Minimum distinct section headers required to trigger splitting. */
    private static final int MIN_SECTIONS_TO_ACTIVATE = 2;

    /**
     * Minimum number of non-whitespace characters in the body of a section
     * (all lines after the header line) for that section to be kept as a
     * separate chunk. Sections with a body smaller than this are merged into
     * the adjacent section rather than being emitted as standalone chunks.
     *
     * <p>Rationale: OCR pages often produce a section header followed by just
     * a page-number or sub-title line before the next section header — e.g.
     * {@code "Từ vựng\nII. Phần dịch"}. Without this guard such degenerate
     * sections would be sent to the extraction agent with essentially no content.
     */
    private static final int MIN_SECTION_BODY_CHARS = 80;

    /**
     * Maximum character length of a normalized (lowercased, whitespace-collapsed)
     * line that can qualify as a section header. Headers are short standalone titles.
     */
    private static final int MAX_HEADER_LINE_LENGTH = 60;

    // ── Keyword sets (normalized: lowercase, whitespace collapsed) ───────────

    private static final Set<String> VOCAB_KEYWORDS = Set.of(
            // Japanese
            "語彙", "ことば", "語　彙", "kotoba",
            // Vietnamese — with and without diacritics (OCR drop)
            "từ vựng", "tu vung", "tu vụng", "tuvung", "từvựng",
            // Uppercase OCR variants
            "tù vung", "tú vung"
    );

    private static final Set<String> GRAMMAR_PATTERN_KEYWORDS = Set.of(
            // Japanese
            "文型", "ぶんけい", "文　型", "bunkei",
            // Vietnamese
            "mẫu câu", "mau cau", "maucau", "mẫucâu",
            "mấu câu", "mau cầu"  // common OCR misreads
    );

    private static final Set<String> EXAMPLE_KEYWORDS = Set.of(
            // Japanese
            "例文", "れいぶん", "例　文", "reibun",
            // Vietnamese
            "ví dụ", "vi du", "vidu", "ví​dụ",
            "vi dụ", "ví du"  // partial OCR drop
    );

    private static final Set<String> DIALOG_KEYWORDS = Set.of(
            // Japanese
            "会話", "かいわ", "会　話", "kaiwa",
            // Vietnamese
            "hội thoại", "hoi thoai", "hoithoai", "hội​thoại",
            "hoi thoại", "hội thoai"
    );

    private static final Set<String> GRAMMAR_EXPLANATION_KEYWORDS = Set.of(
            // Japanese
            "文法ノート", "文法解説", "文法", "ぶんぽう", "bunpou", "bunpo",
            // Vietnamese
            "ngữ pháp", "ngu phap", "ngữpháp", "ngupháp",
            "ngu pháp", "ngữ phap"  // partial diacritic drop
    );

    private static final Set<String> EXERCISE_KEYWORDS = Set.of(
            // Japanese — 練習A / 練習Ａ / 練習a
            "練習a", "練習b", "練習c", "練習ａ", "練習ｂ", "練習ｃ",
            "renshuu a", "renshuu b", "renshuu c",
            "renshu a", "renshu b", "renshu c",
            // Vietnamese
            "bài tập", "bai tap", "luyện tập", "luyen tap",
            "bài​tập", "luyện​tập", "bàitập", "luyệntập"
    );

    private MnNSectionSplitter() {}

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Split a lesson-level chunk into functional section sub-chunks.
     *
     * <p>If fewer than {@value #MIN_SECTIONS_TO_ACTIVATE} section headers are detected
     * the original chunk is returned as a single-element list with
     * {@code sectionType = UNKNOWN}.
     *
     * @param lessonChunk a lesson-level DocumentChunk produced by {@link LessonBoundaryDetector}
     * @return ordered list of section-tagged child chunks (at least one element)
     */
    public static List<DocumentChunk> split(DocumentChunk lessonChunk) {
        String content = lessonChunk.getContent();
        if (content == null || content.isBlank()) {
            return List.of(stamp(lessonChunk, UNKNOWN));
        }

        String[] lines = content.split("\\r?\\n", -1);
        List<SectionBoundary> boundaries = detectBoundaries(lines);

        if (boundaries.size() < MIN_SECTIONS_TO_ACTIVATE) {
            String sole = boundaries.isEmpty() ? UNKNOWN : boundaries.get(0).sectionType();
            log.debug("Lesson '{}' chunk {}: {} section(s) found — keeping as single chunk ({})",
                    lessonChunk.getLessonTitle(), lessonChunk.getChunkIndex(),
                    boundaries.size(), sole);
            return List.of(stamp(lessonChunk, sole));
        }

        log.info("Lesson '{}' chunk {}: splitting into {} sections {}",
                lessonChunk.getLessonTitle(), lessonChunk.getChunkIndex(),
                boundaries.size(), boundaries.stream().map(SectionBoundary::sectionType).toList());

        return buildSectionChunks(lessonChunk, lines, boundaries);
    }

    // ── Detection ────────────────────────────────────────────────────────────

    private static List<SectionBoundary> detectBoundaries(String[] lines) {
        List<SectionBoundary> boundaries = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.length() > MAX_HEADER_LINE_LENGTH) continue;

            String sectionType = classify(line);
            if (sectionType == null) continue;

            // Deduplicate: skip if same type as immediately previous boundary
            if (!boundaries.isEmpty()
                    && boundaries.get(boundaries.size() - 1).sectionType().equals(sectionType)) {
                continue;
            }
            boundaries.add(new SectionBoundary(i, sectionType));
        }

        return boundaries;
    }

    /**
     * Classify a single (stripped) line as a section type, or {@code null} if not a header.
     * Matching is done against normalized forms (lowercase, spaces collapsed).
     */
    private static String classify(String line) {
        String normalized = line.toLowerCase()
                .replaceAll("[\\s\u3000\u200b\u00a0]+", " ")   // collapse all whitespace variants
                .strip();

        if (VOCAB_KEYWORDS.contains(normalized))             return VOCABULARY;
        if (GRAMMAR_PATTERN_KEYWORDS.contains(normalized))   return GRAMMAR_PATTERN;
        if (GRAMMAR_EXPLANATION_KEYWORDS.contains(normalized)) return GRAMMAR_EXPLANATION;
        if (EXAMPLE_KEYWORDS.contains(normalized))           return EXAMPLE;
        if (DIALOG_KEYWORDS.contains(normalized))            return DIALOG;
        if (EXERCISE_KEYWORDS.contains(normalized))          return EXERCISE;

        // Partial-match fallback for OCR lines that contain the header amid noise
        // (e.g., "▶ Từ vựng ◀"). Only applied when line is ≤ 40 chars to limit false positives.
        if (normalized.length() <= 40) {
            if (containsAny(normalized, VOCAB_KEYWORDS))               return VOCABULARY;
            if (containsAny(normalized, GRAMMAR_PATTERN_KEYWORDS))     return GRAMMAR_PATTERN;
            if (containsAny(normalized, GRAMMAR_EXPLANATION_KEYWORDS)) return GRAMMAR_EXPLANATION;
            if (containsAny(normalized, EXAMPLE_KEYWORDS))             return EXAMPLE;
            if (containsAny(normalized, DIALOG_KEYWORDS))              return DIALOG;
            if (containsAny(normalized, EXERCISE_KEYWORDS))            return EXERCISE;
        }

        return null;
    }

    private static boolean containsAny(String line, Set<String> keywords) {
        for (String kw : keywords) {
            if (line.contains(kw)) return true;
        }
        return false;
    }

    // ── Chunk construction ───────────────────────────────────────────────────

    /**
     * Build the list of section DocumentChunks.
     *
     * <h3>Trivial-body filtering</h3>
     * <p>Before emitting any chunk, the body of each candidate section
     * (all lines after the header line up to the next boundary) is measured.
     * Sections whose body contains fewer than {@value #MIN_SECTION_BODY_CHARS}
     * non-whitespace characters are considered trivial: their content is
     * merged into the <em>next</em> section (or the previous one if they are
     * last). After merging, if fewer than {@link #MIN_SECTIONS_TO_ACTIVATE}
     * real sections remain, the parent chunk is returned unchanged as a single
     * {@value #UNKNOWN} chunk.
     *
     * <p>Content preceding the first detected header is emitted as a preamble
     * chunk with type {@value #UNKNOWN} — it typically contains the lesson
     * title repetition or introductory text.
     *
     * <p>Child chunks are re-indexed sequentially starting from
     * {@code parent.chunkIndex * 1000} to guarantee uniqueness within the
     * document while preserving lesson ordering.
     */
    private static List<DocumentChunk> buildSectionChunks(
            DocumentChunk parent, String[] lines, List<SectionBoundary> boundaries) {

        // ── Step 1: compute candidate sections (startLine, endLine, type) ────
        record Candidate(int startLine, int endLine, String sectionType) {}
        List<Candidate> candidates = new ArrayList<>();
        for (int b = 0; b < boundaries.size(); b++) {
            int startLine = boundaries.get(b).lineIndex();
            int endLine   = (b + 1 < boundaries.size())
                    ? boundaries.get(b + 1).lineIndex() - 1
                    : lines.length - 1;
            candidates.add(new Candidate(startLine, endLine, boundaries.get(b).sectionType()));
        }

        // ── Step 2: merge trivial sections (body too small) into neighbours ──
        // "Body" = lines after the header line itself.
        // Walk forward: if a section body is trivial, append its content to the next one.
        // Any leftover trivials at the end are appended to the previous.
        List<Candidate> kept = new ArrayList<>();
        String pendingMergeContent = ""; // content accumulated from trivial sections waiting for a real host
        int    pendingStartLine    = -1;

        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            // Body = lines after the first (header) line
            String body = joinLines(lines, c.startLine() + 1, c.endLine());
            long meaningfulBodyChars = body.codePoints()
                    .filter(ch -> !Character.isWhitespace(ch))
                    .count();

            if (meaningfulBodyChars < MIN_SECTION_BODY_CHARS) {
                // Trivial — accumulate content to merge forward
                String raw = joinLines(lines, c.startLine(), c.endLine());
                if (pendingStartLine < 0) pendingStartLine = c.startLine();
                pendingMergeContent = pendingMergeContent.isBlank()
                        ? raw : pendingMergeContent + "\n" + raw;
                log.debug("Lesson '{}' chunk {}: section {} at line {} has trivial body ({} chars) — merging forward",
                        parent.getLessonTitle(), parent.getChunkIndex(),
                        c.sectionType(), c.startLine(), meaningfulBodyChars);
            } else {
                // Non-trivial section: prepend any pending trivial content
                String fullContent = pendingMergeContent.isBlank()
                        ? joinLines(lines, c.startLine(), c.endLine())
                        : pendingMergeContent + "\n" + joinLines(lines, c.startLine(), c.endLine());
                int effectiveStart = pendingStartLine >= 0 ? pendingStartLine : c.startLine();
                kept.add(new Candidate(effectiveStart, c.endLine(), c.sectionType()));
                // Replace the content for this candidate by patching lines — store in a wrapper
                // (We'll use the lineCharOffset-based page estimation using original boundaries)
                pendingMergeContent = "";
                pendingStartLine    = -1;
            }
        }

        // Any remaining pending trivial content: append to the last kept section
        if (!pendingMergeContent.isBlank() && !kept.isEmpty()) {
            Candidate last = kept.get(kept.size() - 1);
            // Extend the last candidate's endLine to include pending trivial lines
            int newEnd = Math.max(last.endLine(), candidates.get(candidates.size() - 1).endLine());
            kept.set(kept.size() - 1, new Candidate(last.startLine(), newEnd, last.sectionType()));
        }

        // ── Step 3: if too few sections survive, fall back to single chunk ───
        if (kept.size() < MIN_SECTIONS_TO_ACTIVATE) {
            log.debug("Lesson '{}' chunk {}: after trivial-body merge only {} section(s) left — keeping as single chunk",
                    parent.getLessonTitle(), parent.getChunkIndex(), kept.size());
            String sole = kept.isEmpty() ? UNKNOWN : kept.get(0).sectionType();
            return List.of(stamp(parent, sole));
        }

        // ── Step 4: pre-compute page offsets for proportional estimation ─────
        int[] lineCharOffset = new int[lines.length + 1];
        for (int i = 0; i < lines.length; i++) {
            lineCharOffset[i + 1] = lineCharOffset[i] + lines[i].length() + 1;
        }
        int totalChars = Math.max(1, lineCharOffset[lines.length]);
        int parentPages = Math.max(1, parent.getPageEnd() - parent.getPageStart() + 1);

        // ── Step 5: build result chunks ───────────────────────────────────────
        List<DocumentChunk> result = new ArrayList<>();

        // Preamble: lines before the first kept section header
        int firstHeaderLine = kept.get(0).startLine();
        if (firstHeaderLine > 0) {
            String preamble = joinLines(lines, 0, firstHeaderLine - 1);
            if (!preamble.isBlank()) {
                int[] pages = estimatePages(parent, lineCharOffset, 0, firstHeaderLine - 1,
                        totalChars, parentPages);
                result.add(buildChild(parent, preamble, UNKNOWN, pages[0], pages[1]));
            }
        }

        for (Candidate c : kept) {
            String sectionContent = joinLines(lines, c.startLine(), c.endLine());
            if (sectionContent.isBlank()) continue;
            int[] pages = estimatePages(parent, lineCharOffset,
                    c.startLine(), c.endLine(), totalChars, parentPages);
            result.add(buildChild(parent, sectionContent, c.sectionType(), pages[0], pages[1]));
        }

        // ── Step 6: re-index sequentially within the lesson namespace ─────────
        int base = parent.getChunkIndex() * 1000;
        for (int i = 0; i < result.size(); i++) {
            result.get(i).setChunkIndex(base + i);
        }

        return result;
    }

    /**
     * Estimate the page range (pageStart, pageEnd) for a span of lines within a parent chunk.
     *
     * <p>Uses a simple linear proportion: the char offset of the line relative to the
     * total parent content determines which page the section falls on. This is an
     * approximation — OCR lines are not uniform in density — but it is far more
     * useful than always reporting the full parent range (e.g., pages 18–210).
     *
     * @param parent          parent lesson chunk (source of absolute page numbers)
     * @param lineCharOffset  cumulative char offsets per line (length = lines.length + 1)
     * @param startLine       first line of the section (inclusive)
     * @param endLine         last line of the section (inclusive)
     * @param totalChars      total chars in parent (= lineCharOffset[lines.length])
     * @param parentPages     total pages spanned by parent (pageEnd - pageStart + 1)
     * @return int[2] { estimated pageStart, estimated pageEnd }, clamped to parent bounds
     */
    private static int[] estimatePages(DocumentChunk parent, int[] lineCharOffset,
                                        int startLine, int endLine,
                                        int totalChars, int parentPages) {
        int startChar = lineCharOffset[startLine];
        int endChar   = lineCharOffset[Math.min(endLine + 1, lineCharOffset.length - 1)];

        int estStart = parent.getPageStart()
                + (int) ((double) startChar / totalChars * parentPages);
        int estEnd   = parent.getPageStart()
                + (int) ((double) endChar   / totalChars * parentPages);

        // Clamp to parent bounds
        estStart = Math.max(parent.getPageStart(), Math.min(estStart, parent.getPageEnd()));
        estEnd   = Math.max(estStart, Math.min(estEnd, parent.getPageEnd()));
        return new int[]{ estStart, estEnd };
    }

    private static String joinLines(String[] lines, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to && i < lines.length; i++) {
            sb.append(lines[i]).append("\n");
        }
        return sb.toString().strip();
    }

    private static DocumentChunk buildChild(DocumentChunk parent, String content,
                                             String sectionType, int pageStart, int pageEnd) {
        return DocumentChunk.builder()
                .chunkIndex(0)           // placeholder — re-indexed after all children built
                .content(content)
                .pageStart(pageStart)
                .pageEnd(pageEnd)
                .chunkType(parent.getChunkType())
                .lessonNumber(parent.getLessonNumber())
                .lessonTitle(parent.getLessonTitle())
                .sectionType(sectionType)
                .estimatedTokenCount(content.length() / 4)
                .build();
    }

    /** Set sectionType on an existing chunk and return it. */
    private static DocumentChunk stamp(DocumentChunk chunk, String sectionType) {
        chunk.setSectionType(sectionType);
        return chunk;
    }

    // ── Value record ─────────────────────────────────────────────────────────

    private record SectionBoundary(int lineIndex, String sectionType) {}
}

package com.fu.curriculumimport.service.agent.prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits markdown grammar content into individual sections for per-section LLM extraction.
 *
 * <p>Layout Parser produces markdown with headings like:
 * <pre>
 * # IV. Giải thích ngữ pháp
 * ## 1. Danh từ は Danh từ です
 * - Trợ từ「は」biểu thị rằng ...
 * ## 2. Danh từ じゃ ありません
 * 「じゃ ありません」là thể phủ định ...
 * ## 〜か、〜か                          ← unnumbered heading-2
 * Đây là mẫu câu nghi vấn sắp xếp ...
 * ## Danh từ の Danh từ                  ← unnumbered heading-2
 * Ở Bài 1 chúng ta đã học ...
 * </pre>
 *
 * <p>Both numbered ({@code ## 1. Title}) and unnumbered ({@code ## Title}) headings
 * are detected. Unnumbered headings are accepted only when they contain Japanese
 * characters (hiragana, katakana, kanji, or {@code 〜}) — this distinguishes real
 * grammar points ({@code ## 〜か、〜か}) from Vietnamese-only sub-notes
 * ({@code ## Chú ý}) regardless of heading level.
 *
 * <p>Heading level ({@code #} count) is intentionally ignored because Document AI
 * produces inconsistent levels between runs for the same PDF.
 */
public final class MarkdownGrammarSplitter {

    private MarkdownGrammarSplitter() {}

    /**
     * A single grammar section extracted from markdown.
     *
     * @param stt           sequence number (1, 2, 3...)
     * @param headingTitle  the heading text (e.g. "Danh từ は Danh từ です")
     * @param fullSection   the complete section text including heading and body
     */
    public record GrammarSection(int stt, String headingTitle, String fullSection) {}

    /**
     * Matches any markdown heading at level 1–4 that looks like a grammar section.
     *
     * <p>Supports two forms:
     * <ul>
     *   <li><b>Numbered</b>: {@code ## 1. Danh từ は Danh từ です} — Group 2 = number</li>
     *   <li><b>Unnumbered</b>: {@code ## 〜か、〜か} — Group 2 = null</li>
     * </ul>
     *
     * <p>Group 1 = heading prefix ({@code ##}), Group 2 = optional number,
     * Group 3 = heading text after optional number+period.
     *
     * <p>Requires at least one {@code #} — bare lines without {@code #} are NOT headings
     * (they could be numbered list items in the body text like "1) ...").
     */
    private static final Pattern SECTION_HEADING = Pattern.compile(
            "^(#{1,4})\\s+(?:(\\d+)\\.\\s+)?(.+)$", Pattern.MULTILINE);

    /**
     * Top-level heading patterns to SKIP — these are chapter/part headers, not grammar sections.
     * E.g. "# IV. Giải thích ngữ pháp", "# Bài 1", "## I. Từ vựng"
     */
    private static final Pattern SKIP_HEADING = Pattern.compile(
            "(?i)^(IV|III|II|I|V)\\.\\s|^Bài\\s+\\d|^Giải\\s*thích|^Ngữ\\s*pháp$|" +
            "Từ\\s*vựng|Phần\\s*dịch|Mẫu\\s*câu|Ví\\s*dụ|Hội\\s*thoại|Luyện\\s*tập|" +
            "Bài\\s*tập|練習|会話|例文|文型");

    /**
     * Detects Japanese script (hiragana, katakana, kanji) or the grammar-notation
     * wave dash {@code 〜} (U+301C).  Used to distinguish real grammar headings
     * ({@code ## 〜か、〜か}) from Vietnamese-only sub-notes ({@code ## Chú ý}).
     */
    private static final Pattern JAPANESE_CHARS = Pattern.compile(
            "[\\u3040-\\u309F\\u30A0-\\u30FF\\u4E00-\\u9FFF\\u3005\\u301C〜]");

    /**
     * Split markdown content into individual grammar sections.
     *
     * <h3>Design principle</h3>
     * <p>Grammar section identity is determined by <b>content</b> (what the heading says),
     * not by <b>formatting</b> (how many {@code #} characters it has).  Document AI and
     * Layout Parser produce inconsistent heading levels between runs for the same PDF —
     * {@code ## 〜か、〜か} in one run becomes {@code ### 〜か、〜か} in the next.
     * Level-based filtering caused grammar counts to vary by 30–40 % across identical
     * imports (e.g. 14 vs 9).
     *
     * <p>A heading is treated as a grammar section boundary when:
     * <ol>
     *   <li>It passes {@link #SKIP_HEADING} (not a chapter/part header), AND</li>
     *   <li>It is <b>numbered</b> ({@code ## 1. Title}), OR it contains
     *       <b>Japanese characters</b> (hiragana, katakana, kanji, {@code 〜}).</li>
     * </ol>
     * Unnumbered, Vietnamese-only headings ({@code ## Chú ý}) are excluded because
     * they are sub-notes, not grammar points.
     *
     * @param content markdown text from a GRAMMAR_EXPLANATION chunk
     * @return list of sections in order; empty list if no headings found
     */
    public static List<GrammarSection> split(String content) {
        if (content == null || content.isBlank()) return List.of();

        Matcher matcher = SECTION_HEADING.matcher(content);

        // Collect all heading positions and metadata — heading level is intentionally
        // ignored for filtering.  SKIP_HEADING is the only filter.
        List<int[]> positions = new ArrayList<>();   // [start, end]
        List<Integer> numbers = new ArrayList<>();   // -1 for unnumbered
        List<String> titles = new ArrayList<>();

        while (matcher.find()) {
            String title = matcher.group(3).trim();
            if (SKIP_HEADING.matcher(title).find()) continue;

            boolean isNumbered = matcher.group(2) != null;
            // Unnumbered headings must contain Japanese to qualify as grammar points.
            // Pure-Vietnamese headings like "Chú ý" or "Ghi chú" are sub-notes.
            if (!isNumbered && !JAPANESE_CHARS.matcher(title).find()) continue;

            positions.add(new int[]{matcher.start(), matcher.end()});
            numbers.add(isNumbered ? Integer.parseInt(matcher.group(2)) : -1);
            titles.add(title);
        }

        if (positions.isEmpty()) return List.of();

        // Build sections with auto-incrementing STT for unnumbered headings
        List<GrammarSection> sections = new ArrayList<>(positions.size());
        int lastStt = 0;

        for (int i = 0; i < positions.size(); i++) {
            int sectionStart = positions.get(i)[0];
            int sectionEnd = (i + 1 < positions.size())
                    ? positions.get(i + 1)[0]
                    : content.length();

            String fullSection = content.substring(sectionStart, sectionEnd).trim();
            int stt = numbers.get(i) >= 0 ? numbers.get(i) : lastStt + 1;
            lastStt = stt;

            sections.add(new GrammarSection(stt, titles.get(i), fullSection));
        }

        return sections;
    }

    /**
     * Get the preamble text before the first section heading (e.g. "# IV. Giải thích ngữ pháp").
     * Returns empty string if no preamble or no headings found.
     */
    public static String getPreamble(String content) {
        if (content == null || content.isBlank()) return "";
        Matcher matcher = SECTION_HEADING.matcher(content);
        if (matcher.find() && matcher.start() > 0) {
            return content.substring(0, matcher.start()).trim();
        }
        return "";
    }
}

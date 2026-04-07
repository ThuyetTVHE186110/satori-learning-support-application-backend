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
 * are detected. Unnumbered headings continue the sequence number from the last
 * numbered heading.
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
     * Split markdown content into individual grammar sections.
     *
     * @param content markdown text from a GRAMMAR_EXPLANATION chunk
     * @return list of sections in order; empty list if no headings found
     */
    public static List<GrammarSection> split(String content) {
        if (content == null || content.isBlank()) return List.of();

        Matcher matcher = SECTION_HEADING.matcher(content);

        // Collect all heading positions and metadata
        List<int[]> positions = new ArrayList<>();   // [start, end]
        List<Integer> numbers = new ArrayList<>();   // -1 for unnumbered
        List<String> titles = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();    // heading depth (# count)

        while (matcher.find()) {
            String title = matcher.group(3).trim();

            // Skip chapter/part headers like "# IV. Giải thích ngữ pháp"
            if (SKIP_HEADING.matcher(title).find()) continue;

            positions.add(new int[]{matcher.start(), matcher.end()});
            numbers.add(matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : -1);
            titles.add(title);
            levels.add(matcher.group(1).length());
        }

        if (positions.isEmpty()) return List.of();

        // --- Grammar section boundary detection ---
        //
        // PDF parsers sometimes produce inconsistent heading levels for the same content type:
        //   ## IV. Giải thích ngữ pháp   ← chapter header (skipped above)
        //   ### 1. Danh từ₁ は...          ← grammar point at level 3
        //   ### 2. ...
        //   ## 4. Danh từ も              ← grammar point at level 2 (!)
        //
        // Using minimum-level filtering would miss the "###" grammar points.
        //
        // Key insight: numbered headings use "N." format (period), while subsection
        // explanations use "N)" format (parenthesis). The SECTION_HEADING regex only
        // captures group(2) when the format is "N." — so:
        //   ### 1. Danh từ₁ は ...  → group(2)="1"  → numbered → grammar point ✓
        //   ### 1) Trợ từ は        → group(2)=null → unnumbered → subsection body ✗
        //
        // Strategy:
        //   1. All numbered headings (N. format, any level) are grammar section boundaries.
        //   2. Unnumbered headings are included only when at the same level as the
        //      shallowest numbered heading (to handle documents that use unnumbered
        //      headings for grammar points like "## ～さん").
        int minNumberedLevel = 999;
        for (int i = 0; i < levels.size(); i++) {
            if (numbers.get(i) >= 0 && levels.get(i) < minNumberedLevel) {
                minNumberedLevel = levels.get(i);
            }
        }
        if (minNumberedLevel == 999) minNumberedLevel = 2; // no numbered headings found

        List<int[]> filteredPositions = new ArrayList<>();
        List<Integer> filteredNumbers = new ArrayList<>();
        List<String> filteredTitles = new ArrayList<>();
        for (int i = 0; i < positions.size(); i++) {
            boolean isNumbered = numbers.get(i) >= 0;                              // N. format
            boolean isUnnumberedAtPrimary = !isNumbered && levels.get(i) == minNumberedLevel;
            if (isNumbered || isUnnumberedAtPrimary) {
                filteredPositions.add(positions.get(i));
                filteredNumbers.add(numbers.get(i));
                filteredTitles.add(titles.get(i));
            }
        }
        positions = filteredPositions;
        numbers = filteredNumbers;
        titles = filteredTitles;

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

            // Determine STT: use explicit number if present, otherwise auto-increment
            int stt;
            if (numbers.get(i) >= 0) {
                stt = numbers.get(i);
            } else {
                stt = lastStt + 1;
            }
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

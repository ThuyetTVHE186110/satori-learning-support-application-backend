package com.fu.curriculumimport.service.ingestion;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes furigana annotations found in Japanese textbook PDF / OCR text.
 *
 * <p>Japanese educational materials annotate kanji with their hiragana/katakana
 * readings inside brackets. Different editors and OCR engines produce different
 * bracket styles; extra spaces are also common in scanned sources. This normalizer
 * converts all variants to a single canonical {@code word[reading]} form so that
 * downstream embedding and LLM extraction steps receive consistent, clean text.
 *
 * <h3>Supported input → canonical output</h3>
 * <pre>
 *   先生（せんせい）    →  先生[せんせい]   (full-width round brackets — most common)
 *   先生〔せんせい〕    →  先生[せんせい]   (lenticular brackets)
 *   先生｛せんせい｝    →  先生[せんせい]   (full-width curly braces)
 *   先生 ( せんせい )  →  先生[せんせい]   (half-width with spaces — OCR artifact)
 *   先生[せんせい]     →  先生[せんせい]   (already canonical — unchanged)
 * </pre>
 *
 * <p>Only content composed of hiragana, katakana, or the prolonged-sound mark
 * (ー) is treated as a furigana reading; other parenthetical content (English
 * abbreviations, numbers, etc.) is left untouched.
 */
public final class FuriganaNormalizer {

    /**
     * Unicode ranges accepted inside furigana brackets.
     * Hiragana \u3040-\u309F, Katakana \u30A0-\u30FF, prolonged-sound mark \u30FC,
     * small kana extensions \u31F0-\u31FF.
     */
    private static final String KANA_CHARS =
            "[\\u3040-\\u309F\\u30A0-\\u30FF\\u31F0-\\u31FF\\u30FC\\s]";

    /**
     * Pattern matching furigana-bracket variants.
     * <ul>
     *   <li>Group 1: full-width round  （…）</li>
     *   <li>Group 2: lenticular        〔…〕</li>
     *   <li>Group 3: full-width curly  ｛…｝</li>
     *   <li>Group 4: half-width spaced  ( … )  (OCR artifact — requires at least one space)</li>
     * </ul>
     * Spaces inside the brackets are stripped from the captured reading.
     */
    private static final Pattern FURIGANA_PATTERN = Pattern.compile(
            "（\\s*(" + KANA_CHARS + "+?)\\s*）"    +   // group 1: （）
            "|〔\\s*(" + KANA_CHARS + "+?)\\s*〕"    +   // group 2: 〔〕
            "|｛\\s*(" + KANA_CHARS + "+?)\\s*｝"    +   // group 3: ｛｝
            "|\\(\\s+(" + KANA_CHARS + "+?)\\s+\\)"      // group 4: ( ) with mandatory spaces
    );

    // ── Inline meaning patterns ───────────────────────────────────────────────

    /**
     * Matches: [CJK/kana word]（[any content ≤100 chars]）[optional trailing text]
     * Used to detect Vietnamese/English meanings attached inline to a Japanese word.
     * Full-width parentheses （）— most common in Vietnamese-edition textbooks.
     */
    private static final Pattern INLINE_MEANING_PATTERN = Pattern.compile(
            "^([\\u3040-\\u9FFF]+)（([^）]{1,100})）(.*)$"
    );

    /**
     * Same as {@link #INLINE_MEANING_PATTERN} but for OCR output that uses
     * half-width parentheses with surrounding spaces: word ( meaning )
     * Example: アメリカ ( Mý )  →  word=アメリカ, meaning=Mý
     * The word part may contain spaces (e.g. "中国 語 ( tiêng Trung Quóc )").
     */
    private static final Pattern INLINE_MEANING_HALF_WIDTH = Pattern.compile(
            "^([\\u3040-\\u9FFF][\\u3040-\\u9FFF\\s]*)\\(([^)]{1,100})\\)(.*)$"
    );

    /**
     * Content matching this pattern is furigana (kana-only), NOT a meaning.
     * Hiragana \u3040-\u309F, Katakana \u30A0-\u30FF, small-kana \u31F0-\u31FF,
     * prolonged-sound mark \u30FC, and whitespace.
     */
    private static final Pattern KANA_ONLY = Pattern.compile(
            "^[\\u3040-\\u30FF\\u31F0-\\u31FF\\u30FC\\s]+$"
    );

    // ── Result type ───────────────────────────────────────────────────────────

    /**
     * Result of inline-meaning detection for a single vocabulary cell or line.
     *
     * @param word    the Japanese word stripped of any attached parenthetical
     * @param meaning the non-kana content found in （）; {@code null} when {@code found} is false
     * @param found   {@code true} when a non-kana parenthetical was detected and stripped
     */
    public record InlineMeaning(String word, String meaning, boolean found) {}

    // ── Constructor (utility class) ───────────────────────────────────────────

    private FuriganaNormalizer() {}

    /**
     * Normalize all furigana annotations in {@code text} to {@code [reading]} form.
     *
     * <p>Text without any furigana is returned unchanged. {@code null} or blank
     * input is returned as-is.
     *
     * @param text raw OCR / PDF extracted text
     * @return text with furigana normalized to square-bracket canonical form
     */
    public static String normalize(String text) {
        if (text == null || text.isBlank()) return text;

        return FURIGANA_PATTERN.matcher(text).replaceAll(match -> {
            for (int g = 1; g <= match.groupCount(); g++) {
                String reading = match.group(g);
                if (reading != null) {
                    // Strip internal whitespace (OCR may have spaced out kana)
                    return "[" + reading.replaceAll("\\s+", "") + "]";
                }
            }
            return match.group(); // should never reach here
        });
    }

    /**
     * Detect and extract a Vietnamese/English meaning attached inline to a Japanese word.
     *
     * <h3>Pattern matched</h3>
     * <pre>
     *   行きます（đi）            →  word="行きます",  meaning="đi"
     *   食べます（ăn cơm）        →  word="食べます",  meaning="ăn cơm"
     *   来ます（đt nhóm III）đến  →  word="来ます",    meaning="đt nhóm III）đến"
     * </pre>
     *
     * <h3>NOT matched (furigana — kana-only content)</h3>
     * <pre>
     *   先生（せんせい）   →  found=false  (kana-only → handled by {@link #normalize})
     *   学校（がっこう）   →  found=false
     * </pre>
     *
     * <p>This pattern is common in Vietnamese editions of Japanese textbooks such as
     * Minna no Nihongo, where vocabulary entries embed the Vietnamese translation in
     * full-width parentheses immediately after the Japanese word.
     *
     * @param text raw text of a single vocabulary cell or OCR line
     * @return {@link InlineMeaning}; when {@code found = false}, {@code word} equals
     *         the trimmed input and {@code meaning} is {@code null}
     */
    public static InlineMeaning extractInlineMeaning(String text) {
        if (text == null || text.isBlank()) return new InlineMeaning(text, null, false);

        String trimmed = text.trim();

        // ── Full-width parentheses （）— primary pattern ───────────────────────
        Matcher m = INLINE_MEANING_PATTERN.matcher(trimmed);
        if (m.matches()) {
            String inner = m.group(2).trim();
            // Kana-only content → furigana reading, not a Vietnamese/English meaning
            if (!KANA_ONLY.matcher(inner).matches()) {
                String trailing = m.group(3).trim();
                // Append any text that follows the closing bracket (e.g. extra meaning words)
                String meaning = trailing.isEmpty() ? inner : inner + " " + trailing;
                return new InlineMeaning(m.group(1).trim(), meaning, true);
            }
        }

        // ── Half-width parentheses with spaces ( ) — OCR output style ─────────
        // e.g. "アメリカ ( Mý )", "中国 語 ( tiêng Trung Quóc )"
        Matcher m2 = INLINE_MEANING_HALF_WIDTH.matcher(trimmed);
        if (m2.matches()) {
            String inner = m2.group(2).trim();
            if (!KANA_ONLY.matcher(inner).matches()) {
                String word = m2.group(1).trim();
                String trailing = m2.group(3).trim();
                String meaning = trailing.isEmpty() ? inner : inner + " " + trailing;
                return new InlineMeaning(word, meaning, true);
            }
        }

        return new InlineMeaning(trimmed, null, false);
    }
}

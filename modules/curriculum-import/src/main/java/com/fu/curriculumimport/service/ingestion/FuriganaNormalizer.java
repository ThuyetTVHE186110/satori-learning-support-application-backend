package com.fu.curriculumimport.service.ingestion;

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
}

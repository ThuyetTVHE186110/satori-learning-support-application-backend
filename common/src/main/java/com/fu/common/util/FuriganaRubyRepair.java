package com.fu.common.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts curator-authored paren-form readings ({@code 先生 (せんせい)}) into canonical ruby
 * syntax ({@code [先生]{せんせい}}) while preserving the reading the Content Manager chose.
 *
 * <p><b>Scope after the Kuromoji migration:</b> this class is NOT used for LLM write-paths
 * anymore — those use {@link FuriganaAnnotator} which tokenizes plain Japanese deterministically.
 * {@code FuriganaRubyRepair} remains for READ paths that surface curated vocabulary / grammar
 * data from the learning-content schema (see {@code CurriculumQueryTools}, {@code RetrievalAdvisor}),
 * where the DB stores the curator's paren form via {@code ContentEmbeddingEventListener.buildRichText}
 * and Kuromoji would override the pedagogically chosen reading.
 *
 * <p>Handles three degraded inputs that show up in curated data:
 *
 * <ul>
 *   <li>Paren form:            {@code 漢字 (かな)} (produced by vocabulary embedding {@code buildRichText})</li>
 *   <li>Legacy wiki style:     {@code 漢字[かな]} (older ingestion format still present in historical embeddings)</li>
 *   <li>Half-ruby leak:        {@code 漢字{かな}} (reading delimiter kept, base brackets dropped)</li>
 * </ul>
 *
 * <p>The kanji run is captured together with any trailing okurigana so
 * {@code 食べる{たべる}} becomes {@code [食べる]{たべる}} (not {@code [食]{たべる}べる}).
 *
 * <p>Idempotent: canonical input passes through untouched — negative lookbehind
 * on {@code [} blocks re-wrapping.
 *
 * <pre>
 *   食べる{たべる}         → [食べる]{たべる}
 *   先生[せんせい]         → [先生]{せんせい}
 *   先生 (せんせい)        → [先生]{せんせい}    (paren form — embedding rich text)
 *   先生(せんせい)         → [先生]{せんせい}    (paren form, no space)
 *   [先生]{せんせい}       → [先生]{せんせい}    (unchanged)
 *   お話[はなし]           → お[話]{はなし}      (hiragana prefix stays outside)
 *   アメリカ (Mỹ)          → アメリカ (Mỹ)       (non-kana paren content ignored)
 * </pre>
 */
public final class FuriganaRubyRepair {

    private static final String KANJI_HEAD = "[\\u4E00-\\u9FFF\\u3400-\\u4DBF々]";
    private static final String KANJI_BODY = "[\\u4E00-\\u9FFF\\u3400-\\u4DBF々\\u3040-\\u309F]*";
    private static final String KANA_READING = "[\\u3040-\\u309F\\u30A0-\\u30FF\\u30FCー]+";

    /** Kanji(+okurigana) directly followed by {@code {kana}} — LLM leak. */
    private static final Pattern HALF_RUBY = Pattern.compile(
            "(?<!\\[)(" + KANJI_HEAD + KANJI_BODY + ")\\{(" + KANA_READING + ")\\}"
    );

    /** Kanji(+okurigana) directly followed by {@code [kana]} — legacy wiki format. */
    private static final Pattern LEGACY_WIKI = Pattern.compile(
            "(?<!\\[)(" + KANJI_HEAD + KANJI_BODY + ")\\[(" + KANA_READING + ")\\]"
    );

    /**
     * Kanji(+okurigana) followed by optional space and a half-width paren
     * containing kana only — the format emitted by
     * {@code ContentEmbeddingEventListener.buildRichText()} when vocabulary
     * is indexed. Kana-only constraint inside the paren keeps this from
     * matching meaning-style content like {@code アメリカ (Mỹ)}.
     */
    private static final Pattern PAREN_FORM = Pattern.compile(
            "(?<!\\[)(" + KANJI_HEAD + KANJI_BODY + ")\\s?\\((" + KANA_READING + ")\\)"
    );

    private FuriganaRubyRepair() {}

    /**
     * Normalize all degraded furigana forms in {@code text} to {@code [漢字]{かな}}.
     * Null or empty input is returned unchanged.
     */
    public static String repair(String text) {
        if (text == null || text.isEmpty()) return text;
        String result = replaceAll(text, HALF_RUBY);
        result = replaceAll(result, LEGACY_WIKI);
        result = replaceAll(result, PAREN_FORM);
        return result;
    }

    private static String replaceAll(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) return text;
        m.reset();
        StringBuilder sb = new StringBuilder(text.length() + 16);
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement("[" + m.group(1) + "]{" + m.group(2) + "}"));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}

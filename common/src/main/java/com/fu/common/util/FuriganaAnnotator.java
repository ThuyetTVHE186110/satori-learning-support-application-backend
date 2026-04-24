package com.fu.common.util;

import com.atilika.kuromoji.ipadic.Token;
import com.atilika.kuromoji.ipadic.Tokenizer;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Generates canonical ruby-syntax furigana ({@code [漢字]{かな}}) from plain Japanese text
 * using Kuromoji IPADIC morphological analysis. Replaces LLM-generated furigana to avoid
 * hallucination patterns (double readings, kanji prefix duplication, kana wrapped in brackets).
 *
 * <p>Typical use: server receives plain Japanese from the LLM and calls
 * {@link #annotate(String)} before returning to the client. The LLM no longer needs to emit
 * a parallel furigana field.
 *
 * <p>For inputs that may contain degraded ruby syntax from older write paths, use
 * {@link #normalizeAndAnnotate(String)}: strips any existing ruby syntax back to plain text
 * and re-annotates — deterministic, always canonical.
 *
 * <p>The underlying {@link Tokenizer} is heavy (~4MB dictionary, ~200ms init). A lazy-init
 * holder keeps a JVM-wide singleton; modules that never call the annotator pay no init cost.
 */
public final class FuriganaAnnotator {

    private static final class Holder {
        static final Tokenizer INSTANCE = new Tokenizer.Builder().build();
    }

    /** Any ruby-like syntax — used to strip before re-annotating. */
    private static final Pattern CANONICAL_RUBY = Pattern.compile("\\[([^\\[\\]]+)\\]\\{[^{}]+\\}");
    // Match kanji head + optional okurigana body so 食べる{たべる} strips correctly.
    private static final String KANJI_WITH_OKURIGANA = "[\\u4E00-\\u9FFF\\u3400-\\u4DBF々][\\u4E00-\\u9FFF\\u3400-\\u4DBF々\\u3040-\\u309F]*";
    private static final Pattern HALF_RUBY_LEAK = Pattern.compile("(" + KANJI_WITH_OKURIGANA + ")\\{[\\u3040-\\u309F\\u30A0-\\u30FF\\u30FCー]+\\}");
    private static final Pattern LEGACY_WIKI = Pattern.compile("(" + KANJI_WITH_OKURIGANA + ")\\[[\\u3040-\\u309F\\u30A0-\\u30FF\\u30FCー]+\\]");

    private FuriganaAnnotator() {}

    /**
     * Annotate every token containing kanji with canonical ruby syntax {@code [surface]{reading}}.
     * Kana-only tokens and punctuation pass through unchanged. Tokens without a known reading
     * (Kuromoji returns {@code "*"}) also pass through unchanged.
     *
     * <p>Null or empty input returns unchanged.
     */
    public static String annotate(String text) {
        if (text == null || text.isEmpty()) return text;
        if (!containsKanji(text)) return text;

        List<Token> tokens = Holder.INSTANCE.tokenize(text);
        StringBuilder sb = new StringBuilder(text.length() + 32);
        for (Token t : tokens) {
            String surface = t.getSurface();
            if (!containsKanji(surface)) {
                sb.append(surface);
                continue;
            }
            String reading = t.getReading();
            if (reading == null || reading.isEmpty() || "*".equals(reading)) {
                sb.append(surface);
                continue;
            }
            sb.append('[').append(surface).append(']').append('{')
              .append(katakanaToHiragana(reading)).append('}');
        }
        return sb.toString();
    }

    /**
     * Strip any existing ruby/legacy furigana syntax from {@code text}, then annotate from
     * scratch. Use when the input may be contaminated by upstream LLM output.
     */
    public static String normalizeAndAnnotate(String text) {
        return annotate(stripAllFurigana(text));
    }

    /**
     * Remove every known furigana syntax variant, returning plain Japanese. Preserves base
     * kanji/kana text. Idempotent on text that has no furigana syntax.
     */
    public static String stripAllFurigana(String text) {
        if (text == null || text.isEmpty()) return text;
        String result = CANONICAL_RUBY.matcher(text).replaceAll("$1");
        result = HALF_RUBY_LEAK.matcher(result).replaceAll("$1");
        result = LEGACY_WIKI.matcher(result).replaceAll("$1");
        return result;
    }

    private static boolean containsKanji(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if ((cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF) || cp == 0x3005) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    /**
     * Convert a katakana-only string to hiragana. Kuromoji returns readings as katakana
     * (e.g. {@code タベル}); the client renders hiragana. Non-katakana code points are left
     * untouched so prolonged sound mark ({@code ー}) and anything unexpected survive.
     */
    private static String katakanaToHiragana(String kata) {
        StringBuilder sb = new StringBuilder(kata.length());
        for (int i = 0; i < kata.length(); ) {
            int cp = kata.codePointAt(i);
            if (cp >= 0x30A1 && cp <= 0x30F6) {
                sb.appendCodePoint(cp - 0x60);
            } else {
                sb.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return sb.toString();
    }
}

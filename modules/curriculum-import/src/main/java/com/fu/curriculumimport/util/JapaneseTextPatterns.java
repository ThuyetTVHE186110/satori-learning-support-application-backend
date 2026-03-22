package com.fu.curriculumimport.util;

import java.util.regex.Pattern;

/**
 * Shared regex patterns for Japanese text detection and validation.
 * Eliminates duplication across GroundingVerifier, ContentValidationAgent, etc.
 */
public final class JapaneseTextPatterns {

    private JapaneseTextPatterns() {}

    /** Hiragana characters (U+3040–U+309F). */
    public static final Pattern HIRAGANA = Pattern.compile("[\\u3040-\\u309F]");

    /** Katakana characters (U+30A0–U+30FF). */
    public static final Pattern KATAKANA = Pattern.compile("[\\u30A0-\\u30FF]");

    /** CJK Unified Ideographs / Kanji (U+4E00–U+9FAF). */
    public static final Pattern KANJI = Pattern.compile("[\\u4E00-\\u9FAF]");

    /** Any kana or kanji character. */
    public static final Pattern KANA_OR_KANJI = Pattern.compile("[\\u3040-\\u309F\\u30A0-\\u30FF\\u4E00-\\u9FAF]");

    /** Romaji-only string (latin letters, spaces, hyphens, apostrophes). */
    public static final Pattern ROMAJI = Pattern.compile("^[a-zA-Z\\s\\-']+$");

    /** Valid JLPT level format: N1 through N5. */
    public static final Pattern VALID_JLPT = Pattern.compile("^N[1-5]$");
}

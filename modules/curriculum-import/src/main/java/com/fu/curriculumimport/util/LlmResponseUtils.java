package com.fu.curriculumimport.util;

/**
 * Shared utilities for LLM response processing across all agents.
 */
public final class LlmResponseUtils {

    private LlmResponseUtils() {}

    /**
     * Clean markdown code fences from LLM JSON responses.
     * Handles ```json, ```, and mixed-case variants.
     */
    public static String cleanJson(String json) {
        if (json == null || json.isBlank()) return "{}";
        String cleaned = json.strip();
        // Handle ```json or ```JSON etc.
        if (cleaned.toLowerCase().startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        return cleaned.strip();
    }

    /**
     * Estimate token count from text.
     * Japanese text averages ~1.5 tokens per character (kanji/kana are tokenized differently).
     * Latin text averages ~0.25 tokens per character (4 chars per token).
     * We use a blended ratio suitable for mixed Japanese/English content.
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cjkChars = 0;
        int otherChars = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                cjkChars++;
            } else {
                otherChars++;
            }
        }
        // CJK: ~1.5 tokens per char, Latin: ~0.25 tokens per char
        return (int) (cjkChars * 1.5 + otherChars * 0.25);
    }

    private static boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS;
    }
}

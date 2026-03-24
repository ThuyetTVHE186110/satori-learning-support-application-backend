package com.fu.curriculumimport.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared utilities for LLM response processing across all agents.
 */
public final class LlmResponseUtils {

    private static final Logger log = LoggerFactory.getLogger(LlmResponseUtils.class);

    private LlmResponseUtils() {}

    /**
     * Clean markdown code fences from LLM JSON responses and repair truncated JSON.
     *
     * <p>When the LLM response hits maxTokens, the JSON is cut off mid-stream.
     * This method attempts to recover by finding the last complete array element
     * and closing all open brackets/braces.
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
        cleaned = cleaned.strip();

        // Check if JSON is properly closed
        if (isJsonComplete(cleaned)) {
            return cleaned;
        }

        // Truncated JSON — attempt repair
        log.warn("Detected truncated JSON response ({} chars), attempting repair", cleaned.length());
        return repairTruncatedJson(cleaned);
    }

    /**
     * Attempt to repair truncated JSON by finding the last complete object/array element
     * and closing all open brackets/braces.
     *
     * <p>Strategy: scan backwards from the truncation point to find the last valid
     * closing brace '}' that completes an array element, then close all remaining
     * open brackets and braces.
     */
    static String repairTruncatedJson(String json) {
        // Find the last complete object in an array: look for "}," or "}" followed by "]"
        // which indicates a fully closed array element.
        int lastCompleteObj = findLastCompleteObject(json);

        String trimmed;
        if (lastCompleteObj > 0) {
            trimmed = json.substring(0, lastCompleteObj + 1);
        } else {
            trimmed = json;
        }

        // Count open brackets/braces that need closing
        int openBraces = 0;
        int openBrackets = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            switch (c) {
                case '{' -> openBraces++;
                case '}' -> openBraces--;
                case '[' -> openBrackets++;
                case ']' -> openBrackets--;
            }
        }

        // Close all open structures
        StringBuilder repaired = new StringBuilder(trimmed);
        for (int i = 0; i < openBrackets; i++) {
            repaired.append(']');
        }
        for (int i = 0; i < openBraces; i++) {
            repaired.append('}');
        }

        String result = repaired.toString();
        log.info("Repaired truncated JSON: {} → {} chars (closed {} brackets, {} braces)",
                json.length(), result.length(), openBrackets, openBraces);
        return result;
    }

    /**
     * Find the position of the last '}' that completes an array element.
     * Looks for patterns like "},\n" or "}\n]" which indicate a fully serialized object.
     */
    private static int findLastCompleteObject(String json) {
        // Scan backwards for "}" that is followed by "," or "]" (possibly with whitespace)
        // This indicates the end of a complete JSON object within an array.
        boolean inString = false;
        boolean escaped = false;
        int lastCloseBrace = -1;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;

            if (c == '}') {
                // Check if this is followed by ',' or ']' or whitespace+','
                int next = skipWhitespace(json, i + 1);
                if (next < json.length()) {
                    char nextChar = json.charAt(next);
                    if (nextChar == ',' || nextChar == ']') {
                        lastCloseBrace = i;
                    }
                }
            }
        }

        return lastCloseBrace;
    }

    private static int skipWhitespace(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    /**
     * Check if JSON appears to be complete (ends with } or ]).
     */
    private static boolean isJsonComplete(String json) {
        if (json.isEmpty()) return false;
        char last = json.charAt(json.length() - 1);
        return last == '}' || last == ']';
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

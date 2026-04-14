package com.fu.common.util;

/**
 * Shared text manipulation utilities used across modules.
 */
public final class TextUtils {

    private TextUtils() {
    }

    /**
     * Truncates text to maxChars, appending a suffix when truncated.
     *
     * @param text     the input text (null-safe)
     * @param maxChars maximum characters to keep
     * @param suffix   string appended when truncation occurs (e.g. "..." or "\n...[truncated]")
     * @return the possibly-truncated text, or null if input was null
     */
    public static String truncate(String text, int maxChars, String suffix) {
        if (text == null) return null;
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + suffix;
    }

    /**
     * Truncates text to maxChars with no suffix.
     */
    public static String truncate(String text, int maxChars) {
        return truncate(text, maxChars, "");
    }
}

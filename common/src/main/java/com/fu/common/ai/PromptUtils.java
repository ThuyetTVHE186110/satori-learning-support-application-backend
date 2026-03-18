package com.fu.common.ai;

import java.util.List;

/**
 * Shared AI prompt utilities reused across all modules.
 *
 * <ul>
 *   <li>{@link #cleanMarkdownFences} — strip markdown code fences from LLM responses</li>
 *   <li>{@link #slidingTail} — keep only the last N items from a list (sliding context window)</li>
 * </ul>
 *
 * <p>This class has zero Spring/AI dependencies so it can be used freely in any module
 * without introducing additional classpath coupling.
 */
public final class PromptUtils {

    private PromptUtils() {}

    /**
     * Strip markdown code fences from an LLM response.
     *
     * <p>Handles {@code ```json}, plain {@code ```}, and mixed-case variants.
     * Skips the first line after the opening fence (the language tag line).
     *
     * @param raw      raw response from the LLM (may be {@code null})
     * @param fallback value returned when {@code raw} is {@code null} or blank
     * @return cleaned string, trimmed, never {@code null}
     */
    public static String cleanMarkdownFences(String raw, String fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String cleaned = raw.strip();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline > 0) {
                cleaned = cleaned.substring(firstNewline + 1);
            } else {
                // Opening fence with no newline — strip the 3 backticks
                cleaned = cleaned.substring(3);
            }
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        return cleaned.strip();
    }

    /**
     * Return the last {@code maxSize} elements of {@code list} (sliding-tail window).
     *
     * <p>Used for conversation history trimming in roleplay and mission evaluation
     * to keep recent context within the model's token limit.
     * Returns the original list unchanged when its size is already within the limit.
     *
     * @param list    source list (must not be {@code null})
     * @param maxSize maximum number of elements to keep (from the tail)
     * @param <T>     element type
     * @return sub-list or original list — always a view, never a copy
     */
    public static <T> List<T> slidingTail(List<T> list, int maxSize) {
        if (list.size() <= maxSize) return list;
        return list.subList(list.size() - maxSize, list.size());
    }
}

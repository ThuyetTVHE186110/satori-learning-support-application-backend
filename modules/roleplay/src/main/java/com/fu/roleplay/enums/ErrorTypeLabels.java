package com.fu.roleplay.enums;

import java.util.Map;

/**
 * Vietnamese-facing labels for {@link ErrorType}. Backend owns the translation so
 * every consumer (FE, push notifications, email recaps) shows consistent wording
 * without each rewriting its own enum-to-label map.
 *
 * <p>Labels are designed for direct rendering to learners — short noun phrases that
 * read naturally inside Vietnamese sentences (e.g. "Lỗi <b>trợ từ</b> xuất hiện 26 lần").
 */
public final class ErrorTypeLabels {

    private ErrorTypeLabels() {}

    private static final Map<ErrorType, String> LABELS = Map.of(
            ErrorType.PARTICLE,    "Trợ từ (は/が/を/に…)",
            ErrorType.CONJUGATION, "Chia động từ / tính từ",
            ErrorType.WORD_ORDER,  "Trật tự câu",
            ErrorType.WORD_CHOICE, "Dùng từ chưa phù hợp",
            ErrorType.FORMALITY,   "Mức độ lịch sự (敬語)",
            ErrorType.SPELLING,    "Chính tả / Kana / Kanji",
            ErrorType.COHERENCE,   "Mạch lạc câu chuyện",
            ErrorType.OTHER,       "Lỗi khác"
    );

    /** Vietnamese label for an {@link ErrorType}; never returns null. */
    public static String labelOf(ErrorType type) {
        return type == null ? "Lỗi khác" : LABELS.getOrDefault(type, "Lỗi khác");
    }

    /** Convenience for callers holding the raw enum name string (e.g. from JSONB). */
    public static String labelOf(String enumName) {
        if (enumName == null || enumName.isBlank()) return "Lỗi khác";
        try {
            return labelOf(ErrorType.valueOf(enumName));
        } catch (IllegalArgumentException e) {
            return "Lỗi khác";
        }
    }

    /**
     * Build a human-friendly Vietnamese summary line for a recurring error pattern.
     * Example: "Lỗi <i>Dùng từ chưa phù hợp</i> lặp lại 26 lần trong 3/5 buổi gần đây."
     */
    public static String recurringSummary(ErrorType type, int totalOccurrences,
                                          int sessionCount, int totalSessionsChecked) {
        String label = labelOf(type);
        if (totalSessionsChecked <= 0) {
            return String.format("Lỗi %s lặp lại %d lần.", label, totalOccurrences);
        }
        return String.format("Lỗi %s lặp lại %d lần trong %d/%d buổi gần đây.",
                label, totalOccurrences, sessionCount, totalSessionsChecked);
    }
}

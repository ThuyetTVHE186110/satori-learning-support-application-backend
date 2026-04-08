package com.fu.practice.entity;

/**
 * Góc tiếp cận câu hỏi cho mỗi phiên luyện tập.
 *
 * Dimension được chọn ở cấp session (1 dimension cho toàn bộ batch LLM),
 * nhưng lưu per-item trong item_mastery.last_dimension để tính toán
 * mastery-weighted vote cho session tiếp theo.
 *
 * Vocab dimensions mở dần theo mastery_level:
 *   level 0 → [MEANING]
 *   level 1 → [MEANING, READING]
 *   level 2 → [MEANING, READING, USAGE, CONTEXT]
 *   level 3 → [MEANING, READING, USAGE, CONTEXT, ANTONYM_SYNONYM]
 *
 * Grammar dimensions mở dần:
 *   level 0 → [IDENTIFICATION]
 *   level 1 → [IDENTIFICATION, FORMATION]
 *   level 2 → [IDENTIFICATION, FORMATION, USAGE]
 *   level 3 → [IDENTIFICATION, FORMATION, USAGE, CONTRAST]
 */
public enum QuestionDimension {

    // ── Từ vựng ────────────────────────────────────────────────────────────

    /** Hỏi nghĩa tiếng Việt ↔ tiếng Nhật. Default / dễ nhất. */
    MEANING,

    /** Hỏi cách đọc, furigana, on-yomi / kun-yomi. */
    READING,

    /** Dùng từ trong câu: FILL_BLANK, trợ từ đi kèm, SENTENCE_ORDER. */
    USAGE,

    /**
     * Đặt câu hỏi trong tình huống thực tế.
     * Khai thác UserLearningPreferences.preferredTopics khi available.
     */
    CONTEXT,

    /** Hỏi về từ trái nghĩa, đồng nghĩa, từ liên quan. Level cao nhất. */
    ANTONYM_SYNONYM,

    // ── Ngữ pháp ───────────────────────────────────────────────────────────

    /** Nhận diện câu đúng/sai dùng mẫu ngữ pháp. Default grammar. */
    IDENTIFICATION,

    /** Chia động từ, biến thể mẫu ngữ pháp (て形、た形、ない形...). */
    FORMATION,

    /** Phân biệt 2 mẫu ngữ pháp dễ nhầm lẫn. Level cao nhất grammar. */
    CONTRAST
}

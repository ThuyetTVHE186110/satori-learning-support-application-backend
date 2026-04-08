package com.fu.practice.entity;

/**
 * Loại phiên luyện tập.
 * KANJI_READING đã bị xóa (migration 48) — thay bằng VOCAB_DRILL với dimension READING.
 */
public enum PracticeSessionType {
    VOCAB_DRILL,
    GRAMMAR_DRILL,
    MIXED_LESSON
}

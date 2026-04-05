-- ==========================================================================
-- Migration 42: Xoá session type SENTENCE_BUILD
-- SENTENCE_BUILD không còn là một mục tiêu học tập riêng biệt.
-- SENTENCE_ORDER được mở rộng sang VOCAB_DRILL và GRAMMAR_DRILL.
-- ==========================================================================

-- PostgreSQL không hỗ trợ DROP VALUE từ enum trực tiếp.
-- Giải pháp: tạo enum mới, migrate cột, drop enum cũ.

-- ── 1. Tạo enum mới không có SENTENCE_BUILD ───────────────────────────────
CREATE TYPE practice_session_type_new AS ENUM (
    'VOCAB_DRILL',
    'GRAMMAR_DRILL',
    'MIXED_LESSON',
    'KANJI_READING'
);

-- ── 2. Chuyển đổi các session SENTENCE_BUILD (nếu có) sang MIXED_LESSON ───
-- (Dữ liệu cũ không mất — map sang loại gần nhất)
UPDATE practice.practice_sessions
SET session_type = 'MIXED_LESSON'::practice_session_type
WHERE session_type = 'SENTENCE_BUILD'::practice_session_type;

-- ── 3. Đổi kiểu cột sang enum mới ─────────────────────────────────────────
ALTER TABLE practice.practice_sessions
    ALTER COLUMN session_type TYPE practice_session_type_new
        USING session_type::text::practice_session_type_new;

-- ── 4. Drop enum cũ, đổi tên enum mới ────────────────────────────────────
DROP TYPE practice_session_type;
ALTER TYPE practice_session_type_new RENAME TO practice_session_type;

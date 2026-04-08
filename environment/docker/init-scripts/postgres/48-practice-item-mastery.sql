-- ==========================================================================
-- Migration 48: Practice Item Mastery + Session Redesign
-- - Tạo bảng practice.item_mastery (WRCR + EMA per-item mastery tracking)
-- - Xóa KANJI_READING khỏi practice_session_type enum
-- - Thêm cột explicit-selection và dimension vào practice_sessions
-- ==========================================================================

-- ── 1. Xóa KANJI_READING khỏi practice_session_type ─────────────────────
-- PostgreSQL không hỗ trợ DROP VALUE trực tiếp.
-- Giải pháp: rename old → tạo mới → migrate data → drop old → recreate cast.

-- 1a. Xử lý các row còn sót KANJI_READING (update sang VOCAB_DRILL trước khi drop)
UPDATE practice.practice_sessions
    SET session_type = 'VOCAB_DRILL'::text
    WHERE session_type::text = 'KANJI_READING';

-- 1b. Đổi tên enum cũ
ALTER TYPE practice_session_type RENAME TO practice_session_type_old;

-- 1c. Tạo enum mới không có KANJI_READING
CREATE TYPE practice_session_type AS ENUM (
    'VOCAB_DRILL',
    'GRAMMAR_DRILL',
    'MIXED_LESSON'
);

-- 1d. Migrate cột sang enum mới
ALTER TABLE practice.practice_sessions
    ALTER COLUMN session_type TYPE practice_session_type
    USING session_type::text::practice_session_type;

-- 1e. Drop cast cũ trỏ vào type_old (được rename theo khi đổi tên type)
-- Phải drop trước khi drop type vì cast phụ thuộc vào type.
DROP CAST IF EXISTS (character varying AS practice_session_type_old);

-- 1f. Drop enum cũ (giờ không còn dependent objects)
DROP TYPE practice_session_type_old;

-- 1g. Recreate implicit cast cho enum mới
DROP CAST IF EXISTS (character varying AS practice_session_type);
CREATE CAST (character varying AS practice_session_type) WITH INOUT AS IMPLICIT;

-- ── 2. Thêm cột explicit selection + dimension vào practice_sessions ──────
ALTER TABLE practice.practice_sessions
    ADD COLUMN IF NOT EXISTS is_explicit_mode     BOOLEAN  NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS selected_vocab_ids   UUID[]   DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS selected_grammar_ids UUID[]   DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS session_dimension    VARCHAR(20) DEFAULT NULL;

COMMENT ON COLUMN practice.practice_sessions.is_explicit_mode
    IS 'TRUE khi học viên tự chọn từng vocab/grammar cụ thể để luyện tập.';
COMMENT ON COLUMN practice.practice_sessions.selected_vocab_ids
    IS 'UUIDs của Vocabulary items được chọn (null = auto mode).';
COMMENT ON COLUMN practice.practice_sessions.selected_grammar_ids
    IS 'UUIDs của GrammarPoint items được chọn (null = auto mode).';
COMMENT ON COLUMN practice.practice_sessions.session_dimension
    IS 'QuestionDimension áp dụng cho session này (MEANING/READING/USAGE/...).';

-- ── 3. Bảng practice.item_mastery ────────────────────────────────────────
-- Per-user, per-item mastery tracking dùng WRCR + EMA algorithm.
-- Công thức: score = accuracy*0.30 + ema*0.50 + streak*0.20
-- Mastery level: 0=Chưa luyện, 1=Quen thuộc, 2=Nắm vững, 3=Thuộc

CREATE TABLE IF NOT EXISTS practice.item_mastery (
    id                  UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_id             UUID         NOT NULL,
    entity_id           UUID         NOT NULL,
    entity_type         VARCHAR(10)  NOT NULL,
    attempt_count       INT          NOT NULL DEFAULT 0,
    correct_count       INT          NOT NULL DEFAULT 0,
    consecutive_correct INT          NOT NULL DEFAULT 0,
    ema_score           DECIMAL(6,5) NOT NULL DEFAULT 0.50000,
    mastery_level       SMALLINT     NOT NULL DEFAULT 0,
    last_dimension      VARCHAR(20)  DEFAULT NULL,
    last_practiced_at   TIMESTAMP    DEFAULT NULL,
    created_at          TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT pk_item_mastery PRIMARY KEY (id),
    CONSTRAINT uq_item_mastery UNIQUE (user_id, entity_id, entity_type),
    CONSTRAINT chk_item_mastery_entity_type
        CHECK (entity_type IN ('VOCABULARY', 'GRAMMAR')),
    CONSTRAINT chk_item_mastery_level
        CHECK (mastery_level BETWEEN 0 AND 3),
    CONSTRAINT chk_item_mastery_ema
        CHECK (ema_score BETWEEN 0.0 AND 1.0)
);

CREATE INDEX IF NOT EXISTS idx_item_mastery_user
    ON practice.item_mastery (user_id);

CREATE INDEX IF NOT EXISTS idx_item_mastery_user_entity
    ON practice.item_mastery (user_id, entity_id);

CREATE INDEX IF NOT EXISTS idx_item_mastery_user_type_level
    ON practice.item_mastery (user_id, entity_type, mastery_level);

COMMENT ON TABLE practice.item_mastery
    IS 'Per-user, per-item mastery score dùng WRCR + EMA. Cập nhật sau mỗi lần submitAnswer.';
COMMENT ON COLUMN practice.item_mastery.ema_score
    IS 'Exponential Moving Average với alpha=0.4. Prior = 0.5 (uninformed).';
COMMENT ON COLUMN practice.item_mastery.mastery_level
    IS '0=Chưa luyện (<40%), 1=Quen thuộc (40-69%), 2=Nắm vững (70-89%), 3=Thuộc (>=90%).';
COMMENT ON COLUMN practice.item_mastery.last_dimension
    IS 'QuestionDimension của session gần nhất có câu hỏi về item này.';

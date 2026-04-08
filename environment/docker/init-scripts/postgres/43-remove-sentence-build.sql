-- ==========================================================================
-- Migration 43: Xoá session type SENTENCE_BUILD
-- SENTENCE_BUILD không còn là một mục tiêu học tập riêng biệt.
-- SENTENCE_ORDER được mở rộng sang VOCAB_DRILL và GRAMMAR_DRILL.
--
-- IDEMPOTENT: chỉ chạy nếu SENTENCE_BUILD vẫn còn trong enum.
-- ==========================================================================

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_enum e
        JOIN pg_type t ON e.enumtypid = t.oid
        WHERE t.typname = 'practice_session_type'
          AND e.enumlabel = 'SENTENCE_BUILD'
    ) THEN
        -- 1. Tạo enum mới không có SENTENCE_BUILD
        EXECUTE 'CREATE TYPE practice_session_type_new AS ENUM (
            ''VOCAB_DRILL'',
            ''GRAMMAR_DRILL'',
            ''MIXED_LESSON'',
            ''KANJI_READING''
        )';

        -- 2. Chuyển đổi các session SENTENCE_BUILD (nếu có) sang MIXED_LESSON
        EXECUTE 'UPDATE practice.practice_sessions
                 SET session_type = ''MIXED_LESSON''::practice_session_type
                 WHERE session_type = ''SENTENCE_BUILD''::practice_session_type';

        -- 3. Đổi kiểu cột sang enum mới
        EXECUTE 'ALTER TABLE practice.practice_sessions
                 ALTER COLUMN session_type TYPE practice_session_type_new
                 USING session_type::text::practice_session_type_new';

        -- 4. Drop enum cũ (CASCADE để xoá luôn cast phụ thuộc), rename enum mới
        EXECUTE 'DROP TYPE practice_session_type CASCADE';
        EXECUTE 'ALTER TYPE practice_session_type_new RENAME TO practice_session_type';

        RAISE NOTICE 'Migration 43: SENTENCE_BUILD removed from practice_session_type';
    ELSE
        RAISE NOTICE 'Migration 43: SENTENCE_BUILD not in enum — already migrated, skipped';
    END IF;
END $$;

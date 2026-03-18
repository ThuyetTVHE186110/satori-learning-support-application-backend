-- ============================================================================
-- Migration 16: Section-type metadata on import_chunks (MnN structural chunking)
--
-- Adds section_type column to system_admin.import_chunks so that each chunk
-- produced by MnNSectionSplitter carries its functional role within a lesson:
--   VOCABULARY          — 語彙 / Từ vựng
--   GRAMMAR_PATTERN     — 文型 / Mẫu câu
--   EXAMPLE             — 例文 / Ví dụ
--   DIALOG              — 会話 / Hội thoại
--   GRAMMAR_EXPLANATION — 文法 / Ngữ pháp
--   EXERCISE            — 練習 / Bài tập  (extraction skipped)
--   UNKNOWN             — unrecognized / character-based fallback
-- ============================================================================

ALTER TABLE system_admin.import_chunks
    ADD COLUMN IF NOT EXISTS section_type VARCHAR(30);

COMMENT ON COLUMN system_admin.import_chunks.section_type IS
    'Functional section within a Minna no Nihongo lesson (set by MnNSectionSplitter). '
    'Values: VOCABULARY, GRAMMAR_PATTERN, EXAMPLE, DIALOG, GRAMMAR_EXPLANATION, EXERCISE, UNKNOWN. '
    'NULL for character-based chunks that were not processed by the section splitter.';

-- Index to allow fast filtering of chunk types (e.g. skip EXERCISE chunks in analytics)
CREATE INDEX IF NOT EXISTS idx_import_chunks_section_type
    ON system_admin.import_chunks (section_type)
    WHERE section_type IS NOT NULL;

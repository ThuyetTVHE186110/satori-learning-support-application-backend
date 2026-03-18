-- ============================================================================
-- Migration 15: Lesson-aware chunking metadata on import_chunks
--
-- Adds lesson_title and lesson_number columns to system_admin.import_chunks
-- so that retry processing can reconstruct lesson-aware DocumentChunks from
-- the database without re-running boundary detection on the original file.
-- ============================================================================

ALTER TABLE system_admin.import_chunks
    ADD COLUMN IF NOT EXISTS lesson_title  VARCHAR(100),
    ADD COLUMN IF NOT EXISTS lesson_number INTEGER;

COMMENT ON COLUMN system_admin.import_chunks.lesson_title IS
    'Lesson title detected by boundary analysis, e.g. "Bài 1". NULL for character-based chunks.';
COMMENT ON COLUMN system_admin.import_chunks.lesson_number IS
    'Numeric lesson number corresponding to lesson_title. NULL for character-based chunks.';

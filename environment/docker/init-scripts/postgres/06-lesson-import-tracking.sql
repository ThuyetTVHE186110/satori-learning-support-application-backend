-- Migration: Add import_id to lessons table for curriculum import tracking
-- Allows precise tracking of which lessons were created by a specific import job.

ALTER TABLE learning_content.lessons
    ADD COLUMN IF NOT EXISTS import_id UUID NULL;

COMMENT ON COLUMN learning_content.lessons.import_id IS
    'References system_admin.curriculum_imports.id. Set when a lesson is created by the Agentic RAG pipeline.';

CREATE INDEX IF NOT EXISTS idx_lessons_import_id
    ON learning_content.lessons (import_id)
    WHERE import_id IS NOT NULL;

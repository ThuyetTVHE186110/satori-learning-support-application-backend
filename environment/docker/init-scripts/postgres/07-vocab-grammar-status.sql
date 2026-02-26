-- ==========================================================================
-- Migration 07: Add status column to vocabulary and grammar_points
-- ==========================================================================

ALTER TABLE learning_content.vocabulary
    ADD COLUMN IF NOT EXISTS status content_status DEFAULT 'DRAFT';

ALTER TABLE learning_content.grammar_points
    ADD COLUMN IF NOT EXISTS status content_status DEFAULT 'DRAFT';

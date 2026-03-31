-- Migration 33: Remove lesson_type column and enum type
-- Reason: lessons are always comprehensive (MIXED), the field is redundant.

-- Step 1: Drop the dependent view first (uses SELECT * which includes lesson_type)
DROP VIEW IF EXISTS learning_content.v_active_lessons;

-- Step 2: Drop the column (also drops idx_lessons_lesson_type automatically)
ALTER TABLE learning_content.lessons
    DROP COLUMN IF EXISTS lesson_type;

-- Step 3: Recreate the view without lesson_type
CREATE OR REPLACE VIEW learning_content.v_active_lessons AS
SELECT * FROM learning_content.lessons
WHERE deleted_at IS NULL AND status = 'PUBLISHED';

-- Step 4: Drop the implicit cast added in migration 12
DROP CAST IF EXISTS (character varying AS lesson_type);

-- Step 5: Drop the enum type
DROP TYPE IF EXISTS lesson_type;

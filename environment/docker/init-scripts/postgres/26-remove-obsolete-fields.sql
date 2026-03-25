-- ── 26. Remove Obsolete Fields from Assignment Module ──────────────────────
-- Drop columns from assignment.quiz_questions
ALTER TABLE assignment.quiz_questions
    DROP COLUMN IF EXISTS vocabulary_id,
    DROP COLUMN IF EXISTS grammar_id,
    DROP COLUMN IF EXISTS lesson_id,
    DROP COLUMN IF EXISTS tags,
    DROP COLUMN IF EXISTS image_url;

-- Drop column from assignment.writing_assignments
ALTER TABLE assignment.writing_assignments
    DROP COLUMN IF EXISTS source_text;

-- Drop column from assignment.assignment_submissions
ALTER TABLE assignment.assignment_submissions
    DROP COLUMN IF EXISTS time_spent_seconds;

-- Drop associated indexes
DROP INDEX IF EXISTS assignment.idx_quiz_questions_lesson_id;

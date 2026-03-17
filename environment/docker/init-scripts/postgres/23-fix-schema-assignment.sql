-- ── 1. Drop Trigger & Function (Sửa lỗi bạn vừa gặp) ────────────────────────
DROP TRIGGER IF EXISTS trg_mark_late_submission ON assignment.assignment_submissions;
DROP FUNCTION IF EXISTS assignment.mark_late_submission();

-- ── 2. Drop View (Đảm bảo không bị dependency) ─────────────────────────────
DROP VIEW IF EXISTS assignment.v_active_assignments;

-- ── 3. Clean up assignment.assignments ──────────────────────────────────────
ALTER TABLE assignment.assignments
    DROP COLUMN IF EXISTS time_limit_minutes,
    DROP COLUMN IF EXISTS late_submission_allowed,
    DROP COLUMN IF EXISTS late_submission_penalty_percent,
    DROP COLUMN IF EXISTS passing_score,
    DROP COLUMN IF EXISTS shuffle_questions,
    DROP COLUMN IF EXISTS shuffle_options,
    DROP COLUMN IF EXISTS show_correct_answers,
    DROP COLUMN IF EXISTS lesson_id;

-- ── 4. Recreate View (Sạch sẽ) ───────────────────────────────────────────────
CREATE OR REPLACE VIEW assignment.v_active_assignments AS
SELECT * FROM assignment.assignments
WHERE deleted_at IS NULL AND status != 'CLOSED';

-- ── 5. Clean up assignment.quiz_questions ────────────────────────────────────
ALTER TABLE assignment.quiz_questions
    DROP COLUMN IF EXISTS question_japanese,
    DROP COLUMN IF EXISTS explanation_vi,
    DROP COLUMN IF EXISTS difficulty_score,
    DROP COLUMN IF EXISTS status;

-- ── 6. Clean up assignment.writing_assignments ───────────────────────────────
ALTER TABLE assignment.writing_assignments
    DROP COLUMN IF EXISTS prompt_japanese,
    DROP COLUMN IF EXISTS sample_answer,
    DROP COLUMN IF EXISTS sample_answer_japanese,
    DROP COLUMN IF EXISTS min_words,
    DROP COLUMN IF EXISTS max_words,
    DROP COLUMN IF EXISTS translation_direction;

-- ── 7. Clean up assignment.assignment_submissions (Các cột thừa) ──────────────
ALTER TABLE assignment.assignment_submissions
    DROP COLUMN IF EXISTS is_late,
    DROP COLUMN IF EXISTS late_penalty_applied,
    DROP COLUMN IF EXISTS score_before_penalty;
-- 8. Drop the view depending on assignment table columns
DROP VIEW IF EXISTS assignment.v_active_assignments;

-- 9. Drop deprecated columns from assignment.assignments
ALTER TABLE assignment.assignments
    DROP COLUMN IF EXISTS max_score,
    DROP COLUMN IF EXISTS allow_retry,
    DROP COLUMN IF EXISTS max_retries;

-- 10. Recreate the view without the removed columns
CREATE OR REPLACE VIEW assignment.v_active_assignments AS
SELECT * FROM assignment.assignments
WHERE deleted_at IS NULL AND status != 'CLOSED';

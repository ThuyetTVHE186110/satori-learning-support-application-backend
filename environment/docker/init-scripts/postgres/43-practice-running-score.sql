-- ==========================================================================
-- Migration 43: Add running_score_sum to practice_sessions
-- Allows finishSession() to compute final score without re-reading all items.
-- ==========================================================================

ALTER TABLE practice.practice_sessions
    ADD COLUMN IF NOT EXISTS running_score_sum INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN practice.practice_sessions.running_score_sum
    IS 'Cumulative sum of per-item scores (0-100 each), incremented on every submitAnswer. Used to compute session final score without a full items re-read.';

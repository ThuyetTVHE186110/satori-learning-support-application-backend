-- ==========================================================================
-- Migration 30: Add optimistic locking (version column) to practice entities
-- Fixes race condition on concurrent answer submissions where two requests
-- could read the same completedItems counter, both increment it, and the
-- last write wins — causing the session to never reach completion.
-- ==========================================================================

-- PracticeSession: version column for optimistic locking
ALTER TABLE practice.practice_sessions
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- PracticeItem: version column for optimistic locking
ALTER TABLE practice.practice_items
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

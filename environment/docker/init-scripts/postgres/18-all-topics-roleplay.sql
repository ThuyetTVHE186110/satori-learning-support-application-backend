-- ==========================================================================
-- Migration 18: Enforce all conversation_topics are roleplay
-- All conversations are roleplay — set is_roleplay = TRUE everywhere
-- and change the column default to TRUE.
-- ==========================================================================

-- 1. Set every existing topic to is_roleplay = TRUE
UPDATE speaking.conversation_topics
SET is_roleplay = TRUE
WHERE is_roleplay = FALSE OR is_roleplay IS NULL;

-- 2. Change column default to TRUE so all future inserts are correct
ALTER TABLE speaking.conversation_topics
    ALTER COLUMN is_roleplay SET DEFAULT TRUE;

-- 3. Add NOT NULL constraint now that all rows are populated
ALTER TABLE speaking.conversation_topics
    ALTER COLUMN is_roleplay SET NOT NULL;

DO $$ BEGIN RAISE NOTICE 'Migration 18 complete: all conversation topics are now roleplay.'; END $$;

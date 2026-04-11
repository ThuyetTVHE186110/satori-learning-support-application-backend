-- Migration 54: Rename prosody_score to completeness_score in message_pronunciation
-- Azure Speech prosody is always 0 for ja-JP (only supported for en-US).
-- Completeness score (ratio of words spoken vs reference) is meaningful and was
-- already returned by Azure but never persisted.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'ai_conversation'
          AND table_name   = 'message_pronunciation'
          AND column_name  = 'prosody_score'
    ) THEN
        ALTER TABLE ai_conversation.message_pronunciation
            RENAME COLUMN prosody_score TO completeness_score;
    END IF;
END $$;

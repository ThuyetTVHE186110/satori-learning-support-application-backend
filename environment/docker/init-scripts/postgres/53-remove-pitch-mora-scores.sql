-- Migration 53: Remove pitch_accent_score and mora_timing_score from message_pronunciation
-- These columns were intended for a custom Japanese pronunciation microservice
-- that was never implemented. All pronunciation assessment is handled by Azure Speech,
-- which does not provide these metrics.

ALTER TABLE ai_conversation.message_pronunciation
    DROP COLUMN IF EXISTS pitch_accent_score,
    DROP COLUMN IF EXISTS mora_timing_score;

-- ==========================================================================
-- Migration 11: Japanese Pronunciation Assessment
-- - Add pitch_accent_score and mora_timing_score to message_pronunciation
-- - These columns store scores from the custom Japanese pronunciation
--   assessment microservice (pitch accent + mora timing analysis)
-- ==========================================================================

ALTER TABLE ai_conversation.message_pronunciation
    ADD COLUMN IF NOT EXISTS pitch_accent_score DECIMAL(5,2),
    ADD COLUMN IF NOT EXISTS mora_timing_score DECIMAL(5,2);

-- Prevent duplicate sequence numbers within the same session (race condition fix)
CREATE UNIQUE INDEX IF NOT EXISTS uq_conv_messages_session_seq
    ON ai_conversation.conversation_messages(session_id, sequence_number);

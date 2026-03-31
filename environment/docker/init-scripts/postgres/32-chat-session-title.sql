-- Migration 32: Add title column to conversation_sessions
-- Supports auto-generated context title (set at session creation)
-- and AI-generated title (overwritten async after first message).

ALTER TABLE ai_conversation.conversation_sessions
    ADD COLUMN IF NOT EXISTS title VARCHAR(255);

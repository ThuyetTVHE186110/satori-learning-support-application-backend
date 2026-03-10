-- ==========================================================================
-- Migration 15: Add Vietnamese description columns to conversation tables
-- - speaking.conversation_themes    → description_vi
-- - speaking.conversation_topics    → description_vi
-- ==========================================================================

ALTER TABLE speaking.conversation_themes
    ADD COLUMN IF NOT EXISTS description_vi TEXT;

ALTER TABLE speaking.conversation_topics
    ADD COLUMN IF NOT EXISTS description_vi TEXT;

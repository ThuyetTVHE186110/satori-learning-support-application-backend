-- Migration 25: Add ai_role and learner_role columns to speaking.conversation_topics
-- These replace the embedded aiRole/userRole fields inside the roleplay_context JSONB,
-- making each role a proper first-class column that is easier to query and display.

ALTER TABLE speaking.conversation_topics
    ADD COLUMN IF NOT EXISTS ai_role      TEXT,
    ADD COLUMN IF NOT EXISTS learner_role TEXT;

-- Back-fill from existing roleplay_context JSONB where data is already present
UPDATE speaking.conversation_topics
SET
    ai_role      = roleplay_context ->> 'aiRole',
    learner_role = roleplay_context ->> 'userRole'
WHERE roleplay_context IS NOT NULL
  AND (roleplay_context ->> 'aiRole' IS NOT NULL
    OR roleplay_context ->> 'userRole' IS NOT NULL);

COMMENT ON COLUMN speaking.conversation_topics.ai_role      IS 'Identity of the AI character in this roleplay scenario (e.g. "a Japanese shopkeeper")';
COMMENT ON COLUMN speaking.conversation_topics.learner_role IS 'Identity of the learner in this roleplay scenario (e.g. "a customer")';

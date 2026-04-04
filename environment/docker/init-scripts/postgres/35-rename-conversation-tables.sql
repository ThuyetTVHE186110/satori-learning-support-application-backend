-- ==========================================================================
-- Migration 35: Rename conversation tables to align with refactored entity names
--
-- Rename mapping:
--   speaking.conversation_topics  → speaking.conversations       (roleplay scenarios)
--   speaking.conversation_themes  → speaking.conversation_topics  (top-level themes)
--   speaking.user_conversation_topics → speaking.user_conversations (learner private)
--
-- Column renames:
--   conversations.theme_id        → conversations.topic_id       (FK to conversation_topics)
--   conversation_missions.topic_id → conversation_missions.conversation_id
--   user_conversation_progress.topic_id → user_conversation_progress.conversation_id
-- ==========================================================================

-- Step 1: Rename old conversation_topics (roleplay scenarios) → conversations
ALTER TABLE IF EXISTS speaking.conversation_topics
    RENAME TO conversations;

-- Step 2: Rename old conversation_themes (top-level themes) → conversation_topics
ALTER TABLE IF EXISTS speaking.conversation_themes
    RENAME TO conversation_topics;

-- Step 3: Rename old user_conversation_topics → user_conversations
ALTER TABLE IF EXISTS speaking.user_conversation_topics
    RENAME TO user_conversations;

-- Step 4: In conversations, rename theme_id → topic_id (FK now points to new conversation_topics)
ALTER TABLE IF EXISTS speaking.conversations
    RENAME COLUMN theme_id TO topic_id;

-- Update FK constraint on conversations.topic_id to point to the renamed table
ALTER TABLE speaking.conversations
    DROP CONSTRAINT IF EXISTS conversation_topics_theme_id_fkey;

ALTER TABLE speaking.conversations
    ADD CONSTRAINT conversations_topic_id_fkey
    FOREIGN KEY (topic_id)
    REFERENCES speaking.conversation_topics(id)
    ON DELETE SET NULL;

-- Step 5: In conversation_missions, rename topic_id → conversation_id
ALTER TABLE IF EXISTS speaking.conversation_missions
    RENAME COLUMN topic_id TO conversation_id;

-- Update FK constraint on conversation_missions.conversation_id
ALTER TABLE speaking.conversation_missions
    DROP CONSTRAINT IF EXISTS conversation_missions_topic_id_fkey;

ALTER TABLE speaking.conversation_missions
    ADD CONSTRAINT conversation_missions_conversation_id_fkey
    FOREIGN KEY (conversation_id)
    REFERENCES speaking.conversations(id)
    ON DELETE CASCADE;

-- Step 6: In user_conversation_progress, rename topic_id → conversation_id
ALTER TABLE IF EXISTS speaking.user_conversation_progress
    RENAME COLUMN topic_id TO conversation_id;

-- Update FK and unique constraint
ALTER TABLE speaking.user_conversation_progress
    DROP CONSTRAINT IF EXISTS user_conversation_progress_topic_id_fkey,
    DROP CONSTRAINT IF EXISTS uq_user_conversation_progress;

ALTER TABLE speaking.user_conversation_progress
    ADD CONSTRAINT user_conversation_progress_conversation_id_fkey
    FOREIGN KEY (conversation_id)
    REFERENCES speaking.conversations(id)
    ON DELETE CASCADE;

ALTER TABLE speaking.user_conversation_progress
    ADD CONSTRAINT uq_user_conversation_progress
    UNIQUE (user_id, conversation_id);

-- Step 7: Rename indexes to reflect new table/column names
ALTER INDEX IF EXISTS idx_conv_themes_course    RENAME TO idx_conv_topics_course_new;
ALTER INDEX IF EXISTS idx_conv_themes_jlpt      RENAME TO idx_conv_topics_jlpt_new;
ALTER INDEX IF EXISTS idx_conv_themes_status    RENAME TO idx_conv_topics_status_new;
ALTER INDEX IF EXISTS idx_conv_topics_theme     RENAME TO idx_conversations_topic;
ALTER INDEX IF EXISTS idx_user_topics_user      RENAME TO idx_user_conversations_user;

-- Step 8: Create new indexes for conversations table
CREATE INDEX IF NOT EXISTS idx_conversations_course ON speaking.conversations(course_id);
CREATE INDEX IF NOT EXISTS idx_conversations_level  ON speaking.conversations(jlpt_level);

-- Step 9: Rename topic_id → conversation_id in ai_conversation.conversation_sessions
-- The session's topic_id previously pointed to speaking.conversation_topics (roleplay scenarios).
-- After the table rename, those scenarios are now in speaking.conversations.
-- Column is renamed for clarity; the FK was not enforced in original DDL so no constraint to drop.
ALTER TABLE IF EXISTS ai_conversation.conversation_sessions
    RENAME COLUMN topic_id TO conversation_id;

ALTER INDEX IF EXISTS idx_conversation_sessions_topic
    RENAME TO idx_conversation_sessions_conversation;

-- Step 10: Grant permissions on new/renamed tables
GRANT ALL PRIVILEGES ON speaking.conversations        TO satori_user;
GRANT ALL PRIVILEGES ON speaking.conversation_topics  TO satori_user;
GRANT ALL PRIVILEGES ON speaking.user_conversations   TO satori_user;

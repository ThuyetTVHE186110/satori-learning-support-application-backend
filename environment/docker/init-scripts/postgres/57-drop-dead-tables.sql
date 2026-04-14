-- ============================================================
-- 57-drop-dead-tables.sql
-- Dead code cleanup: drop 19 orphan tables, 12 unused columns,
-- dormant triggers, and unused functions.
--
-- Audit methodology: full scan of Java entities, repositories,
-- services, controllers, DTOs, MapStruct mappers, JdbcTemplate
-- calls, AND SQL triggers/functions.  Every item below has ZERO
-- references in the entire application stack.
-- ============================================================

-- ── Phase 1: Drop entire dead schemas ───────────────────────────────────────

-- shop schema: coin shop never built (no entity, no repo, no service)
DROP SCHEMA IF EXISTS shop CASCADE;

-- pronunciation schema: module uses only Azure Speech API,
-- no local persistence (no entity, no repo)
DROP SCHEMA IF EXISTS pronunciation CASCADE;

-- ── Phase 2: Drop orphan tables in surviving schemas ────────────────────────

-- progress schema: only content_embeddings is used (via JdbcTemplate)
DROP TABLE IF EXISTS progress.daily_activity;
DROP TABLE IF EXISTS progress.user_progress;
DROP TABLE IF EXISTS progress.user_mistakes;    -- replaced by ai_conversation.learner_error_patterns (migration 56)
DROP TABLE IF EXISTS progress.user_embeddings;  -- embeddings stored in content_embeddings

-- system_admin schema: only curriculum_imports + import_chunks are used
DROP TABLE IF EXISTS system_admin.notification_queue;   -- replaced by notification.notifications (migration 42)
DROP TABLE IF EXISTS system_admin.usage_statistics;     -- no entity, no service, no cron
DROP TABLE IF EXISTS system_admin.import_history;       -- replaced by curriculum_imports entity
DROP TABLE IF EXISTS system_admin.audit_log;            -- never wired

-- gamification schema: leaderboard + reward_transactions never wired
DROP TABLE IF EXISTS gamification.reward_transactions;  -- has 2 triggers but no Java code ever INSERTs
DROP TABLE IF EXISTS gamification.leaderboard;          -- no entity, no code

-- ai_conversation schema
DROP TABLE IF EXISTS ai_conversation.translation_cache; -- no entity, no repo, no service

-- ── Phase 3: Drop dormant triggers and functions ────────────────────────────

-- These triggers were on reward_transactions (dropped above), but CASCADE
-- should have removed them.  Explicit drop for safety:
DROP FUNCTION IF EXISTS gamification.validate_coin_balance() CASCADE;
DROP FUNCTION IF EXISTS gamification.update_coin_balance() CASCADE;

-- Utility functions never called by Java or other SQL
DROP FUNCTION IF EXISTS gamification.calculate_level(INT) CASCADE;
DROP FUNCTION IF EXISTS gamification.exp_for_level(INT) CASCADE;

-- ── Phase 4: Drop unused columns from live entities ─────────────────────────

-- Vocabulary: commonCollocations (always "[]"), contentHash (dedup never built)
ALTER TABLE learning_content.vocabulary
    DROP COLUMN IF EXISTS common_collocations,
    DROP COLUMN IF EXISTS content_hash;

-- GrammarPoint: contentHash (dedup never built)
ALTER TABLE learning_content.grammar_points
    DROP COLUMN IF EXISTS content_hash;

-- CurriculumImport: quiz generation never built into import pipeline
ALTER TABLE system_admin.curriculum_imports
    DROP COLUMN IF EXISTS quiz_questions_created;

-- ImportChunk: embeddingId never set (embeddings via JdbcTemplate → content_embeddings)
ALTER TABLE system_admin.import_chunks
    DROP COLUMN IF EXISTS embedding_id;

-- ConversationSession (roleplay): durationSeconds never set by any service
ALTER TABLE ai_conversation.conversation_sessions
    DROP COLUMN IF EXISTS duration_seconds;

-- UserConversationProgress: 5 score/count fields never written
ALTER TABLE speaking.user_conversation_progress
    DROP COLUMN IF EXISTS total_messages_sent,
    DROP COLUMN IF EXISTS best_fluency_score,
    DROP COLUMN IF EXISTS best_accuracy_score,
    DROP COLUMN IF EXISTS best_vocabulary_score,
    DROP COLUMN IF EXISTS best_overall_score;

-- UserLearningPreferences: preferredStudyTime never used in any logic
ALTER TABLE user_management.user_learning_preferences
    DROP COLUMN IF EXISTS preferred_study_time;

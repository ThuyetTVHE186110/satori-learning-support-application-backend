-- ============================================================
-- 56-evaluation-overhaul.sql
-- Evaluation v2: Data-driven orthogonal scoring
-- Adds cross-session error tracking and new scoring columns
-- ============================================================

-- 1. Cross-session error tracking table
CREATE TABLE IF NOT EXISTS ai_conversation.learner_error_patterns (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES user_management.users(id),
    session_id UUID NOT NULL REFERENCES ai_conversation.conversation_sessions(id) ON DELETE CASCADE,
    error_type VARCHAR(30) NOT NULL,
    occurrence_count INT NOT NULL DEFAULT 0,
    examples JSONB DEFAULT '[]'::JSONB,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_error_patterns_user
    ON ai_conversation.learner_error_patterns(user_id);
CREATE INDEX IF NOT EXISTS idx_error_patterns_user_type
    ON ai_conversation.learner_error_patterns(user_id, error_type);
CREATE INDEX IF NOT EXISTS idx_error_patterns_session
    ON ai_conversation.learner_error_patterns(session_id);

-- 2. New scoring columns on conversation_sessions (additive, nullable)
--    Old columns (mission_score, pronunciation_score, language_score, overall_score) kept for backward compat
ALTER TABLE ai_conversation.conversation_sessions
    ADD COLUMN IF NOT EXISTS accuracy_score DECIMAL(5,2),
    ADD COLUMN IF NOT EXISTS task_completion_score DECIMAL(5,2),
    ADD COLUMN IF NOT EXISTS complexity_score DECIMAL(5,2);

-- 3. Grant permissions
GRANT ALL PRIVILEGES ON ai_conversation.learner_error_patterns TO satori_user;

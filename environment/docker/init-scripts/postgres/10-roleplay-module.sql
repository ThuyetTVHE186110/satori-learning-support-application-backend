-- ==========================================================================
-- Migration 10: Roleplay Module
-- - New ENUM types: mission_type, mission_status
-- - speaking.conversation_missions (mission templates per topic)
-- - ai_conversation.session_missions (per-session mission tracking)
-- - ai_conversation.message_pronunciation (per-message pronunciation scores)
-- - ALTER ai_conversation.conversation_sessions (add scoring columns)
-- ==========================================================================

-- ==================== New ENUM Types ====================

CREATE TYPE mission_type AS ENUM (
    'INFORMATION_GATHERING',
    'TRANSACTIONAL',
    'SOCIAL',
    'PROBLEM_SOLVING',
    'DESCRIPTIVE',
    'CUSTOM'
);

CREATE TYPE mission_status AS ENUM (
    'PENDING',
    'IN_PROGRESS',
    'COMPLETED',
    'FAILED'
);

-- ==================== Mission Templates (Manager-created per topic) ====================

CREATE TABLE IF NOT EXISTS speaking.conversation_missions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    topic_id UUID NOT NULL REFERENCES speaking.conversation_topics(id) ON DELETE CASCADE,

    title VARCHAR(255) NOT NULL,
    title_japanese VARCHAR(255),
    description TEXT,
    mission_type mission_type NOT NULL DEFAULT 'CUSTOM',

    -- Success criteria (JSONB - hidden from learner, used by AI evaluator)
    success_criteria JSONB DEFAULT '{}'::JSONB,
    /*
    success_criteria structure:
    {
        "requiredActions": ["ask for the price", "confirm the order"],
        "requiredVocabulary": ["いくら", "注文"],
        "requiredGrammar": ["～をください", "～はいくらですか"],
        "completionConditions": "The learner successfully places an order and confirms payment."
    }
    */

    -- Vocabulary and grammar focus
    required_vocabulary JSONB DEFAULT '[]'::JSONB,
    required_grammar JSONB DEFAULT '[]'::JSONB,

    order_index INT DEFAULT 0,

    -- SoftDeletable fields
    created_by UUID REFERENCES user_management.users(id),
    updated_by UUID REFERENCES user_management.users(id),
    deleted_at TIMESTAMP WITH TIME ZONE,
    deleted_by UUID,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- ==================== Per-Session Mission Tracking ====================

CREATE TABLE IF NOT EXISTS ai_conversation.session_missions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    session_id UUID NOT NULL REFERENCES ai_conversation.conversation_sessions(id) ON DELETE CASCADE,
    mission_id UUID REFERENCES speaking.conversation_missions(id) ON DELETE SET NULL,

    -- Snapshot of mission data at session start
    title VARCHAR(255) NOT NULL,
    title_japanese VARCHAR(255),
    description TEXT,
    mission_type mission_type NOT NULL DEFAULT 'CUSTOM',
    success_criteria JSONB DEFAULT '{}'::JSONB,

    -- Progress tracking
    status mission_status NOT NULL DEFAULT 'PENDING',
    progress_pct INT DEFAULT 0,
    ai_reasoning TEXT,

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- ==================== Per-Message Pronunciation Scores ====================

CREATE TABLE IF NOT EXISTS ai_conversation.message_pronunciation (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    message_id UUID NOT NULL REFERENCES ai_conversation.conversation_messages(id) ON DELETE CASCADE,
    session_id UUID NOT NULL REFERENCES ai_conversation.conversation_sessions(id) ON DELETE CASCADE,

    -- Scores (0-100 scale)
    overall_score DECIMAL(5,2),
    accuracy_score DECIMAL(5,2),
    fluency_score DECIMAL(5,2),
    prosody_score DECIMAL(5,2),

    -- Transcribed text from speech recognition
    transcribed_text TEXT,

    -- Raw provider response (for debugging / detailed analysis)
    raw_response JSONB DEFAULT '{}'::JSONB,

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- ==================== ALTER conversation_sessions for scoring ====================

ALTER TABLE ai_conversation.conversation_sessions
    ADD COLUMN IF NOT EXISTS mission_score DECIMAL(5,2),
    ADD COLUMN IF NOT EXISTS pronunciation_score DECIMAL(5,2),
    ADD COLUMN IF NOT EXISTS language_score DECIMAL(5,2),
    ADD COLUMN IF NOT EXISTS overall_score DECIMAL(5,2),
    ADD COLUMN IF NOT EXISTS mission_summary JSONB DEFAULT '{}'::JSONB;

-- ==================== Indexes ====================

CREATE INDEX IF NOT EXISTS idx_conv_missions_topic ON speaking.conversation_missions(topic_id);
CREATE INDEX IF NOT EXISTS idx_conv_missions_type ON speaking.conversation_missions(mission_type);
CREATE INDEX IF NOT EXISTS idx_conv_missions_active ON speaking.conversation_missions(topic_id) WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_session_missions_session ON ai_conversation.session_missions(session_id);
CREATE INDEX IF NOT EXISTS idx_session_missions_status ON ai_conversation.session_missions(session_id, status);

CREATE INDEX IF NOT EXISTS idx_msg_pronunciation_message ON ai_conversation.message_pronunciation(message_id);
CREATE INDEX IF NOT EXISTS idx_msg_pronunciation_session ON ai_conversation.message_pronunciation(session_id);

-- ==================== Permissions ====================

GRANT ALL PRIVILEGES ON speaking.conversation_missions TO satori_user;
GRANT ALL PRIVILEGES ON ai_conversation.session_missions TO satori_user;
GRANT ALL PRIVILEGES ON ai_conversation.message_pronunciation TO satori_user;

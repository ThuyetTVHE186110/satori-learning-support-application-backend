-- ==========================================================================
-- Migration 08: Conversation Module
-- - speaking.conversation_themes (chủ đề lớn - manager created, AI-assisted)
-- - Extend speaking.conversation_topics (add theme_id, course_id)
-- - speaking.user_conversation_topics (learner private topics)
-- ==========================================================================

-- 1. Chủ đề lớn: Main conversation themes (Manager-created, AI-assisted from course context)
CREATE TABLE IF NOT EXISTS speaking.conversation_themes (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    title VARCHAR(255) NOT NULL,
    title_japanese VARCHAR(255),
    description TEXT,
    category VARCHAR(100),
    jlpt_level jlpt_level,
    course_id UUID REFERENCES learning_content.courses(id) ON DELETE SET NULL,
    ai_generated_context JSONB DEFAULT '{}'::JSONB,
    thumbnail_url TEXT,
    status content_status DEFAULT 'DRAFT',
    order_index INT DEFAULT 0,
    created_by UUID REFERENCES user_management.users(id),
    updated_by UUID REFERENCES user_management.users(id),
    deleted_at TIMESTAMP WITH TIME ZONE,
    deleted_by UUID,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 2. Extend speaking.conversation_topics (chủ đề nhỏ) with theme + course linkage
ALTER TABLE speaking.conversation_topics
    ADD COLUMN IF NOT EXISTS theme_id UUID REFERENCES speaking.conversation_themes(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS course_id UUID REFERENCES learning_content.courses(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS updated_by UUID REFERENCES user_management.users(id),
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS deleted_by UUID;

-- 3. Learner private topics (chủ đề hội thoại tự tạo)
CREATE TABLE IF NOT EXISTS speaking.user_conversation_topics (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES user_management.users(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    scenario TEXT,
    jlpt_level jlpt_level,
    course_id UUID REFERENCES learning_content.courses(id) ON DELETE SET NULL,
    ai_generated_context JSONB DEFAULT '{}'::JSONB,
    status content_status DEFAULT 'DRAFT',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_conv_themes_course ON speaking.conversation_themes(course_id);
CREATE INDEX IF NOT EXISTS idx_conv_themes_jlpt ON speaking.conversation_themes(jlpt_level);
CREATE INDEX IF NOT EXISTS idx_conv_themes_status ON speaking.conversation_themes(status);
CREATE INDEX IF NOT EXISTS idx_conv_topics_theme ON speaking.conversation_topics(theme_id);
CREATE INDEX IF NOT EXISTS idx_conv_topics_course ON speaking.conversation_topics(course_id);
CREATE INDEX IF NOT EXISTS idx_user_topics_user ON speaking.user_conversation_topics(user_id);

-- Permissions
GRANT ALL PRIVILEGES ON speaking.conversation_themes TO satori_user;
GRANT ALL PRIVILEGES ON speaking.user_conversation_topics TO satori_user;

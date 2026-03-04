-- ==================== PostgreSQL Initialization Script ====================
-- Satori Japanese Learning System - Complete Database Schema
-- Version: 2.0.0
-- Last Updated: 2026-01-24
-- Changes: Replaced MongoDB with PostgreSQL JSONB, Topic-based Pronunciation

-- ==================== Extensions ====================
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ==================== Schema Creation ====================
CREATE SCHEMA IF NOT EXISTS user_management;
CREATE SCHEMA IF NOT EXISTS learning_content;
CREATE SCHEMA IF NOT EXISTS assignment;
CREATE SCHEMA IF NOT EXISTS pronunciation;
CREATE SCHEMA IF NOT EXISTS speaking;
CREATE SCHEMA IF NOT EXISTS progress;
CREATE SCHEMA IF NOT EXISTS gamification;
CREATE SCHEMA IF NOT EXISTS shop;
CREATE SCHEMA IF NOT EXISTS system_admin;
CREATE SCHEMA IF NOT EXISTS ai_conversation;

-- ==================== ENUM Types ====================

-- User related enums
CREATE TYPE user_role AS ENUM ('LEARNER', 'TEACHER', 'MANAGER', 'ADMIN');
CREATE TYPE user_status AS ENUM ('PENDING_ACTIVATION', 'ACTIVE', 'INACTIVE', 'SUSPENDED', 'DELETED');
CREATE TYPE auth_provider AS ENUM ('LOCAL', 'GOOGLE');
CREATE TYPE gender AS ENUM ('MALE', 'FEMALE', 'OTHER', 'PREFER_NOT_TO_SAY');

-- Learning related enums
CREATE TYPE jlpt_level AS ENUM ('N5', 'N4', 'N3', 'N2', 'N1');
CREATE TYPE lesson_type AS ENUM ('VOCABULARY', 'GRAMMAR', 'KANJI', 'LISTENING', 'READING', 'MIXED');
CREATE TYPE content_status AS ENUM ('DRAFT', 'PUBLISHED', 'ARCHIVED');
CREATE TYPE course_status AS ENUM ('ACTIVE', 'INACTIVE', 'DELETED');
CREATE TYPE processing_status AS ENUM ('QUEUED', 'EXTRACTING', 'CHUNKING', 'AI_PROCESSING', 'EMBEDDING', 'COMPLETED', 'FAILED');
CREATE TYPE grammar_category AS ENUM ('VERB_FORM', 'PARTICLE', 'ADJECTIVE', 'CONJUGATION', 'SENTENCE_PATTERN', 'HONORIFIC', 'CONDITIONAL', 'PASSIVE', 'CAUSATIVE', 'OTHER');

-- Assignment related enums
CREATE TYPE assignment_type AS ENUM ('QUIZ', 'VOCABULARY_TEST', 'GRAMMAR_TEST', 'MIXED_TEST');
CREATE TYPE assignment_status AS ENUM ('DRAFT', 'PUBLISHED', 'CLOSED');
CREATE TYPE submission_status AS ENUM ('NOT_STARTED', 'IN_PROGRESS', 'SUBMITTED', 'GRADED', 'OVERDUE');

-- Pronunciation enums (topic-based)
CREATE TYPE pronunciation_topic_category AS ENUM ('HIRAGANA', 'KATAKANA', 'VOWEL', 'CONSONANT', 'PITCH_ACCENT', 'WORD', 'PHRASE', 'SENTENCE', 'MINIMAL_PAIR');
CREATE TYPE feedback_status AS ENUM ('PENDING', 'COMPLETED', 'FAILED');

-- Conversation enums
CREATE TYPE session_type AS ENUM ('GUIDED_CONVERSATION', 'ROLEPLAY', 'FREE_TALK', 'PRACTICE_ASSIGNMENT');
CREATE TYPE session_status AS ENUM ('ACTIVE', 'COMPLETED', 'ABANDONED');
CREATE TYPE message_role AS ENUM ('USER', 'ASSISTANT', 'SYSTEM', 'HINT');
CREATE TYPE correction_type AS ENUM ('GRAMMAR', 'VOCABULARY', 'KANJI', 'POLITENESS', 'PARTICLE', 'CONJUGATION', 'WORD_ORDER', 'SPELLING', 'NATURAL');

-- Gamification enums
CREATE TYPE badge_type AS ENUM ('STREAK', 'ACHIEVEMENT', 'MILESTONE', 'SPECIAL');
CREATE TYPE reward_type AS ENUM ('EXP', 'COINS', 'BADGE', 'ITEM');
CREATE TYPE transaction_type AS ENUM ('EARN', 'SPEND', 'REFUND', 'BONUS');
CREATE TYPE item_type AS ENUM ('AVATAR', 'THEME', 'BOOSTER', 'FEATURE_UNLOCK', 'DECORATION');
CREATE TYPE item_rarity AS ENUM ('COMMON', 'UNCOMMON', 'RARE', 'EPIC', 'LEGENDARY');

-- ==========================================================================
-- ==================== USER MANAGEMENT SCHEMA ==============================
-- ==========================================================================

-- Users table
CREATE TABLE user_management.users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255),

    -- Profile information
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    display_name VARCHAR(100),
    avatar_url TEXT,
    phone_number VARCHAR(20),
    date_of_birth DATE,
    gender gender,

    -- Account status
    role user_role NOT NULL DEFAULT 'LEARNER',
    status user_status NOT NULL DEFAULT 'PENDING_ACTIVATION',
    auth_provider auth_provider NOT NULL DEFAULT 'LOCAL',
    google_id VARCHAR(255) UNIQUE,

    -- Activation & Security
    activation_token VARCHAR(255),
    activation_token_expires_at TIMESTAMP WITH TIME ZONE,
    password_reset_token VARCHAR(255),
    password_reset_token_expires_at TIMESTAMP WITH TIME ZONE,
    last_password_change_at TIMESTAMP WITH TIME ZONE,
    failed_login_attempts INT DEFAULT 0,
    locked_until TIMESTAMP WITH TIME ZONE,

    -- Metadata
    last_login_at TIMESTAMP WITH TIME ZONE,
    email_verified_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP WITH TIME ZONE
);

-- User learning preferences (Personalization)
CREATE TABLE user_management.user_learning_preferences (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES user_management.users(id) ON DELETE CASCADE,

    -- Learning goals
    target_jlpt_level jlpt_level DEFAULT 'N5',
    daily_study_goal_minutes INT DEFAULT 30,
    preferred_study_time VARCHAR(20), -- 'morning', 'afternoon', 'evening', 'night'

    -- AI Practice Context Settings
    preferred_topics TEXT[], -- Array of preferred conversation topics
    learning_pace VARCHAR(20) DEFAULT 'normal', -- 'slow', 'normal', 'fast'
    preferred_formality VARCHAR(20) DEFAULT 'polite', -- 'casual', 'polite', 'formal'
    conversation_style VARCHAR(50), -- 'practical', 'creative', 'business', 'travel'

    -- Notification preferences
    streak_reminder_enabled BOOLEAN DEFAULT TRUE,
    reminder_time TIME DEFAULT '20:00',

    -- AI personalization data (JSONB for flexibility)
    weak_points JSONB DEFAULT '[]'::JSONB, -- Array of weak pronunciation/grammar points
    strong_points JSONB DEFAULT '[]'::JSONB,

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    UNIQUE(user_id)
);

-- Classes (for teacher-student organization)
CREATE TABLE user_management.classes (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    teacher_id UUID NOT NULL REFERENCES user_management.users(id),
    course_id UUID,
    max_students INT DEFAULT 50,

    start_date DATE,
    end_date DATE,

    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Class membership
CREATE TABLE user_management.class_members (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    class_id UUID NOT NULL REFERENCES user_management.classes(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES user_management.users(id) ON DELETE CASCADE,

    joined_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    removed_at TIMESTAMP WITH TIME ZONE,

    UNIQUE(class_id, user_id)
);

-- User sessions for tracking login sessions and refresh tokens
-- Supports both Mobile App and Web Browser sessions
CREATE TABLE user_management.user_sessions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES user_management.users(id) ON DELETE CASCADE,

    -- Token storage (session reference for JWT validation)
    refresh_token VARCHAR(512),

    -- Device information as JSONB for flexible storage
    -- For Mobile App: {
    --   "platform": "app",
    --   "device_type": "mobile|tablet",
    --   "os": "iOS 17.2|Android 14",
    --   "app_version": "2.1.0",
    --   "device_model": "iPhone 15|Samsung S24",
    --   "device_id": "unique-device-identifier",
    --   "push_token": "fcm_token_for_push_notifications"
    -- }
    -- For Web Browser: {
    --   "platform": "web",
    --   "device_type": "desktop|mobile",
    --   "os": "Windows 11|macOS|Linux",
    --   "browser": "Chrome 120|Safari 17|Firefox 121",
    --   "browser_version": "120.0.0"
    -- }
    device_info JSONB,

    -- Client network information
    ip_address INET,
    user_agent VARCHAR(500),

    -- Timestamps
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    last_active_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for user_sessions
CREATE INDEX idx_user_sessions_user_id ON user_management.user_sessions(user_id);
CREATE INDEX idx_user_sessions_refresh_token ON user_management.user_sessions(refresh_token);
CREATE INDEX idx_user_sessions_expires_at ON user_management.user_sessions(expires_at);
-- Partial index for active (non-revoked) sessions
-- Note: expires_at filtering done at query time (CURRENT_TIMESTAMP is not IMMUTABLE)
CREATE INDEX idx_user_sessions_active ON user_management.user_sessions(user_id)
    WHERE revoked_at IS NULL;

-- ==========================================================================
-- ==================== LEARNING CONTENT SCHEMA =============================
-- (For quiz/test assignments - imported from curriculum PDF)
-- ==========================================================================

-- Courses/Curriculum
CREATE TABLE learning_content.courses (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    description TEXT,

    jlpt_level jlpt_level NOT NULL,
    thumbnail_url TEXT,

    order_index INT DEFAULT 0,
    status course_status DEFAULT 'INACTIVE',

    created_by UUID REFERENCES user_management.users(id),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Lessons within courses (for quiz/test content extraction)
CREATE TABLE learning_content.lessons (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    course_id UUID NOT NULL REFERENCES learning_content.courses(id) ON DELETE CASCADE,

    title VARCHAR(255) NOT NULL,
    title_japanese VARCHAR(255),
    description TEXT,
    lesson_type lesson_type NOT NULL,

    -- Content
    content JSONB, -- Flexible content structure
    objectives TEXT[], -- Learning objectives

    -- Curriculum Import (PDF/Doc upload)
    content_file_url TEXT,
    content_file_name VARCHAR(255),
    processing_status processing_status DEFAULT 'COMPLETED',
    processed_at TIMESTAMP WITH TIME ZONE,
    processing_error TEXT,

    -- Page info (for imported curriculum)
    page_start INT,
    page_end INT,

    order_index INT DEFAULT 0,
    estimated_duration_minutes INT DEFAULT 30,

    status content_status DEFAULT 'DRAFT',

    created_by UUID REFERENCES user_management.users(id),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Vocabulary items (for quiz questions)
CREATE TABLE learning_content.vocabulary (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),

    japanese_text VARCHAR(255) NOT NULL,
    reading VARCHAR(255), -- Hiragana reading
    romaji VARCHAR(255), -- Romaji reading
    meaning_vi TEXT, -- Vietnamese meaning
    meaning_en TEXT, -- English meaning

    part_of_speech VARCHAR(50),
    jlpt_level jlpt_level,

    -- Extended vocabulary info
    kanji_writing VARCHAR(255),
    example_sentences JSONB DEFAULT '[]'::JSONB,
    synonyms TEXT[],
    antonyms TEXT[],
    audio_url TEXT,
    image_url TEXT,

    -- Usage notes
    usage_notes TEXT,
    common_collocations JSONB DEFAULT '[]'::JSONB,

    tags TEXT[],
    content_hash VARCHAR(64),

    created_by UUID REFERENCES user_management.users(id),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    UNIQUE(japanese_text, jlpt_level)
);

-- Grammar points (for quiz questions)
CREATE TABLE learning_content.grammar_points (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),

    pattern VARCHAR(255) NOT NULL,
    pattern_reading VARCHAR(255),
    pattern_romaji VARCHAR(255),

    meaning_vi TEXT,
    meaning_en TEXT,
    short_explanation TEXT,
    detailed_explanation TEXT,

    structure JSONB,
    conjugation_rules JSONB,
    formation_rules TEXT,

    example_sentences JSONB DEFAULT '[]'::JSONB,
    common_mistakes JSONB DEFAULT '[]'::JSONB,
    tips TEXT,
    related_grammar TEXT[],

    jlpt_level jlpt_level,
    category grammar_category DEFAULT 'OTHER',
    difficulty_score INT DEFAULT 1,

    tags TEXT[],
    audio_url TEXT,
    content_hash VARCHAR(64),

    created_by UUID REFERENCES user_management.users(id),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    UNIQUE(pattern, jlpt_level)
);

-- Lesson-Vocabulary mapping
CREATE TABLE learning_content.lesson_vocabulary (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    lesson_id UUID NOT NULL REFERENCES learning_content.lessons(id) ON DELETE CASCADE,
    vocabulary_id UUID NOT NULL REFERENCES learning_content.vocabulary(id) ON DELETE CASCADE,

    order_index INT DEFAULT 0,
    is_key_word BOOLEAN DEFAULT FALSE,

    UNIQUE(lesson_id, vocabulary_id)
);

-- Lesson-Grammar mapping
CREATE TABLE learning_content.lesson_grammar (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    lesson_id UUID NOT NULL REFERENCES learning_content.lessons(id) ON DELETE CASCADE,
    grammar_id UUID NOT NULL REFERENCES learning_content.grammar_points(id) ON DELETE CASCADE,

    order_index INT DEFAULT 0,
    is_key_point BOOLEAN DEFAULT FALSE,

    UNIQUE(lesson_id, grammar_id)
);

-- ==========================================================================
-- ==================== PRONUNCIATION SCHEMA ================================
-- (Topic-based - KHÔNG dựa trên bài giáo trình)
-- ==========================================================================

-- Pronunciation Topics (thay thế lesson-based)
CREATE TABLE pronunciation.pronunciation_topics (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),

    name VARCHAR(255) NOT NULL,
    name_japanese VARCHAR(255),
    description TEXT,

    -- Topic category
    category pronunciation_topic_category NOT NULL,

    -- Classification
    jlpt_level jlpt_level,
    difficulty_score INT DEFAULT 1, -- 1-5

    -- Learning focus
    focus_sounds TEXT[], -- e.g., ['あ', 'い', 'う'] or ['r/l distinction']
    tips TEXT,
    video_tutorial_url TEXT,

    -- Display
    thumbnail_url TEXT,
    order_index INT DEFAULT 0,
    status content_status DEFAULT 'DRAFT',

    created_by UUID REFERENCES user_management.users(id),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Pronunciation Items (belongs to Topic, NOT Lesson)
CREATE TABLE pronunciation.pronunciation_items (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    topic_id UUID NOT NULL REFERENCES pronunciation.pronunciation_topics(id) ON DELETE CASCADE,

    title VARCHAR(255) NOT NULL,
    description TEXT,

    -- Target sound/word
    japanese_text VARCHAR(500) NOT NULL,
    reading VARCHAR(500), -- Hiragana reading
    romaji VARCHAR(500),
    meaning_vi TEXT,
    meaning_en TEXT,

    phonetic_guide TEXT, -- IPA or detailed phonetic guide

    -- Reference audio
    reference_audio_url TEXT NOT NULL,
    slow_audio_url TEXT,

    -- Scoring criteria
    target_sounds TEXT[], -- Specific sounds to focus on
    common_mistakes JSONB DEFAULT '[]'::JSONB,
    tips TEXT,

    -- Difficulty
    difficulty_score INT DEFAULT 1, -- 1-5

    status content_status DEFAULT 'DRAFT',
    order_index INT DEFAULT 0,

    created_by UUID REFERENCES user_management.users(id),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- User pronunciation progress (per topic)
CREATE TABLE pronunciation.user_topic_progress (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES user_management.users(id) ON DELETE CASCADE,
    topic_id UUID NOT NULL REFERENCES pronunciation.pronunciation_topics(id) ON DELETE CASCADE,

    -- Progress
    items_practiced INT DEFAULT 0,
    items_mastered INT DEFAULT 0,
    total_items INT DEFAULT 0,
    completion_percentage DECIMAL(5,2) DEFAULT 0,

    -- Best scores
    best_accuracy_score DECIMAL(5,2) DEFAULT 0,
    best_fluency_score DECIMAL(5,2) DEFAULT 0,
    best_overall_score DECIMAL(5,2) DEFAULT 0,

    last_practiced_at TIMESTAMP WITH TIME ZONE,

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    UNIQUE(user_id, topic_id)
);

-- User pronunciation progress (per item)
CREATE TABLE pronunciation.user_item_progress (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES user_management.users(id) ON DELETE CASCADE,
    item_id UUID NOT NULL REFERENCES pronunciation.pronunciation_items(id) ON DELETE CASCADE,

    -- Best scores
    best_accuracy_score DECIMAL(5,2) DEFAULT 0,
    best_fluency_score DECIMAL(5,2) DEFAULT 0,
    best_prosody_score DECIMAL(5,2) DEFAULT 0,
    best_overall_score DECIMAL(5,2) DEFAULT 0,

    -- Practice stats
    total_attempts INT DEFAULT 0,
    successful_attempts INT DEFAULT 0, -- Score >= threshold
    last_practiced_at TIMESTAMP WITH TIME ZONE,

    -- Learning state
    is_mastered BOOLEAN DEFAULT FALSE,
    mastered_at TIMESTAMP WITH TIME ZONE,

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    UNIQUE(user_id, item_id)
);

-- Pronunciation attempts (JSONB for detailed analysis - thay thế MongoDB)
CREATE TABLE pronunciation.pronunciation_attempts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES user_management.users(id) ON DELETE CASCADE,
    item_id UUID NOT NULL REFERENCES pronunciation.pronunciation_items(id) ON DELETE CASCADE,

    -- Audio recordings
    user_audio_url TEXT,
    reference_audio_url TEXT,

    -- Target text
    target_text VARCHAR(500) NOT NULL,
    target_reading VARCHAR(500),

    -- AI Analysis Results (JSONB for flexibility)
    analysis JSONB DEFAULT '{}'::JSONB,
    /*
    analysis structure:
    {
        "overallScore": 85.5,
        "accuracyScore": 88.0,
        "fluencyScore": 82.0,
        "prosodyScore": 86.0,
        "transcribedText": "...",
        "confidence": 0.95,
        "phonemeAnalysis": [
            {"phoneme": "あ", "expected": "a", "detected": "a", "score": 95, "isCorrect": true}
        ],
        "wordAnalysis": [
            {"word": "ありがとう", "score": 88, "issues": ["pitch accent"]}
        ],
        "mistakes": [
            {"type": "PITCH", "position": "ありがとう", "expected": "high-low", "actual": "flat", "suggestion": "..."}
        ]
    }
    */

    -- AI Feedback (JSONB)
    feedback JSONB DEFAULT '{}'::JSONB,
    /*
    feedback structure:
    {
        "summary": "Good pronunciation overall...",
        "summaryVi": "Phát âm tốt nhìn chung...",
        "strengths": ["Clear vowel sounds", "Good rhythm"],
        "improvements": ["Work on pitch accent"],
        "tips": ["Try listening to native speakers"],
        "nextSteps": ["Practice minimal pairs"]
    }
    */

    -- Environment info
    environment_info JSONB DEFAULT '{}'::JSONB,

    -- Metadata
    processing_time_ms INT,

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_pronunciation_attempts_user ON pronunciation.pronunciation_attempts(user_id, created_at DESC);
CREATE INDEX idx_pronunciation_attempts_item ON pronunciation.pronunciation_attempts(item_id);
CREATE INDEX idx_pronunciation_attempts_analysis ON pronunciation.pronunciation_attempts USING gin(analysis);

-- ==========================================================================
-- ==================== SPEAKING/CONVERSATION TOPICS ========================
-- (Must be created BEFORE ai_conversation due to foreign key reference)
-- ==========================================================================

-- Conversation topics (for AI practice)
CREATE TABLE speaking.conversation_topics (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),

    title VARCHAR(255) NOT NULL,
    title_japanese VARCHAR(255),
    description TEXT,

    -- Topic context
    scenario TEXT,
    vocabulary_hints JSONB DEFAULT '[]'::JSONB,
    grammar_points JSONB DEFAULT '[]'::JSONB,
    useful_expressions JSONB DEFAULT '[]'::JSONB,

    -- Classification
    category VARCHAR(100), -- 'daily_life', 'travel', 'business', 'school', etc.
    jlpt_level jlpt_level,
    difficulty_score INT DEFAULT 1,

    -- For roleplay
    is_roleplay BOOLEAN DEFAULT FALSE,
    roleplay_context JSONB,

    thumbnail_url TEXT,
    status content_status DEFAULT 'DRAFT',
    order_index INT DEFAULT 0,

    created_by UUID REFERENCES user_management.users(id),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- User conversation progress
CREATE TABLE speaking.user_conversation_progress (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES user_management.users(id) ON DELETE CASCADE,
    topic_id UUID NOT NULL REFERENCES speaking.conversation_topics(id) ON DELETE CASCADE,

    -- Practice stats
    total_sessions INT DEFAULT 0,
    total_messages_sent INT DEFAULT 0,

    -- Best scores
    best_fluency_score DECIMAL(5,2) DEFAULT 0,
    best_accuracy_score DECIMAL(5,2) DEFAULT 0,
    best_vocabulary_score DECIMAL(5,2) DEFAULT 0,
    best_overall_score DECIMAL(5,2) DEFAULT 0,

    -- Learning state
    last_practiced_at TIMESTAMP WITH TIME ZONE,
    is_completed BOOLEAN DEFAULT FALSE,
    completed_at TIMESTAMP WITH TIME ZONE,

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    UNIQUE(user_id, topic_id)
);

-- ==========================================================================
-- ==================== AI CONVERSATION SCHEMA ==============================
-- (Replaces MongoDB - uses JSONB for flexible document storage)
-- ==========================================================================

-- Conversation Sessions
CREATE TABLE ai_conversation.conversation_sessions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES user_management.users(id) ON DELETE CASCADE,

    session_type session_type NOT NULL,

    -- Topic reference (optional - can be free talk)
    topic_id UUID REFERENCES speaking.conversation_topics(id),
    assignment_id UUID, -- Reference to assignment if part of assignment

    -- Session context (JSONB for flexibility)
    context JSONB DEFAULT '{}'::JSONB,
    /*
    context structure:
    {
        "topic": "Ordering at a restaurant",
        "scenario": "You are at a Japanese restaurant...",
        "jlptLevel": "N5",
        "userRole": "Customer",
        "aiRole": "Waiter",
        "objectives": ["Order food", "Ask for recommendations"],
        "vocabularyFocus": ["注文", "おすすめ"],
        "grammarFocus": ["～をください", "～はありますか"]
    }
    */

    -- Session status
    status session_status DEFAULT 'ACTIVE',

    -- Scoring & Feedback (JSONB)
    feedback JSONB DEFAULT '{}'::JSONB,
    /*
    feedback structure:
    {
        "overallScore": 85.0,
        "fluencyScore": 88.0,
        "accuracyScore": 82.0,
        "vocabularyScore": 85.0,
        "grammarScore": 84.0,
        "strengths": ["Good use of polite forms"],
        "improvements": ["Work on particle usage"],
        "summary": "Great conversation practice..."
    }
    */

    -- Stats
    message_count INT DEFAULT 0,
    user_message_count INT DEFAULT 0,
    hints_used INT DEFAULT 0,
    corrections_count INT DEFAULT 0,
    duration_seconds INT DEFAULT 0,

    -- Timestamps
    completed_at TIMESTAMP WITH TIME ZONE,

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Conversation Messages
CREATE TABLE ai_conversation.conversation_messages (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    session_id UUID NOT NULL REFERENCES ai_conversation.conversation_sessions(id) ON DELETE CASCADE,

    role message_role NOT NULL,

    -- Content
    content TEXT NOT NULL, -- Original message content
    japanese_content TEXT, -- Japanese version
    translation TEXT, -- Vietnamese/English translation
    romaji TEXT, -- Romaji reading

    -- Audio
    audio_url TEXT,
    user_audio_url TEXT,

    -- AI Corrections (JSONB array)
    corrections JSONB DEFAULT '[]'::JSONB,
    /*
    corrections structure:
    [
        {
            "original": "私は食べるます",
            "corrected": "私は食べます",
            "explanation": "Conjugation error...",
            "explanationVi": "Lỗi chia động từ...",
            "type": "CONJUGATION"
        }
    ]
    */

    -- Vocabulary used (JSONB array)
    vocabulary_used JSONB DEFAULT '[]'::JSONB,
    /*
    vocabulary_used structure:
    [
        {"word": "食べる", "reading": "たべる", "meaning": "to eat", "isNew": true}
    ]
    */

    -- Message metadata
    is_hint_request BOOLEAN DEFAULT FALSE,
    sequence_number INT NOT NULL,
    processing_time_ms INT,
    
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_conversation_sessions_user ON ai_conversation.conversation_sessions(user_id, created_at DESC);
CREATE INDEX idx_conversation_sessions_status ON ai_conversation.conversation_sessions(user_id, status);
CREATE INDEX idx_conversation_sessions_topic ON ai_conversation.conversation_sessions(topic_id);
CREATE INDEX idx_conversation_sessions_assignment ON ai_conversation.conversation_sessions(assignment_id);
CREATE INDEX idx_conversation_sessions_context ON ai_conversation.conversation_sessions USING gin(context);

CREATE INDEX idx_conversation_messages_session ON ai_conversation.conversation_messages(session_id, sequence_number);
CREATE INDEX idx_conversation_messages_corrections ON ai_conversation.conversation_messages USING gin(corrections);
CREATE INDEX idx_conversation_messages_content ON ai_conversation.conversation_messages USING gin(to_tsvector('simple', content));

-- Translation Cache (previously MongoDB)
CREATE TABLE ai_conversation.translation_cache (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),

    source_text TEXT NOT NULL,
    source_language VARCHAR(5) NOT NULL, -- 'ja', 'vi', 'en'
    target_language VARCHAR(5) NOT NULL,

    translated_text TEXT NOT NULL,

    -- For words
    is_word BOOLEAN DEFAULT FALSE,
    word_info JSONB DEFAULT '{}'::JSONB,
    /*
    word_info structure:
    {
        "reading": "たべる",
        "partOfSpeech": "verb",
        "jlptLevel": "N5",
        "examples": [...]
    }
    */

    -- Cache metadata
    hit_count INT DEFAULT 0,
    last_accessed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    UNIQUE(source_text, source_language, target_language)
);

CREATE INDEX idx_translation_cache_lookup ON ai_conversation.translation_cache(source_text, source_language, target_language);
CREATE INDEX idx_translation_cache_ttl ON ai_conversation.translation_cache(last_accessed_at);

-- ==========================================================================
-- ==================== ASSIGNMENT SCHEMA ===================================
-- (Quiz-based assignments from curriculum content)
-- ==========================================================================

-- Quiz Questions (generated from curriculum)
CREATE TABLE assignment.quiz_questions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),

    -- Question content
    question_text TEXT NOT NULL,
    question_japanese TEXT,
    question_type VARCHAR(50) NOT NULL, -- 'multiple_choice', 'fill_blank', 'matching', 'true_false', 'ordering'

    -- Options for multiple choice (JSONB)
    options JSONB DEFAULT '[]'::JSONB,
    /*
    options structure:
    [
        {"id": "A", "text": "食べる", "isCorrect": true},
        {"id": "B", "text": "食べます", "isCorrect": false}
    ]
    */

    -- Correct answer
    correct_answer TEXT, -- For fill_blank, true_false
    correct_answers JSONB, -- For matching, ordering: [{"left": "A", "right": "1"}]

    -- Explanation
    explanation TEXT,
    explanation_vi TEXT,

    -- Source
    vocabulary_id UUID REFERENCES learning_content.vocabulary(id),
    grammar_id UUID REFERENCES learning_content.grammar_points(id),
    lesson_id UUID REFERENCES learning_content.lessons(id),

    -- Classification
    jlpt_level jlpt_level,
    difficulty_score INT DEFAULT 1,
    tags TEXT[],

    -- Media
    audio_url TEXT,
    image_url TEXT,

    status content_status DEFAULT 'DRAFT',

    created_by UUID REFERENCES user_management.users(id),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Assignments (quiz/test)
CREATE TABLE assignment.assignments (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),

    title VARCHAR(255) NOT NULL,
    description TEXT,
    instructions TEXT,

    assignment_type assignment_type NOT NULL,

    -- Timing
    start_date TIMESTAMP WITH TIME ZONE,
    due_date TIMESTAMP WITH TIME ZONE NOT NULL,
    time_limit_minutes INT, -- NULL = no limit
    late_submission_allowed BOOLEAN DEFAULT FALSE,
    late_submission_penalty_percent INT DEFAULT 0,

    -- Scoring
    max_score INT DEFAULT 100,
    passing_score INT DEFAULT 60,

    -- Quiz settings
    shuffle_questions BOOLEAN DEFAULT FALSE,
    shuffle_options BOOLEAN DEFAULT FALSE,
    show_correct_answers BOOLEAN DEFAULT TRUE, -- After submission
    allow_retry BOOLEAN DEFAULT FALSE,
    max_retries INT DEFAULT 1,

    -- Content references (link to lesson for quiz generation)
    lesson_id UUID REFERENCES learning_content.lessons(id),
    question_count INT DEFAULT 10, -- How many questions to include

    status assignment_status DEFAULT 'DRAFT',

    created_by UUID NOT NULL REFERENCES user_management.users(id),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Assignment Questions (many-to-many with order)
CREATE TABLE assignment.assignment_questions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    assignment_id UUID NOT NULL REFERENCES assignment.assignments(id) ON DELETE CASCADE,
    question_id UUID NOT NULL REFERENCES assignment.quiz_questions(id) ON DELETE CASCADE,

    order_index INT DEFAULT 0,
    points INT DEFAULT 10, -- Points for this question

    UNIQUE(assignment_id, question_id)
);

-- Assignment to class/user mapping
CREATE TABLE assignment.assignment_targets (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    assignment_id UUID NOT NULL REFERENCES assignment.assignments(id) ON DELETE CASCADE,

    class_id UUID REFERENCES user_management.classes(id) ON DELETE CASCADE,
    user_id UUID REFERENCES user_management.users(id) ON DELETE CASCADE,

    assigned_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    assigned_by UUID REFERENCES user_management.users(id),

    CHECK (class_id IS NOT NULL OR user_id IS NOT NULL)
);

-- User assignment submissions
CREATE TABLE assignment.assignment_submissions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    assignment_id UUID NOT NULL REFERENCES assignment.assignments(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES user_management.users(id) ON DELETE CASCADE,

    status submission_status DEFAULT 'NOT_STARTED',
    attempt_number INT DEFAULT 1,

    -- Progress
    started_at TIMESTAMP WITH TIME ZONE,
    submitted_at TIMESTAMP WITH TIME ZONE,

    -- Scoring
    score DECIMAL(5,2),
    correct_count INT DEFAULT 0,
    total_questions INT DEFAULT 0,
    feedback TEXT,
    graded_by UUID REFERENCES user_management.users(id),
    graded_at TIMESTAMP WITH TIME ZONE,

    -- Time tracking
    time_spent_seconds INT DEFAULT 0,

    -- Answers (JSONB)
    answers JSONB DEFAULT '[]'::JSONB,
    /*
    answers structure:
    [
        {"questionId": "uuid", "selectedAnswer": "A", "isCorrect": true, "timeSpent": 15}
    ]
    */

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    UNIQUE(assignment_id, user_id, attempt_number)
);

-- ==========================================================================
-- ==================== PROGRESS & ANALYTICS SCHEMA =========================
-- ==========================================================================

-- Daily learning activity
CREATE TABLE progress.daily_activity (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES user_management.users(id) ON DELETE CASCADE,
    activity_date DATE NOT NULL,

    -- Time spent
    total_study_minutes INT DEFAULT 0,
    pronunciation_minutes INT DEFAULT 0,
    conversation_minutes INT DEFAULT 0,
    quiz_minutes INT DEFAULT 0,

    -- Practice counts
    pronunciation_attempts INT DEFAULT 0,
    conversation_messages INT DEFAULT 0,
    quizzes_completed INT DEFAULT 0,

    -- Scores
    avg_pronunciation_score DECIMAL(5,2),
    avg_conversation_score DECIMAL(5,2),
    avg_quiz_score DECIMAL(5,2),

    -- EXP earned
    exp_earned INT DEFAULT 0,

    -- Detailed analytics (JSONB)
    analytics JSONB DEFAULT '{}'::JSONB,
    /*
    analytics structure:
    {
        "pronunciationAttempts": 10,
        "conversationSessions": 2,
        "newVocabularyLearned": ["食べる", "飲む"],
        "grammarPointsPracticed": ["～ます", "～ません"],
        "commonMistakes": [
            {"type": "PARTICLE", "category": "は/が", "count": 3}
        ]
    }
    */

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    UNIQUE(user_id, activity_date)
);

-- User overall progress
CREATE TABLE progress.user_progress (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES user_management.users(id) ON DELETE CASCADE,

    -- Current level
    current_jlpt_level jlpt_level DEFAULT 'N5',

    -- Overall stats
    total_study_minutes INT DEFAULT 0,
    total_pronunciation_attempts INT DEFAULT 0,
    total_conversation_sessions INT DEFAULT 0,
    total_quizzes_completed INT DEFAULT 0,

    -- Average scores
    avg_pronunciation_accuracy DECIMAL(5,2) DEFAULT 0,
    avg_conversation_fluency DECIMAL(5,2) DEFAULT 0,
    avg_quiz_score DECIMAL(5,2) DEFAULT 0,

    -- Mastery counts
    pronunciation_topics_completed INT DEFAULT 0,
    pronunciation_items_mastered INT DEFAULT 0,
    conversation_topics_completed INT DEFAULT 0,

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    UNIQUE(user_id)
);

-- Common mistakes tracking
CREATE TABLE progress.user_mistakes (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES user_management.users(id) ON DELETE CASCADE,

    mistake_type VARCHAR(50) NOT NULL, -- 'pronunciation', 'grammar', 'vocabulary'
    mistake_category VARCHAR(100), -- specific sound, grammar point, etc.

    -- Details
    original_text TEXT,
    correct_text TEXT,
    explanation TEXT,

    occurrence_count INT DEFAULT 1,
    last_occurred_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    -- Resolution
    is_resolved BOOLEAN DEFAULT FALSE,
    resolved_at TIMESTAMP WITH TIME ZONE,

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_user_mistakes_user_type ON progress.user_mistakes(user_id, mistake_type);

-- ==========================================================================
-- ==================== GAMIFICATION SCHEMA =================================
-- ==========================================================================

-- User gamification stats
CREATE TABLE gamification.user_stats (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES user_management.users(id) ON DELETE CASCADE,

    -- Points
    total_exp INT DEFAULT 0,
    current_level INT DEFAULT 1,
    exp_to_next_level INT DEFAULT 100,

    -- Currency
    coins INT DEFAULT 0,

    -- Streaks
    current_streak INT DEFAULT 0,
    longest_streak INT DEFAULT 0,
    streak_last_date DATE,
    streak_freeze_count INT DEFAULT 0,

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    UNIQUE(user_id)
);

-- Streak history
CREATE TABLE gamification.streak_history (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES user_management.users(id) ON DELETE CASCADE,

    streak_date DATE NOT NULL,
    streak_count INT NOT NULL,

    activity_type VARCHAR(50),
    exp_earned INT DEFAULT 0,
    freeze_used BOOLEAN DEFAULT FALSE,

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    UNIQUE(user_id, streak_date)
);

-- Badges definition
CREATE TABLE gamification.badges (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),

    name VARCHAR(100) NOT NULL,
    name_japanese VARCHAR(100),
    description TEXT,

    badge_type badge_type NOT NULL,

    requirement_type VARCHAR(50),
    requirement_value INT,
    requirement_details JSONB,

    icon_url TEXT,
    color VARCHAR(20),
    rarity item_rarity DEFAULT 'COMMON',

    exp_reward INT DEFAULT 0,
    coin_reward INT DEFAULT 0,

    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- User earned badges
CREATE TABLE gamification.user_badges (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES user_management.users(id) ON DELETE CASCADE,
    badge_id UUID NOT NULL REFERENCES gamification.badges(id) ON DELETE CASCADE,

    earned_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    is_featured BOOLEAN DEFAULT FALSE,

    UNIQUE(user_id, badge_id)
);

-- Leaderboard
CREATE TABLE gamification.leaderboard (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES user_management.users(id) ON DELETE CASCADE,

    global_rank INT,
    weekly_rank INT,
    class_rank INT,

    total_exp INT DEFAULT 0,
    weekly_exp INT DEFAULT 0,
    monthly_exp INT DEFAULT 0,

    period_start DATE,
    period_end DATE,

    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    UNIQUE(user_id, period_start)
);

-- Reward transactions
CREATE TABLE gamification.reward_transactions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES user_management.users(id) ON DELETE CASCADE,

    transaction_type transaction_type NOT NULL,
    reward_type reward_type NOT NULL,

    amount INT NOT NULL,
    balance_after INT,

    source_type VARCHAR(50),
    source_id UUID,
    description TEXT,

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_reward_transactions_user ON gamification.reward_transactions(user_id, created_at DESC);

-- ==========================================================================
-- ==================== SHOP SCHEMA =========================================
-- ==========================================================================

-- Shop items
CREATE TABLE shop.items (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),

    name VARCHAR(255) NOT NULL,
    name_japanese VARCHAR(255),
    description TEXT,

    item_type item_type NOT NULL,
    rarity item_rarity DEFAULT 'COMMON',

    price_coins INT DEFAULT 0,
    price_real_money DECIMAL(10,2),

    is_available BOOLEAN DEFAULT TRUE,
    available_from TIMESTAMP WITH TIME ZONE,
    available_until TIMESTAMP WITH TIME ZONE,
    stock_limit INT,

    thumbnail_url TEXT,
    preview_url TEXT,

    item_data JSONB,

    created_by UUID REFERENCES user_management.users(id),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- User items
CREATE TABLE shop.user_items (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES user_management.users(id) ON DELETE CASCADE,
    item_id UUID NOT NULL REFERENCES shop.items(id) ON DELETE CASCADE,

    purchased_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    price_paid INT,

    is_equipped BOOLEAN DEFAULT FALSE,
    equipped_at TIMESTAMP WITH TIME ZONE,

    quantity INT DEFAULT 1,

    UNIQUE(user_id, item_id)
);

-- Purchase history
CREATE TABLE shop.purchase_history (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES user_management.users(id) ON DELETE CASCADE,
    item_id UUID NOT NULL REFERENCES shop.items(id),

    quantity INT DEFAULT 1,
    total_price INT NOT NULL,
    payment_method VARCHAR(50),

    payment_reference VARCHAR(255),
    payment_status VARCHAR(50),

    purchased_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- ==========================================================================
-- ==================== SYSTEM ADMIN SCHEMA =================================
-- ==========================================================================

-- System usage statistics
CREATE TABLE system_admin.usage_statistics (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    stat_date DATE NOT NULL,

    total_users INT DEFAULT 0,
    active_users INT DEFAULT 0,
    new_users INT DEFAULT 0,

    total_sessions INT DEFAULT 0,
    total_pronunciation_practices INT DEFAULT 0,
    total_conversation_sessions INT DEFAULT 0,
    total_quizzes_completed INT DEFAULT 0,

    avg_response_time_ms INT,
    error_count INT DEFAULT 0,

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    UNIQUE(stat_date)
);

-- Import history
CREATE TABLE system_admin.import_history (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),

    import_type VARCHAR(50) NOT NULL,
    file_name VARCHAR(255),

    total_records INT DEFAULT 0,
    successful_records INT DEFAULT 0,
    failed_records INT DEFAULT 0,

    error_details JSONB,

    imported_by UUID REFERENCES user_management.users(id),
    imported_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Curriculum imports
CREATE TABLE system_admin.curriculum_imports (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),

    original_filename VARCHAR(255) NOT NULL,
    file_url TEXT NOT NULL,
    file_size_bytes BIGINT,
    file_type VARCHAR(50),
    total_pages INT,

    course_id UUID REFERENCES learning_content.courses(id),

    status processing_status NOT NULL DEFAULT 'QUEUED',
    current_step VARCHAR(50),

    progress_percent DECIMAL(5,2) DEFAULT 0,
    pages_processed INT DEFAULT 0,
    chunks_created INT DEFAULT 0,
    embeddings_created INT DEFAULT 0,

    lessons_created INT DEFAULT 0,
    grammar_points_created INT DEFAULT 0,
    vocabulary_created INT DEFAULT 0,
    quiz_questions_created INT DEFAULT 0,

    queued_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    estimated_completion_at TIMESTAMP WITH TIME ZONE,

    estimated_cost_usd DECIMAL(10,4),
    actual_cost_usd DECIMAL(10,4),
    tokens_used INT DEFAULT 0,

    error_message TEXT,
    error_details JSONB,
    retry_count INT DEFAULT 0,
    max_retries INT DEFAULT 3,

    created_by UUID NOT NULL REFERENCES user_management.users(id),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_curriculum_imports_status ON system_admin.curriculum_imports(status);
CREATE INDEX idx_curriculum_imports_course ON system_admin.curriculum_imports(course_id);

-- Import chunks
CREATE TABLE system_admin.import_chunks (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    import_id UUID NOT NULL REFERENCES system_admin.curriculum_imports(id) ON DELETE CASCADE,

    chunk_index INT NOT NULL,
    chunk_type VARCHAR(50),
    content TEXT NOT NULL,

    page_start INT,
    page_end INT,

    status VARCHAR(20) DEFAULT 'PENDING',

    extracted_data JSONB,
    extraction_confidence DECIMAL(3,2),

    embedding_id UUID,

    token_count INT,
    processing_time_ms INT,
    error_message TEXT,

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_import_chunks_import ON system_admin.import_chunks(import_id, status);

-- Audit log
CREATE TABLE system_admin.audit_log (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),

    user_id UUID REFERENCES user_management.users(id),
    action VARCHAR(100) NOT NULL,
    entity_type VARCHAR(100),
    entity_id UUID,

    old_values JSONB,
    new_values JSONB,

    ip_address INET,
    user_agent TEXT,

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_log_user ON system_admin.audit_log(user_id, created_at DESC);
CREATE INDEX idx_audit_log_entity ON system_admin.audit_log(entity_type, entity_id);

-- Notification Queue (previously MongoDB)
CREATE TABLE system_admin.notification_queue (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES user_management.users(id) ON DELETE CASCADE,

    notification_type VARCHAR(50) NOT NULL, -- 'STREAK_REMINDER', 'ASSIGNMENT_DUE', etc.

    title VARCHAR(255),
    body TEXT,
    data JSONB DEFAULT '{}'::JSONB,

    status VARCHAR(20) DEFAULT 'PENDING', -- 'PENDING', 'SENT', 'FAILED', 'CANCELLED'
    channel VARCHAR(20), -- 'PUSH', 'EMAIL', 'IN_APP'

    scheduled_at TIMESTAMP WITH TIME ZONE NOT NULL,
    sent_at TIMESTAMP WITH TIME ZONE,

    retry_count INT DEFAULT 0,
    last_error TEXT,

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_notification_queue_user ON system_admin.notification_queue(user_id, status);
CREATE INDEX idx_notification_queue_status ON system_admin.notification_queue(status, scheduled_at);

-- ==========================================================================
-- ==================== AI PERSONALIZATION ==================================
-- ==========================================================================

-- User embeddings
CREATE TABLE progress.user_embeddings (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES user_management.users(id) ON DELETE CASCADE,

    embedding vector(1536),
    embedding_type VARCHAR(50) NOT NULL,

    metadata JSONB,

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_user_embeddings_vector ON progress.user_embeddings
USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- Content embeddings
CREATE TABLE progress.content_embeddings (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),

    content_type VARCHAR(50) NOT NULL,
    content_id UUID NOT NULL,

    embedding vector(1536),
    text_content TEXT NOT NULL,

    metadata JSONB DEFAULT '{}'::JSONB,

    chunk_index INT,
    import_id UUID,

    token_count INT,

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    UNIQUE(content_type, content_id, chunk_index)
);

CREATE INDEX idx_content_embeddings_vector ON progress.content_embeddings
USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
CREATE INDEX idx_content_embeddings_type ON progress.content_embeddings(content_type);
CREATE INDEX idx_content_embeddings_metadata ON progress.content_embeddings USING gin(metadata);


-- ====================FOREIGN KEY =========================================
ALTER TABLE user_management.classes ADD CONSTRAINT fk_classes_course FOREIGN KEY (course_id) REFERENCES learning_content.courses(id);
-- ==========================================================================
-- ==================== ADDITIONAL INDEXES ==================================
-- ==========================================================================

-- User Management
CREATE INDEX idx_users_email ON user_management.users(email);
CREATE INDEX idx_users_status ON user_management.users(status);
CREATE INDEX idx_users_role ON user_management.users(role);
CREATE INDEX idx_users_google_id ON user_management.users(google_id);
CREATE INDEX idx_class_members_user ON user_management.class_members(user_id);
CREATE INDEX idx_class_members_class ON user_management.class_members(class_id);

-- Learning Content
CREATE INDEX idx_lessons_course ON learning_content.lessons(course_id);
CREATE INDEX idx_lessons_processing ON learning_content.lessons(processing_status);
CREATE INDEX idx_vocabulary_jlpt ON learning_content.vocabulary(jlpt_level);
CREATE INDEX idx_vocabulary_text ON learning_content.vocabulary USING gin(japanese_text gin_trgm_ops);
CREATE INDEX idx_vocabulary_hash ON learning_content.vocabulary(content_hash);
CREATE INDEX idx_grammar_jlpt ON learning_content.grammar_points(jlpt_level);
CREATE INDEX idx_grammar_pattern ON learning_content.grammar_points USING gin(pattern gin_trgm_ops);
CREATE INDEX idx_grammar_category ON learning_content.grammar_points(category);
CREATE INDEX idx_grammar_hash ON learning_content.grammar_points(content_hash);
CREATE INDEX idx_lesson_vocabulary_lesson ON learning_content.lesson_vocabulary(lesson_id);
CREATE INDEX idx_lesson_grammar_lesson ON learning_content.lesson_grammar(lesson_id);

-- Pronunciation
CREATE INDEX idx_pronunciation_topics_category ON pronunciation.pronunciation_topics(category);
CREATE INDEX idx_pronunciation_topics_level ON pronunciation.pronunciation_topics(jlpt_level);
CREATE INDEX idx_pronunciation_items_topic ON pronunciation.pronunciation_items(topic_id);
CREATE INDEX idx_pronunciation_item_progress_user ON pronunciation.user_item_progress(user_id);
CREATE INDEX idx_pronunciation_topic_progress_user ON pronunciation.user_topic_progress(user_id);

-- Speaking
CREATE INDEX idx_conversation_topics_level ON speaking.conversation_topics(jlpt_level);
CREATE INDEX idx_conversation_topics_category ON speaking.conversation_topics(category);
CREATE INDEX idx_conversation_progress_user ON speaking.user_conversation_progress(user_id);

-- Assignment
CREATE INDEX idx_quiz_questions_level ON assignment.quiz_questions(jlpt_level);
CREATE INDEX idx_quiz_questions_lesson ON assignment.quiz_questions(lesson_id);
CREATE INDEX idx_assignments_created_by ON assignment.assignments(created_by);
CREATE INDEX idx_assignment_targets_assignment ON assignment.assignment_targets(assignment_id);
CREATE INDEX idx_assignment_submissions_user ON assignment.assignment_submissions(user_id);
CREATE INDEX idx_assignment_submissions_status ON assignment.assignment_submissions(status);

-- Progress
CREATE INDEX idx_daily_activity_user_date ON progress.daily_activity(user_id, activity_date DESC);

-- Gamification
CREATE INDEX idx_streak_history_user_date ON gamification.streak_history(user_id, streak_date DESC);
CREATE INDEX idx_leaderboard_global ON gamification.leaderboard(global_rank) WHERE global_rank IS NOT NULL;
CREATE INDEX idx_leaderboard_weekly ON gamification.leaderboard(weekly_rank) WHERE weekly_rank IS NOT NULL;

-- ==========================================================================
-- ==================== FUNCTIONS ===========================================
-- ==========================================================================

-- Update timestamp function
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

-- Apply trigger to all tables with updated_at
DO $$
DECLARE
    t text;
BEGIN
    FOR t IN
        SELECT table_schema || '.' || table_name
        FROM information_schema.columns
        WHERE column_name = 'updated_at'
        AND table_schema IN ('user_management', 'learning_content', 'pronunciation',
                            'speaking', 'assignment', 'progress', 'gamification',
                            'shop', 'ai_conversation')
    LOOP
        EXECUTE format('
            CREATE TRIGGER update_%s_updated_at
            BEFORE UPDATE ON %s
            FOR EACH ROW
            EXECUTE FUNCTION update_updated_at_column();
        ', replace(replace(t, '.', '_'), ' ', ''), t);
    END LOOP;
END;
$$;

-- Level calculation function
CREATE OR REPLACE FUNCTION gamification.calculate_level(exp INT)
RETURNS INT AS $$
BEGIN
    RETURN GREATEST(1, FLOOR(SQRT(exp::float / 100)) + 1)::INT;
END;
$$ LANGUAGE plpgsql;

-- EXP for level function
CREATE OR REPLACE FUNCTION gamification.exp_for_level(level INT)
RETURNS INT AS $$
BEGIN
    RETURN ((level - 1) * (level - 1) * 100)::INT;
END;
$$ LANGUAGE plpgsql;

-- ==========================================================================
-- ==================== PERMISSIONS =========================================
-- ==========================================================================

GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA user_management TO satori_user;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA learning_content TO satori_user;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA pronunciation TO satori_user;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA speaking TO satori_user;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA assignment TO satori_user;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA progress TO satori_user;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA gamification TO satori_user;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA shop TO satori_user;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA system_admin TO satori_user;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA ai_conversation TO satori_user;

GRANT USAGE ON ALL SEQUENCES IN SCHEMA user_management TO satori_user;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA learning_content TO satori_user;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA pronunciation TO satori_user;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA speaking TO satori_user;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA assignment TO satori_user;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA progress TO satori_user;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA gamification TO satori_user;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA shop TO satori_user;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA system_admin TO satori_user;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA ai_conversation TO satori_user;

-- ==========================================================================
-- ==================== SEED DATA ===========================================
-- ==========================================================================

-- Default pronunciation topics
INSERT INTO pronunciation.pronunciation_topics (name, name_japanese, description, category, jlpt_level, difficulty_score, focus_sounds, order_index, status) VALUES
('Hiragana Vowels', 'ひらがな母音', 'Learn the 5 basic Japanese vowels: あ、い、う、え、お', 'HIRAGANA', 'N5', 1, ARRAY['あ', 'い', 'う', 'え', 'お'], 1, 'PUBLISHED'),
('Hiragana K-row', 'ひらがなか行', 'Practice the K consonant sounds: か、き、く、け、こ', 'HIRAGANA', 'N5', 1, ARRAY['か', 'き', 'く', 'け', 'こ'], 2, 'PUBLISHED'),
('Hiragana S-row', 'ひらがなさ行', 'Practice the S consonant sounds: さ、し、す、せ、そ', 'HIRAGANA', 'N5', 1, ARRAY['さ', 'し', 'す', 'せ', 'そ'], 3, 'PUBLISHED'),
('Hiragana T-row', 'ひらがなた行', 'Practice the T consonant sounds: た、ち、つ、て、と', 'HIRAGANA', 'N5', 2, ARRAY['た', 'ち', 'つ', 'て', 'と'], 4, 'PUBLISHED'),
('Hiragana N-row', 'ひらがなな行', 'Practice the N consonant sounds: な、に、ぬ、ね、の', 'HIRAGANA', 'N5', 1, ARRAY['な', 'に', 'ぬ', 'ね', 'の'], 5, 'PUBLISHED'),
('R/L Distinction', 'ら行の発音', 'Master the Japanese R sound (between R and L)', 'CONSONANT', 'N5', 3, ARRAY['ら', 'り', 'る', 'れ', 'ろ'], 10, 'PUBLISHED'),
('Long Vowels', '長音', 'Practice long vowel sounds: おお、ええ、etc.', 'VOWEL', 'N5', 2, ARRAY['おう', 'えい', 'ああ'], 11, 'PUBLISHED'),
('Double Consonants', '促音', 'Practice double consonant sounds (っ)', 'CONSONANT', 'N4', 3, ARRAY['っ', 'きっと', 'ずっと'], 15, 'PUBLISHED'),
('Pitch Accent Basics', 'アクセント基礎', 'Introduction to Japanese pitch accent patterns', 'PITCH_ACCENT', 'N4', 4, ARRAY['高低', 'アクセント'], 20, 'PUBLISHED'),
('Common Greetings', '挨拶', 'Practice pronunciation of common Japanese greetings', 'PHRASE', 'N5', 2, ARRAY['おはよう', 'こんにちは', 'ありがとう'], 25, 'PUBLISHED'),
('Self Introduction', '自己紹介', 'Practice introducing yourself in Japanese', 'SENTENCE', 'N5', 3, ARRAY['はじめまして', '～です', 'よろしく'], 26, 'PUBLISHED'),
('Minimal Pairs: し/ち', 'し/ちの区別', 'Distinguish between し and ち sounds', 'MINIMAL_PAIR', 'N5', 3, ARRAY['し', 'ち'], 30, 'PUBLISHED');

-- Default badges
INSERT INTO gamification.badges (name, name_japanese, description, badge_type, requirement_type, requirement_value, icon_url, rarity, exp_reward, coin_reward) VALUES
('First Step', '第一歩', 'Complete your first practice session', 'ACHIEVEMENT', 'practice_count', 1, '/badges/first-step.png', 'COMMON', 10, 5),
('Week Warrior', '週間戦士', 'Maintain a 7-day streak', 'STREAK', 'streak', 7, '/badges/week-warrior.png', 'UNCOMMON', 50, 25),
('Month Master', '月間マスター', 'Maintain a 30-day streak', 'STREAK', 'streak', 30, '/badges/month-master.png', 'RARE', 200, 100),
('Century Streak', '百日達成', 'Maintain a 100-day streak', 'STREAK', 'streak', 100, '/badges/century-streak.png', 'EPIC', 500, 250),
('Pronunciation Pro', '発音プロ', 'Master 50 pronunciation items', 'MILESTONE', 'pronunciation_mastered', 50, '/badges/pronunciation-pro.png', 'RARE', 150, 75),
('Conversation King', '会話王', 'Complete 100 conversation sessions', 'MILESTONE', 'conversation_count', 100, '/badges/conversation-king.png', 'RARE', 150, 75),
('Quiz Master', 'テストマスター', 'Complete 50 quizzes with 80%+ score', 'MILESTONE', 'quiz_master', 50, '/badges/quiz-master.png', 'RARE', 150, 75),
('Perfect Score', '満点', 'Get 100% on any practice', 'ACHIEVEMENT', 'perfect_score', 1, '/badges/perfect-score.png', 'UNCOMMON', 30, 15),
('Early Bird', '早起き鳥', 'Practice before 7 AM', 'SPECIAL', 'early_practice', 1, '/badges/early-bird.png', 'COMMON', 20, 10),
('Night Owl', '夜更かし', 'Practice after 11 PM', 'SPECIAL', 'night_practice', 1, '/badges/night-owl.png', 'COMMON', 20, 10),
('Level 10', 'レベル10', 'Reach level 10', 'MILESTONE', 'level', 10, '/badges/level-10.png', 'UNCOMMON', 100, 50);

-- Default conversation topics
INSERT INTO speaking.conversation_topics (title, title_japanese, description, scenario, category, jlpt_level, difficulty_score, is_roleplay, vocabulary_hints, grammar_points, useful_expressions, order_index, status) VALUES
('Self Introduction', '自己紹介', 'Practice introducing yourself in Japanese', 'You are meeting someone new at a language exchange event.', 'daily_life', 'N5', 1, false,
    '[{"word": "名前", "reading": "なまえ", "meaning": "name"}, {"word": "出身", "reading": "しゅっしん", "meaning": "hometown"}]'::JSONB,
    '["～です", "～と申します"]'::JSONB,
    '["はじめまして", "よろしくお願いします", "～から来ました"]'::JSONB, 1, 'PUBLISHED'),

('At a Restaurant', 'レストランで', 'Order food at a Japanese restaurant', 'You are at a Japanese restaurant and want to order food.', 'daily_life', 'N5', 2, true,
    '[{"word": "注文", "reading": "ちゅうもん", "meaning": "order"}, {"word": "おすすめ", "reading": "おすすめ", "meaning": "recommendation"}]'::JSONB,
    '["～をください", "～はありますか"]'::JSONB,
    '["すみません", "お会計お願いします", "ごちそうさまでした"]'::JSONB, 2, 'PUBLISHED'),

('Shopping', '買い物', 'Practice shopping conversations', 'You are at a clothing store looking for a specific item.', 'daily_life', 'N5', 2, true,
    '[{"word": "いくら", "reading": "いくら", "meaning": "how much"}, {"word": "サイズ", "reading": "サイズ", "meaning": "size"}]'::JSONB,
    '["～がほしい", "～を探しています"]'::JSONB,
    '["これをください", "試着できますか", "クレジットカードは使えますか"]'::JSONB, 3, 'PUBLISHED'),

('Asking Directions', '道を聞く', 'Ask for and understand directions', 'You are lost and need to find the train station.', 'travel', 'N5', 2, true,
    '[{"word": "駅", "reading": "えき", "meaning": "station"}, {"word": "まっすぐ", "reading": "まっすぐ", "meaning": "straight"}]'::JSONB,
    '["～はどこですか", "～まで行きたいです"]'::JSONB,
    '["すみません、道を教えてください", "右に曲がってください", "左に曲がってください"]'::JSONB, 4, 'PUBLISHED'),

('Making Plans', '予定を立てる', 'Make plans with a friend', 'You are inviting a friend to go somewhere together.', 'daily_life', 'N4', 3, false,
    '[{"word": "予定", "reading": "よてい", "meaning": "plan"}, {"word": "都合", "reading": "つごう", "meaning": "convenience"}]'::JSONB,
    '["～ませんか", "～ましょう"]'::JSONB,
    '["今週末、暇ですか", "一緒に行きましょう", "何時に会いましょうか"]'::JSONB, 5, 'PUBLISHED'),

('At the Hotel', 'ホテルで', 'Check in and communicate at a hotel', 'You are checking in at a Japanese hotel.', 'travel', 'N4', 3, true,
    '[{"word": "予約", "reading": "よやく", "meaning": "reservation"}, {"word": "チェックイン", "reading": "チェックイン", "meaning": "check-in"}]'::JSONB,
    '["～の予約をした～です", "～してもいいですか"]'::JSONB,
    '["予約した〇〇です", "部屋は何階ですか", "Wi-Fiのパスワードを教えてください"]'::JSONB, 6, 'PUBLISHED');

DO $$ BEGIN RAISE NOTICE 'PostgreSQL initialization completed successfully! MongoDB has been replaced with JSONB.'; END $$;

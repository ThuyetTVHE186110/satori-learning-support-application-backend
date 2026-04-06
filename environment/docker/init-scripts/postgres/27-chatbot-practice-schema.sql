-- ==========================================================================
-- Migration 26: Curriculum Chatbot + AI Practice Module Schema
-- - ALTER session_type: thêm CURRICULUM_QA
-- - HNSW index trên progress.content_embeddings (song song IVFFlat)
-- - Schema practice: practice_sessions, practice_items, lesson_progress
-- ==========================================================================

-- ── 1. Session type mới cho chatbot hỏi đáp giáo trình ───────────────────
ALTER TYPE session_type ADD VALUE IF NOT EXISTS 'CURRICULUM_QA';

-- ── 2. HNSW index cho chatbot queries ────────────────────────────────────
-- HNSW tốt hơn IVFFlat cho write-heavy workload (chatbot insert/update liên tục)
-- m=16 cân bằng tốt giữa recall và memory; ef_construction=64 đủ cho 1536-dim
-- CONCURRENTLY để không lock table khi chạy migration
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_content_embeddings_hnsw
    ON progress.content_embeddings
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- Composite index hỗ trợ filter theo content_type + metadata (chatbot filter)
CREATE INDEX IF NOT EXISTS idx_content_embeddings_type_meta
    ON progress.content_embeddings (content_type)
    INCLUDE (content_id, text_content);

-- ── 3. Enums cho Practice module ─────────────────────────────────────────
CREATE TYPE practice_session_type AS ENUM (
    'VOCAB_DRILL',      -- Luyện từ vựng
    'GRAMMAR_DRILL',    -- Luyện ngữ pháp
    'MIXED_LESSON',     -- Tổng hợp bài học (vocab + grammar)
    'KANJI_READING'     -- Luyện đọc kanji
);

CREATE TYPE practice_item_type AS ENUM (
    'MULTIPLE_CHOICE',  -- 4 lựa chọn
    'FILL_BLANK',       -- Điền vào chỗ trống
    'TRANSLATION',      -- Dịch câu (VI→JP hoặc JP→VI)
    'SENTENCE_ORDER',   -- Sắp xếp từ thành câu đúng
    'MATCHING'          -- Nối cặp từ/nghĩa
);

CREATE TYPE practice_status AS ENUM (
    'IN_PROGRESS',      -- Đang luyện tập
    'COMPLETED',        -- Đã hoàn thành
    'ABANDONED'         -- Bỏ dở
);

-- ── 4. Schema practice ────────────────────────────────────────────────────
CREATE SCHEMA IF NOT EXISTS practice;

-- ── 5. practice.practice_sessions ────────────────────────────────────────
-- Mỗi phiên luyện tập của một learner cho một bài học cụ thể
CREATE TABLE IF NOT EXISTS practice.practice_sessions (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID NOT NULL REFERENCES user_management.users(id)    ON DELETE CASCADE,
    lesson_id   UUID             REFERENCES learning_content.lessons(id) ON DELETE SET NULL,
    course_id   UUID NOT NULL    REFERENCES learning_content.courses(id) ON DELETE CASCADE,
    session_type practice_session_type NOT NULL,
    status       practice_status DEFAULT 'IN_PROGRESS',

    -- Thống kê kết quả
    total_items     INT     DEFAULT 0,
    completed_items INT     DEFAULT 0,
    correct_items   INT     DEFAULT 0,
    score           DECIMAL(5,2) DEFAULT 0,  -- 0.00 - 100.00

    -- Context JSON:
    -- { "jlptLevel": "N5", "difficulty": 3, "itemTypes": ["MULTIPLE_CHOICE","FILL_BLANK"] }
    context     JSONB DEFAULT '{}'::JSONB,

    -- AI-generated feedback JSON sau khi hoàn thành:
    -- { "summary": "...", "strengths": [...], "weaknesses": [...], "nextLessonSuggestion": "..." }
    ai_feedback JSONB DEFAULT '{}'::JSONB,

    started_at   TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE,
    created_at   TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- ── 6. practice.practice_items ───────────────────────────────────────────
-- Mỗi câu hỏi trong một phiên luyện tập
CREATE TABLE IF NOT EXISTS practice.practice_items (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    session_id  UUID NOT NULL REFERENCES practice.practice_sessions(id) ON DELETE CASCADE,
    item_index  INT  NOT NULL,          -- Thứ tự câu hỏi trong session (0-based)
    item_type   practice_item_type NOT NULL,

    -- Nội dung câu hỏi
    question          TEXT NOT NULL,
    question_japanese TEXT,             -- Phiên bản tiếng Nhật của câu hỏi (nếu có)
    hint              TEXT,             -- Gợi ý (hiển thị khi learner yêu cầu)

    -- Đáp án dạng JSON (cho MULTIPLE_CHOICE / MATCHING):
    -- MULTIPLE_CHOICE: [{"text": "食べる", "isCorrect": false}, ...]
    -- MATCHING:        [{"left": "食べる", "right": "ăn", "isCorrect": false}, ...]
    -- FILL_BLANK/TRANSLATION/SENTENCE_ORDER: []
    options JSONB DEFAULT '[]'::JSONB,

    correct_answer TEXT NOT NULL,       -- Đáp án đúng (string)

    -- Câu trả lời của learner (NULL nếu chưa trả lời)
    user_answer TEXT,
    is_correct  BOOLEAN,
    score       INT DEFAULT 0,          -- 0-100 per item
    ai_feedback TEXT,                   -- Giải thích của AI bằng tiếng Việt

    -- Tham chiếu nguồn (từ vựng hoặc ngữ pháp đã tạo ra câu hỏi này)
    ref_content_id   UUID,              -- vocabulary.id hoặc grammar_points.id
    ref_content_type VARCHAR(50),       -- VOCABULARY | GRAMMAR

    answered_at TIMESTAMP WITH TIME ZONE,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,  -- required by BaseEntity

    UNIQUE (session_id, item_index)
);

-- ── 7. practice.lesson_progress ──────────────────────────────────────────
-- Tiến độ luyện tập tổng hợp của mỗi learner theo bài học
CREATE TABLE IF NOT EXISTS practice.lesson_progress (
    id        UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id   UUID NOT NULL REFERENCES user_management.users(id)    ON DELETE CASCADE,
    lesson_id UUID NOT NULL REFERENCES learning_content.lessons(id) ON DELETE CASCADE,

    total_sessions    INT     DEFAULT 0,
    best_score        DECIMAL(5,2) DEFAULT 0,
    last_score        DECIMAL(5,2),
    -- Tỷ lệ % từ vựng/ngữ pháp đạt score >= 80 trong lần luyện tập gần nhất
    vocab_mastery     DECIMAL(5,2) DEFAULT 0,
    grammar_mastery   DECIMAL(5,2) DEFAULT 0,

    last_practiced_at TIMESTAMP WITH TIME ZONE,
    created_at        TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    UNIQUE (user_id, lesson_id)
);

-- ── 8. Indexes ────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_practice_sessions_user
    ON practice.practice_sessions (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_practice_sessions_lesson
    ON practice.practice_sessions (user_id, lesson_id)
    WHERE status != 'ABANDONED';

CREATE INDEX IF NOT EXISTS idx_practice_sessions_status
    ON practice.practice_sessions (user_id, status);

CREATE INDEX IF NOT EXISTS idx_practice_items_session
    ON practice.practice_items (session_id, item_index);

CREATE INDEX IF NOT EXISTS idx_practice_items_unanswered
    ON practice.practice_items (session_id)
    WHERE user_answer IS NULL;

CREATE INDEX IF NOT EXISTS idx_lesson_progress_user
    ON practice.lesson_progress (user_id, lesson_id);

CREATE INDEX IF NOT EXISTS idx_lesson_progress_lesson
    ON practice.lesson_progress (lesson_id);

-- ── 9. Permissions ────────────────────────────────────────────────────────
GRANT USAGE ON SCHEMA practice TO satori_user;
GRANT ALL PRIVILEGES ON ALL TABLES    IN SCHEMA practice TO satori_user;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA practice TO satori_user;

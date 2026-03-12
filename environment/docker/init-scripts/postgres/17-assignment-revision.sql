-- ==================== Assignment Module Revision ====================
-- File: 17-assignment-revision.sql
-- Adds Writing/Translation types, AI fields, soft-delete, submission fields
-- Run AFTER 01-init.sql

-- ── 1. Enum: thêm WRITING, TRANSLATION ──────────────────────────────────────
ALTER TYPE assignment_type ADD VALUE IF NOT EXISTS 'WRITING';
ALTER TYPE assignment_type ADD VALUE IF NOT EXISTS 'TRANSLATION';

-- ── 2. assignments: thêm columns ────────────────────────────────────────────
ALTER TABLE assignment.assignments
    ADD COLUMN IF NOT EXISTS class_id UUID REFERENCES user_management.classes(id),
    ADD COLUMN IF NOT EXISTS has_submissions BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS deleted_by UUID REFERENCES user_management.users(id),
    ADD COLUMN IF NOT EXISTS updated_by UUID REFERENCES user_management.users(id);

CREATE INDEX IF NOT EXISTS idx_assignments_class_id
    ON assignment.assignments(class_id)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_assignments_teacher_class
    ON assignment.assignments(created_by, class_id)
    WHERE deleted_at IS NULL;

-- ── 3. quiz_questions: thêm columns ─────────────────────────────────────────
ALTER TABLE assignment.quiz_questions
    ADD COLUMN IF NOT EXISTS question_subtype VARCHAR(50),  -- 'LISTENING', 'READING', 'STANDARD'
    ADD COLUMN IF NOT EXISTS ai_generated BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS updated_by UUID REFERENCES user_management.users(id);

COMMENT ON COLUMN assignment.quiz_questions.question_subtype IS 'LISTENING (nghe), READING (đọc), STANDARD (thường)';
COMMENT ON COLUMN assignment.quiz_questions.ai_generated IS 'TRUE nếu câu hỏi được AI tạo từ file/text';

-- ── 4. Bảng mới: writing_assignments ────────────────────────────────────────
-- Chứa nội dung cho bài Viết (WRITING) và Dịch (TRANSLATION)
CREATE TABLE IF NOT EXISTS assignment.writing_assignments (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    assignment_id UUID NOT NULL UNIQUE REFERENCES assignment.assignments(id) ON DELETE CASCADE,

    -- Đề bài
    prompt TEXT NOT NULL,
    prompt_japanese TEXT,

    -- Đáp án mẫu (tham khảo, không hiển thị cho learner trước khi chấm)
    sample_answer TEXT,
    sample_answer_japanese TEXT,

    -- Constraints
    min_words INT,
    max_words INT,

    -- Chỉ dùng cho TRANSLATION: đoạn văn cần dịch
    source_text TEXT,

    -- Chiều dịch: chỉ 2 chiều JA↔VI
    -- 'JA_TO_VI' = dịch tiếng Nhật sang tiếng Việt
    -- 'VI_TO_JA' = dịch tiếng Việt sang tiếng Nhật
    translation_direction VARCHAR(10)
        CHECK (translation_direction IN ('JA_TO_VI', 'VI_TO_JA')),

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE assignment.writing_assignments IS 'Nội dung bổ sung cho bài WRITING và TRANSLATION';

-- ── 5. assignment_submissions: thêm columns ──────────────────────────────────
ALTER TABLE assignment.assignment_submissions
    ADD COLUMN IF NOT EXISTS written_answer TEXT,            -- Bài làm dạng text (Writing/Translation)
    ADD COLUMN IF NOT EXISTS teacher_score DECIMAL(5,2),     -- Điểm Teacher chấm tay
    ADD COLUMN IF NOT EXISTS ai_auto_score DECIMAL(5,2),     -- Điểm Quiz tự động (so sánh đáp án)
    ADD COLUMN IF NOT EXISTS updated_by UUID REFERENCES user_management.users(id);

COMMENT ON COLUMN assignment.assignment_submissions.ai_auto_score IS 'Điểm tự động cho Quiz; NULL nếu là Writing/Translation';
COMMENT ON COLUMN assignment.assignment_submissions.teacher_score IS 'Điểm Teacher chấm; dùng cho Writing/Translation';

-- ============================================================================
-- 17: Add image_urls column to assignment_submissions
-- Purpose: Allow learners to upload photos of handwritten answers
-- ============================================================================

ALTER TABLE assignment.assignment_submissions
    ADD COLUMN IF NOT EXISTS image_urls jsonb DEFAULT NULL;

COMMENT ON COLUMN assignment.assignment_submissions.image_urls
    IS 'JSON array of Cloudinary image URLs submitted by learner (e.g. photos of handwritten work)';

-- ═══════════════════════════════════════════════════════════════════════════════
-- Migration: Add audio_url to assignment.assignments
-- Purpose: Support optional background/listening audio for quiz assignments.
--          A single audio track can cover all questions in a "listening quiz".
-- ═══════════════════════════════════════════════════════════════════════════════

ALTER TABLE assignment.assignments
    ADD COLUMN IF NOT EXISTS audio_url VARCHAR(1024) NULL;

COMMENT ON COLUMN assignment.assignments.audio_url
    IS 'Optional URL of a background/listening audio file for the entire assignment. Used for listening-comprehension quizzes.';

-- ═══════════════════════════════════════════════════════════════════════════════
-- Migration: Remove audio_url from quiz_questions
-- Purpose: Audio is now managed at the assignment level only.
--          Individual questions no longer hold their own audio file.
-- ═══════════════════════════════════════════════════════════════════════════════
ALTER TABLE assignment.quiz_questions
    DROP COLUMN IF EXISTS audio_url;




-- ==========================================================================
-- Migration 27: Fix practice_items schema + add explanation column
-- ==========================================================================

-- ── 1. Thêm updated_at bị thiếu trong migration 26 ───────────────────────
-- BaseEntity yêu cầu updated_at; migration 26 không có cột này ở practice_items.
ALTER TABLE practice.practice_items
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP;

-- ── 2. Pre-generated explanation từ ExerciseGeneratorAgent ───────────────
-- Sinh khi tạo câu hỏi, lưu để hiển thị cho learner ngay sau submit.
-- Khác ai_feedback: ai_feedback là nhận xét của AnswerEvaluatorAgent khi chấm bài.
ALTER TABLE practice.practice_items
    ADD COLUMN IF NOT EXISTS explanation TEXT;

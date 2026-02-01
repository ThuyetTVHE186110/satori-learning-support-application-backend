-- ==================== Schema Fixes ====================
-- Satori Japanese Learning System - Business Logic Alignment
-- Version: 2.0.3
-- Last Updated: 2026-02-01
-- Purpose: Fixes based on business requirements review + Entity hierarchy support

-- ==========================================================================
-- FIX 1: Update user_role ENUM (Remove ADMIN - chỉ có 1 MANAGER quản lý app)
-- ==========================================================================
-- Note: PostgreSQL không cho ALTER ENUM remove value trực tiếp
-- Cần xử lý ở application layer - ADMIN và MANAGER coi như equivalent
-- Hoặc chạy migration phức tạp nếu cần thiết

-- Comment: ADMIN role sẽ được map sang MANAGER ở application layer
-- Vì chỉ có 1 Manager quản lý toàn bộ hệ thống

-- ==========================================================================
-- FIX 2: Add Soft Delete (deleted_at) to critical tables
-- ==========================================================================

-- Classes - add deleted_at for soft delete
ALTER TABLE user_management.classes 
ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE;

-- Assignments - add deleted_at for soft delete  
ALTER TABLE assignment.assignments 
ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE;

-- Courses - add deleted_at for soft delete
ALTER TABLE learning_content.courses 
ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE;

-- Lessons - add deleted_at for soft delete
ALTER TABLE learning_content.lessons 
ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE;

-- Quiz Questions - add deleted_at for soft delete
ALTER TABLE assignment.quiz_questions 
ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE;

-- Vocabulary - add deleted_at for soft delete
ALTER TABLE learning_content.vocabulary 
ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE;

-- Grammar Points - add deleted_at for soft delete
ALTER TABLE learning_content.grammar_points 
ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE;

-- ==========================================================================
-- FIX 2B: Add deleted_by to track WHO deleted the record (Audit requirement)
-- ==========================================================================

-- Users - add deleted_by (track who deleted: self-delete vs admin-delete)
ALTER TABLE user_management.users 
ADD COLUMN IF NOT EXISTS deleted_by UUID REFERENCES user_management.users(id);

COMMENT ON COLUMN user_management.users.deleted_by IS 
'User who performed the deletion. If deleted_by = id, user self-deleted. If different, admin/manager deleted. NULL = system auto-delete.';

-- Classes - add created_by (who created, may differ from teacher) and deleted_by
ALTER TABLE user_management.classes 
ADD COLUMN IF NOT EXISTS created_by UUID REFERENCES user_management.users(id);

ALTER TABLE user_management.classes 
ADD COLUMN IF NOT EXISTS deleted_by UUID REFERENCES user_management.users(id);

-- Courses - add deleted_by
ALTER TABLE learning_content.courses 
ADD COLUMN IF NOT EXISTS deleted_by UUID REFERENCES user_management.users(id);

-- Lessons - add deleted_by
ALTER TABLE learning_content.lessons 
ADD COLUMN IF NOT EXISTS deleted_by UUID REFERENCES user_management.users(id);

-- Vocabulary - add deleted_by
ALTER TABLE learning_content.vocabulary 
ADD COLUMN IF NOT EXISTS deleted_by UUID REFERENCES user_management.users(id);

-- Grammar Points - add deleted_by
ALTER TABLE learning_content.grammar_points 
ADD COLUMN IF NOT EXISTS deleted_by UUID REFERENCES user_management.users(id);

-- Quiz Questions - add deleted_by
ALTER TABLE assignment.quiz_questions 
ADD COLUMN IF NOT EXISTS deleted_by UUID REFERENCES user_management.users(id);

-- Assignments - add deleted_by
ALTER TABLE assignment.assignments 
ADD COLUMN IF NOT EXISTS deleted_by UUID REFERENCES user_management.users(id);

-- Comment for audit requirement
COMMENT ON COLUMN user_management.classes.deleted_by IS 
'User who soft-deleted this class. Required for audit trail.';

COMMENT ON COLUMN learning_content.courses.deleted_by IS 
'User who soft-deleted this course. Required for audit trail.';

COMMENT ON COLUMN assignment.assignments.deleted_by IS 
'User who soft-deleted this assignment. Required for audit trail.';

-- ==========================================================================
-- FIX 3: Add is_late flag to assignment_submissions
-- Yêu cầu: "Nếu thời gian nộp > hạn chót -> gắn cờ 'LATE'"
-- ==========================================================================

ALTER TABLE assignment.assignment_submissions 
ADD COLUMN IF NOT EXISTS is_late BOOLEAN DEFAULT FALSE;

-- Add late penalty applied column
ALTER TABLE assignment.assignment_submissions 
ADD COLUMN IF NOT EXISTS late_penalty_applied DECIMAL(5,2) DEFAULT 0;

-- Add original score before penalty
ALTER TABLE assignment.assignment_submissions 
ADD COLUMN IF NOT EXISTS score_before_penalty DECIMAL(5,2);

-- ==========================================================================
-- FIX 4: Add class_id to assignments (Assignment phải gắn với Class cụ thể)
-- ==========================================================================

ALTER TABLE assignment.assignments 
ADD COLUMN IF NOT EXISTS class_id UUID REFERENCES user_management.classes(id) ON DELETE SET NULL;

-- Create index for class_id
CREATE INDEX IF NOT EXISTS idx_assignments_class ON assignment.assignments(class_id);

-- ==========================================================================
-- FIX 5: Add CHECK constraints for data validation
-- ==========================================================================

-- Coins balance non-negative (Số dư không bao giờ được âm)
ALTER TABLE gamification.user_stats 
ADD CONSTRAINT chk_coins_non_negative CHECK (coins >= 0);

-- EXP non-negative
ALTER TABLE gamification.user_stats 
ADD CONSTRAINT chk_exp_non_negative CHECK (total_exp >= 0);

-- Level minimum 1
ALTER TABLE gamification.user_stats 
ADD CONSTRAINT chk_level_minimum CHECK (current_level >= 1);

-- Streak non-negative
ALTER TABLE gamification.user_stats 
ADD CONSTRAINT chk_streak_non_negative CHECK (current_streak >= 0 AND longest_streak >= 0);

-- Score validation (0-100 or based on max_score)
ALTER TABLE assignment.assignment_submissions 
ADD CONSTRAINT chk_score_non_negative CHECK (score IS NULL OR score >= 0);

-- Progress percent validation (0-100)
ALTER TABLE system_admin.curriculum_imports 
ADD CONSTRAINT chk_progress_percent_range CHECK (progress_percent >= 0 AND progress_percent <= 100);

-- Pronunciation scores validation (0-100)
ALTER TABLE pronunciation.user_topic_progress 
ADD CONSTRAINT chk_completion_percentage CHECK (completion_percentage >= 0 AND completion_percentage <= 100);

ALTER TABLE pronunciation.user_topic_progress 
ADD CONSTRAINT chk_accuracy_score CHECK (best_accuracy_score >= 0 AND best_accuracy_score <= 100);

ALTER TABLE pronunciation.user_topic_progress 
ADD CONSTRAINT chk_fluency_score CHECK (best_fluency_score >= 0 AND best_fluency_score <= 100);

ALTER TABLE pronunciation.user_topic_progress 
ADD CONSTRAINT chk_overall_score CHECK (best_overall_score >= 0 AND best_overall_score <= 100);

-- User item progress scores
ALTER TABLE pronunciation.user_item_progress 
ADD CONSTRAINT chk_item_accuracy CHECK (best_accuracy_score >= 0 AND best_accuracy_score <= 100);

ALTER TABLE pronunciation.user_item_progress 
ADD CONSTRAINT chk_item_fluency CHECK (best_fluency_score >= 0 AND best_fluency_score <= 100);

ALTER TABLE pronunciation.user_item_progress 
ADD CONSTRAINT chk_item_prosody CHECK (best_prosody_score >= 0 AND best_prosody_score <= 100);

ALTER TABLE pronunciation.user_item_progress 
ADD CONSTRAINT chk_item_overall CHECK (best_overall_score >= 0 AND best_overall_score <= 100);

-- Conversation progress scores
ALTER TABLE speaking.user_conversation_progress 
ADD CONSTRAINT chk_conv_fluency CHECK (best_fluency_score >= 0 AND best_fluency_score <= 100);

ALTER TABLE speaking.user_conversation_progress 
ADD CONSTRAINT chk_conv_accuracy CHECK (best_accuracy_score >= 0 AND best_accuracy_score <= 100);

ALTER TABLE speaking.user_conversation_progress 
ADD CONSTRAINT chk_conv_vocabulary CHECK (best_vocabulary_score >= 0 AND best_vocabulary_score <= 100);

ALTER TABLE speaking.user_conversation_progress 
ADD CONSTRAINT chk_conv_overall CHECK (best_overall_score >= 0 AND best_overall_score <= 100);

-- Daily activity minutes non-negative
ALTER TABLE progress.daily_activity 
ADD CONSTRAINT chk_study_minutes CHECK (total_study_minutes >= 0);

ALTER TABLE progress.daily_activity 
ADD CONSTRAINT chk_exp_earned CHECK (exp_earned >= 0);

-- Shop item price validation
ALTER TABLE shop.items 
ADD CONSTRAINT chk_price_coins CHECK (price_coins >= 0);

-- User items quantity positive
ALTER TABLE shop.user_items 
ADD CONSTRAINT chk_quantity_positive CHECK (quantity > 0);

-- Purchase total price positive
ALTER TABLE shop.purchase_history 
ADD CONSTRAINT chk_total_price_positive CHECK (total_price > 0);

-- Assignment retry validation
ALTER TABLE assignment.assignments 
ADD CONSTRAINT chk_max_retries CHECK (max_retries >= 0);

-- Difficulty score validation (1-5)
ALTER TABLE pronunciation.pronunciation_topics 
ADD CONSTRAINT chk_topic_difficulty CHECK (difficulty_score >= 1 AND difficulty_score <= 5);

ALTER TABLE pronunciation.pronunciation_items 
ADD CONSTRAINT chk_item_difficulty CHECK (difficulty_score >= 1 AND difficulty_score <= 5);

ALTER TABLE assignment.quiz_questions 
ADD CONSTRAINT chk_question_difficulty CHECK (difficulty_score >= 1 AND difficulty_score <= 5);

-- ==========================================================================
-- FIX 6: Add missing indexes for search optimization
-- ==========================================================================

-- Course name search index (Yêu cầu: index cho tìm kiếm)
CREATE INDEX IF NOT EXISTS idx_courses_name ON learning_content.courses(name);
CREATE INDEX IF NOT EXISTS idx_courses_name_trgm ON learning_content.courses USING gin(name gin_trgm_ops);

-- Lesson title search index
CREATE INDEX IF NOT EXISTS idx_lessons_title ON learning_content.lessons(title);
CREATE INDEX IF NOT EXISTS idx_lessons_title_trgm ON learning_content.lessons USING gin(title gin_trgm_ops);

-- Assignments class and status index
CREATE INDEX IF NOT EXISTS idx_assignments_status ON assignment.assignments(status);
CREATE INDEX IF NOT EXISTS idx_assignments_due_date ON assignment.assignments(due_date);

-- Soft delete indexes (for filtering active records)
CREATE INDEX IF NOT EXISTS idx_classes_deleted ON user_management.classes(deleted_at) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_assignments_deleted ON assignment.assignments(deleted_at) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_courses_deleted ON learning_content.courses(deleted_at) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_lessons_deleted ON learning_content.lessons(deleted_at) WHERE deleted_at IS NULL;

-- Submission late flag index
CREATE INDEX IF NOT EXISTS idx_submissions_late ON assignment.assignment_submissions(is_late) WHERE is_late = TRUE;

-- ==========================================================================
-- FIX 7: Create trigger function for auto-marking late submissions
-- ==========================================================================

CREATE OR REPLACE FUNCTION assignment.mark_late_submission()
RETURNS TRIGGER AS $$
DECLARE
    v_due_date TIMESTAMP WITH TIME ZONE;
    v_late_penalty INT;
BEGIN
    -- Only check when submission is being submitted
    IF NEW.submitted_at IS NOT NULL AND OLD.submitted_at IS NULL THEN
        -- Get assignment due date and late penalty
        SELECT due_date, late_submission_penalty_percent 
        INTO v_due_date, v_late_penalty
        FROM assignment.assignments 
        WHERE id = NEW.assignment_id;
        
        -- Check if submission is late
        IF NEW.submitted_at > v_due_date THEN
            NEW.is_late := TRUE;
            
            -- Store original score and apply penalty if score is set
            IF NEW.score IS NOT NULL AND v_late_penalty > 0 THEN
                NEW.score_before_penalty := NEW.score;
                NEW.late_penalty_applied := v_late_penalty;
                NEW.score := GREATEST(0, NEW.score - (NEW.score * v_late_penalty / 100.0));
            END IF;
        END IF;
    END IF;
    
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Create trigger for late submission marking
DROP TRIGGER IF EXISTS trg_mark_late_submission ON assignment.assignment_submissions;
CREATE TRIGGER trg_mark_late_submission
    BEFORE UPDATE ON assignment.assignment_submissions
    FOR EACH ROW
    EXECUTE FUNCTION assignment.mark_late_submission();

-- ==========================================================================
-- FIX 8: Create function to validate coin transactions (Balance never negative)
-- ==========================================================================

CREATE OR REPLACE FUNCTION gamification.validate_coin_balance()
RETURNS TRIGGER AS $$
DECLARE
    v_current_balance INT;
BEGIN
    -- For SPEND transactions, verify sufficient balance
    IF NEW.transaction_type = 'SPEND' THEN
        SELECT coins INTO v_current_balance
        FROM gamification.user_stats
        WHERE user_id = NEW.user_id;
        
        IF v_current_balance IS NULL OR v_current_balance < NEW.amount THEN
            RAISE EXCEPTION 'Insufficient coin balance. Current: %, Required: %', 
                COALESCE(v_current_balance, 0), NEW.amount;
        END IF;
    END IF;
    
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Create trigger for coin validation
DROP TRIGGER IF EXISTS trg_validate_coin_balance ON gamification.reward_transactions;
CREATE TRIGGER trg_validate_coin_balance
    BEFORE INSERT ON gamification.reward_transactions
    FOR EACH ROW
    EXECUTE FUNCTION gamification.validate_coin_balance();

-- ==========================================================================
-- FIX 9: Create function to update coin balance after transaction
-- ==========================================================================

CREATE OR REPLACE FUNCTION gamification.update_coin_balance()
RETURNS TRIGGER AS $$
BEGIN
    -- Update user_stats coins based on transaction type
    UPDATE gamification.user_stats
    SET coins = CASE 
        WHEN NEW.transaction_type IN ('EARN', 'BONUS', 'REFUND') 
            THEN coins + NEW.amount
        WHEN NEW.transaction_type = 'SPEND' 
            THEN coins - NEW.amount
        ELSE coins
    END,
    updated_at = CURRENT_TIMESTAMP
    WHERE user_id = NEW.user_id;
    
    -- Update balance_after in the transaction
    UPDATE gamification.reward_transactions
    SET balance_after = (
        SELECT coins FROM gamification.user_stats WHERE user_id = NEW.user_id
    )
    WHERE id = NEW.id;
    
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Create trigger for automatic balance update
DROP TRIGGER IF EXISTS trg_update_coin_balance ON gamification.reward_transactions;
CREATE TRIGGER trg_update_coin_balance
    AFTER INSERT ON gamification.reward_transactions
    FOR EACH ROW
    EXECUTE FUNCTION gamification.update_coin_balance();

-- ==========================================================================
-- FIX 10: Add View for active (non-deleted) records
-- ==========================================================================

-- Active Classes View
CREATE OR REPLACE VIEW user_management.v_active_classes AS
SELECT * FROM user_management.classes 
WHERE deleted_at IS NULL AND is_active = TRUE;

-- Active Assignments View
CREATE OR REPLACE VIEW assignment.v_active_assignments AS
SELECT * FROM assignment.assignments 
WHERE deleted_at IS NULL AND status != 'CLOSED';

-- Active Courses View
CREATE OR REPLACE VIEW learning_content.v_active_courses AS
SELECT * FROM learning_content.courses 
WHERE deleted_at IS NULL AND status = 'PUBLISHED';

-- Active Lessons View
CREATE OR REPLACE VIEW learning_content.v_active_lessons AS
SELECT * FROM learning_content.lessons 
WHERE deleted_at IS NULL AND status = 'PUBLISHED';

-- ==========================================================================
-- FIX 11: Add comments to document business rules
-- ==========================================================================

COMMENT ON COLUMN assignment.assignment_submissions.is_late IS 
'Auto-set to TRUE if submitted_at > assignment.due_date. Business rule: LATE submissions may have penalty applied.';

COMMENT ON COLUMN assignment.assignment_submissions.late_penalty_applied IS 
'Percentage penalty applied to score for late submission. Copied from assignment.late_submission_penalty_percent.';

COMMENT ON COLUMN assignment.assignment_submissions.score_before_penalty IS 
'Original score before late penalty was applied. NULL if not late or no penalty.';

COMMENT ON COLUMN gamification.user_stats.coins IS 
'SNC (Satori Nihongo Coin) balance. Business rule: MUST never be negative (enforced by CHECK constraint and trigger).';

COMMENT ON COLUMN user_management.users.role IS 
'User role. MANAGER has system-wide access (only 1 per center), TEACHER has class-level access, LEARNER has personal access.';

COMMENT ON TABLE system_admin.audit_log IS 
'Audit trail for all sensitive actions (user deletion, score modification, password change). Records: Who, When, What (Old/New values).';

-- ==========================================================================
-- FIX 12: Streak calculation helper function
-- ==========================================================================

CREATE OR REPLACE FUNCTION gamification.calculate_streak(p_user_id UUID, p_activity_date DATE)
RETURNS TABLE(new_streak INT, streak_broken BOOLEAN, freeze_used BOOLEAN) AS $$
DECLARE
    v_last_date DATE;
    v_current_streak INT;
    v_freeze_count INT;
    v_days_diff INT;
BEGIN
    -- Get current streak info
    SELECT streak_last_date, current_streak, streak_freeze_count
    INTO v_last_date, v_current_streak, v_freeze_count
    FROM gamification.user_stats
    WHERE user_id = p_user_id;
    
    -- If no previous activity, start fresh streak
    IF v_last_date IS NULL THEN
        RETURN QUERY SELECT 1, FALSE, FALSE;
        RETURN;
    END IF;
    
    -- Calculate days difference
    v_days_diff := p_activity_date - v_last_date;
    
    -- Same day - no change
    IF v_days_diff = 0 THEN
        RETURN QUERY SELECT v_current_streak, FALSE, FALSE;
    -- Consecutive day - increment streak
    ELSIF v_days_diff = 1 THEN
        RETURN QUERY SELECT v_current_streak + 1, FALSE, FALSE;
    -- Missed 1 day but has freeze
    ELSIF v_days_diff = 2 AND v_freeze_count > 0 THEN
        RETURN QUERY SELECT v_current_streak + 1, FALSE, TRUE;
    -- Streak broken
    ELSE
        RETURN QUERY SELECT 1, TRUE, FALSE;
    END IF;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION gamification.calculate_streak IS 
'Calculate user streak based on last activity date. Business rules:
- Same day = no change
- Consecutive day = +1 streak
- Missed 1 day with freeze item = maintain streak + use 1 freeze
- Missed >1 day (or no freeze) = reset to 1';

-- ==========================================================================
-- GRANT permissions for new objects
-- ==========================================================================

GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA user_management TO satori_user;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA learning_content TO satori_user;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA assignment TO satori_user;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA gamification TO satori_user;

GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA assignment TO satori_user;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA gamification TO satori_user;

-- ==========================================================================
-- FIX 13: Add missing Foreign Key constraints
-- ==========================================================================

-- ai_conversation.conversation_sessions.assignment_id -> assignment.assignments
ALTER TABLE ai_conversation.conversation_sessions
ADD CONSTRAINT fk_conversation_sessions_assignment 
FOREIGN KEY (assignment_id) REFERENCES assignment.assignments(id) ON DELETE SET NULL;

-- system_admin.import_chunks.embedding_id -> progress.content_embeddings
ALTER TABLE system_admin.import_chunks
ADD CONSTRAINT fk_import_chunks_embedding 
FOREIGN KEY (embedding_id) REFERENCES progress.content_embeddings(id) ON DELETE SET NULL;

-- progress.content_embeddings.import_id -> system_admin.curriculum_imports
ALTER TABLE progress.content_embeddings
ADD CONSTRAINT fk_content_embeddings_import 
FOREIGN KEY (import_id) REFERENCES system_admin.curriculum_imports(id) ON DELETE SET NULL;

-- ==========================================================================
-- FIX 14: Fix ON DELETE policy for soft-delete parent tables
-- Soft-deleted parents should not CASCADE delete children
-- ==========================================================================

-- Drop and recreate FK for assignment_submissions (soft-delete parent)
ALTER TABLE assignment.assignment_submissions
DROP CONSTRAINT IF EXISTS assignment_submissions_assignment_id_fkey;

ALTER TABLE assignment.assignment_submissions
ADD CONSTRAINT assignment_submissions_assignment_id_fkey 
FOREIGN KEY (assignment_id) REFERENCES assignment.assignments(id) ON DELETE RESTRICT;

-- Add ON DELETE SET NULL for quiz_questions references (vocabulary, grammar can be soft-deleted)
-- These are already nullable, so SET NULL is appropriate
-- Note: Need to drop existing implicit constraints first if they exist

-- ==========================================================================
-- FIX 15: Add CHECK constraints for business logic validation
-- ==========================================================================

-- Classes: max_students must be positive
ALTER TABLE user_management.classes
ADD CONSTRAINT chk_max_students_positive CHECK (max_students > 0);

-- Classes: end_date must be after start_date (if both provided)
ALTER TABLE user_management.classes
ADD CONSTRAINT chk_class_dates CHECK (end_date IS NULL OR start_date IS NULL OR end_date >= start_date);

-- Assignments: due_date must be after start_date (if both provided)
ALTER TABLE assignment.assignments
ADD CONSTRAINT chk_assignment_dates CHECK (due_date IS NULL OR start_date IS NULL OR due_date >= start_date);

-- Assignments: passing_score must be <= max_score
ALTER TABLE assignment.assignments
ADD CONSTRAINT chk_passing_score CHECK (passing_score <= max_score);

-- Lessons: page_end must be >= page_start (if both provided)
ALTER TABLE learning_content.lessons
ADD CONSTRAINT chk_lesson_pages CHECK (page_end IS NULL OR page_start IS NULL OR page_end >= page_start);

-- ==========================================================================
-- FIX 16: Add unique constraints to prevent duplicate assignments
-- ==========================================================================

-- Prevent assigning same assignment to same class multiple times
CREATE UNIQUE INDEX IF NOT EXISTS idx_assignment_targets_class_unique 
ON assignment.assignment_targets(assignment_id, class_id) 
WHERE class_id IS NOT NULL;

-- Prevent assigning same assignment to same user multiple times
CREATE UNIQUE INDEX IF NOT EXISTS idx_assignment_targets_user_unique 
ON assignment.assignment_targets(assignment_id, user_id) 
WHERE user_id IS NOT NULL;

-- ==========================================================================
-- FIX 17: Create missing ENUM types and migrate VARCHAR columns
-- ==========================================================================

-- Create learning_pace enum
DO $$ BEGIN
    CREATE TYPE learning_pace AS ENUM ('slow', 'normal', 'fast');
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

-- Create formality_level enum
DO $$ BEGIN
    CREATE TYPE formality_level AS ENUM ('casual', 'polite', 'formal');
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

-- Create question_type_enum
DO $$ BEGIN
    CREATE TYPE question_type_enum AS ENUM ('multiple_choice', 'fill_blank', 'true_false', 'matching', 'ordering', 'listening', 'reading');
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

-- Create conversation_category enum
DO $$ BEGIN
    CREATE TYPE conversation_category AS ENUM ('daily_life', 'travel', 'business', 'shopping', 'healthcare', 'education', 'entertainment', 'social');
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

-- Note: Migration of existing VARCHAR columns to ENUM requires careful data migration
-- This should be done in a separate migration script with data transformation

-- ==========================================================================
-- FIX 18: Add meaning constraint (at least one meaning required)
-- ==========================================================================

-- Vocabulary: at least one meaning (vi or en) must be provided
ALTER TABLE learning_content.vocabulary
ADD CONSTRAINT chk_vocabulary_meaning CHECK (meaning_vi IS NOT NULL OR meaning_en IS NOT NULL);

-- Grammar: at least one meaning (vi or en) must be provided  
ALTER TABLE learning_content.grammar_points
ADD CONSTRAINT chk_grammar_meaning CHECK (meaning_vi IS NOT NULL OR meaning_en IS NOT NULL);

-- Pronunciation items: at least one meaning (vi or en) must be provided
ALTER TABLE pronunciation.pronunciation_items
ADD CONSTRAINT chk_pronunciation_meaning CHECK (meaning_vi IS NOT NULL OR meaning_en IS NOT NULL);

-- ==========================================================================
-- FIX 19: Add comments documenting unused ENUM
-- ==========================================================================

COMMENT ON TYPE feedback_status IS 
'UNUSED ENUM - Originally intended for pronunciation feedback status. Consider removing in future cleanup or integrating into pronunciation_attempts table.';

-- ==========================================================================
-- FIX 20: Add updated_by column to support AuditableEntity/SoftDeletableEntity
-- ==========================================================================
-- These columns are required for Spring Data JPA @LastModifiedBy annotation
-- to track who last modified each record

-- Users - add updated_by
ALTER TABLE user_management.users 
ADD COLUMN IF NOT EXISTS updated_by UUID REFERENCES user_management.users(id) ON DELETE SET NULL;

COMMENT ON COLUMN user_management.users.updated_by IS 
'User who last updated this record. Auto-populated by Spring Data JPA Auditing.';

-- Classes - add updated_by
ALTER TABLE user_management.classes 
ADD COLUMN IF NOT EXISTS updated_by UUID REFERENCES user_management.users(id) ON DELETE SET NULL;

COMMENT ON COLUMN user_management.classes.updated_by IS 
'User who last updated this class. Auto-populated by Spring Data JPA Auditing.';

-- Courses - add created_by and updated_by
ALTER TABLE learning_content.courses 
ADD COLUMN IF NOT EXISTS created_by UUID REFERENCES user_management.users(id) ON DELETE SET NULL;

ALTER TABLE learning_content.courses 
ADD COLUMN IF NOT EXISTS updated_by UUID REFERENCES user_management.users(id) ON DELETE SET NULL;

COMMENT ON COLUMN learning_content.courses.created_by IS 
'User who created this course. Auto-populated by Spring Data JPA Auditing.';

COMMENT ON COLUMN learning_content.courses.updated_by IS 
'User who last updated this course. Auto-populated by Spring Data JPA Auditing.';

-- Lessons - add updated_by
ALTER TABLE learning_content.lessons 
ADD COLUMN IF NOT EXISTS updated_by UUID REFERENCES user_management.users(id) ON DELETE SET NULL;

COMMENT ON COLUMN learning_content.lessons.updated_by IS 
'User who last updated this lesson. Auto-populated by Spring Data JPA Auditing.';

-- Vocabulary - add updated_by
ALTER TABLE learning_content.vocabulary 
ADD COLUMN IF NOT EXISTS updated_by UUID REFERENCES user_management.users(id) ON DELETE SET NULL;

COMMENT ON COLUMN learning_content.vocabulary.updated_by IS 
'User who last updated this vocabulary. Auto-populated by Spring Data JPA Auditing.';

-- Grammar Points - add updated_by
ALTER TABLE learning_content.grammar_points 
ADD COLUMN IF NOT EXISTS updated_by UUID REFERENCES user_management.users(id) ON DELETE SET NULL;

COMMENT ON COLUMN learning_content.grammar_points.updated_by IS 
'User who last updated this grammar point. Auto-populated by Spring Data JPA Auditing.';

-- Quiz Questions - add updated_by
ALTER TABLE assignment.quiz_questions 
ADD COLUMN IF NOT EXISTS updated_by UUID REFERENCES user_management.users(id) ON DELETE SET NULL;

COMMENT ON COLUMN assignment.quiz_questions.updated_by IS 
'User who last updated this question. Auto-populated by Spring Data JPA Auditing.';

-- Assignments - add updated_by
ALTER TABLE assignment.assignments 
ADD COLUMN IF NOT EXISTS updated_by UUID REFERENCES user_management.users(id) ON DELETE SET NULL;

COMMENT ON COLUMN assignment.assignments.updated_by IS 
'User who last updated this assignment. Auto-populated by Spring Data JPA Auditing.';

-- Pronunciation Topics - add updated_by (AuditableEntity, not SoftDeletable)
ALTER TABLE pronunciation.pronunciation_topics 
ADD COLUMN IF NOT EXISTS updated_by UUID REFERENCES user_management.users(id) ON DELETE SET NULL;

COMMENT ON COLUMN pronunciation.pronunciation_topics.updated_by IS 
'User who last updated this topic. Auto-populated by Spring Data JPA Auditing.';

-- Pronunciation Items - add updated_by (AuditableEntity, not SoftDeletable)
ALTER TABLE pronunciation.pronunciation_items 
ADD COLUMN IF NOT EXISTS updated_by UUID REFERENCES user_management.users(id) ON DELETE SET NULL;

COMMENT ON COLUMN pronunciation.pronunciation_items.updated_by IS 
'User who last updated this item. Auto-populated by Spring Data JPA Auditing.';

-- ==========================================================================
-- END OF FIXES
-- ==========================================================================

-- Verify fixes applied
DO $$
BEGIN
    RAISE NOTICE '==========================================';
    RAISE NOTICE 'Schema fixes applied successfully!';
    RAISE NOTICE '==========================================';
    RAISE NOTICE 'Summary of changes:';
    RAISE NOTICE '1. Added deleted_at to: classes, assignments, courses, lessons, quiz_questions, vocabulary, grammar_points';
    RAISE NOTICE '2. Added deleted_by to all soft-delete tables for audit trail';
    RAISE NOTICE '3. Added is_late, late_penalty_applied, score_before_penalty to assignment_submissions';
    RAISE NOTICE '4. Added class_id to assignments';
    RAISE NOTICE '5. Added CHECK constraints for: coins>=0, scores 0-100, difficulty 1-5';
    RAISE NOTICE '6. Added indexes for search optimization';
    RAISE NOTICE '7. Created triggers for: auto-mark late submissions, coin balance validation';
    RAISE NOTICE '8. Created views for active records';
    RAISE NOTICE '9. Created streak calculation function';
    RAISE NOTICE '10. Added missing FK constraints: conversation_sessions.assignment_id, import_chunks.embedding_id';
    RAISE NOTICE '11. Fixed ON DELETE policy for soft-delete parent tables';
    RAISE NOTICE '12. Added business logic CHECK constraints: dates, scores, page ranges';
    RAISE NOTICE '13. Added unique constraints to prevent duplicate assignment targets';
    RAISE NOTICE '14. Created missing ENUM types: learning_pace, formality_level, question_type_enum';
    RAISE NOTICE '15. Added meaning constraints: at least one meaning (vi/en) required';
    RAISE NOTICE '16. Added updated_by to: users, classes, courses, lessons, vocabulary, grammar_points, quiz_questions, assignments, pronunciation_topics, pronunciation_items';
    RAISE NOTICE '17. Added created_by to: courses';
    RAISE NOTICE '==========================================';
END $$;

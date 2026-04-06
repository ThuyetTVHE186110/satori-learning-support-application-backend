-- ==========================================================================
-- Migration 44: Add new notification_type enum values
-- CURRICULUM_IMPORT_PENDING_APPROVAL, CURRICULUM_IMPORT_FAILED, PRACTICE_FEEDBACK_READY
-- ==========================================================================

DO $$ BEGIN
    ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'CURRICULUM_IMPORT_PENDING_APPROVAL';
EXCEPTION WHEN others THEN null;
END $$;

DO $$ BEGIN
    ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'CURRICULUM_IMPORT_FAILED';
EXCEPTION WHEN others THEN null;
END $$;

DO $$ BEGIN
    ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'PRACTICE_FEEDBACK_READY';
EXCEPTION WHEN others THEN null;
END $$;

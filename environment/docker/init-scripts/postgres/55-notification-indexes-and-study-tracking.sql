-- Migration 55: Notification indexes + daily study tracking
-- Fix 1: DB indexes for notification hot paths
-- Fix 4: Duration + daily study goal tracking columns

-- ── Fix 1: Notification indexes ──────────────────────────────────────────────

-- Partial index for daily reminder scheduler (hourly full-table scan)
CREATE INDEX IF NOT EXISTS idx_notif_prefs_daily_reminder
    ON notification.user_notification_preferences (daily_reminder_time)
    WHERE push_enabled = TRUE AND daily_reminder_enabled = TRUE;

-- Composite index for dedup window query: findRecentByUserAndType
CREATE INDEX IF NOT EXISTS idx_notifications_user_type_created
    ON notification.notifications (user_id, type, created_at DESC);

-- ── Fix 4: Daily study goal tracking ─────────────────────────────────────────

-- Add duration_minutes to practice_sessions
ALTER TABLE practice.practice_sessions
    ADD COLUMN IF NOT EXISTS duration_minutes INTEGER;

-- Add today-tracking columns to user_stats
ALTER TABLE gamification.user_stats
    ADD COLUMN IF NOT EXISTS studied_minutes_today INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS study_date DATE;

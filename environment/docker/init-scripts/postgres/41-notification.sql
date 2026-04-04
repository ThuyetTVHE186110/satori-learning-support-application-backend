-- ==================== Notification Schema Migration ====================
-- Satori Learning System - Notification Module
-- Version: 1.0.0
-- Created: 2026-04-03
-- Description: Push notifications via Firebase Cloud Messaging

-- ==================== Schema Creation ====================
CREATE SCHEMA IF NOT EXISTS notification;

-- ==================== ENUM Types ====================
DO $$ BEGIN
    CREATE TYPE device_type AS ENUM ('WEB', 'ANDROID', 'IOS');
EXCEPTION
    WHEN duplicate_object THEN null;
END $$;

DO $$ BEGIN
    CREATE TYPE notification_type AS ENUM (
        'DAILY_REMINDER', 'STREAK_ALERT', 'LESSON_COMPLETE', 'ACHIEVEMENT_UNLOCKED', 
        'NEW_CONTENT_AVAILABLE', 'LEARNING_GOAL_REACHED',
        'ASSIGNMENT_CREATED', 'ASSIGNMENT_DUE_SOON', 'ASSIGNMENT_OVERDUE', 
        'ASSIGNMENT_GRADED', 'SUBMISSION_RECEIVED',
        'CLASS_JOINED', 'CLASS_REMOVED', 'CLASS_ANNOUNCEMENT',
        'NEW_MESSAGE', 'ROLEPLAY_FEEDBACK', 'PRONUNCIATION_RESULT',
        'ACCOUNT_SECURITY', 'PASSWORD_CHANGED', 'MAINTENANCE_NOTICE', 'SYSTEM_UPDATE', 'WELCOME'
    );
EXCEPTION
    WHEN duplicate_object THEN null;
END $$;

DO $$ BEGIN
    CREATE TYPE notification_priority AS ENUM ('LOW', 'NORMAL', 'HIGH', 'CRITICAL');
EXCEPTION
    WHEN duplicate_object THEN null;
END $$;

-- ==================== Tables ====================

-- User Device Tokens (FCM tokens for push notifications)
CREATE TABLE IF NOT EXISTS notification.user_device_tokens (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES user_management.users(id) ON DELETE CASCADE,
    fcm_token VARCHAR(500) NOT NULL UNIQUE,
    device_type device_type NOT NULL,
    device_name VARCHAR(100),
    app_version VARCHAR(20),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    last_active_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_device_tokens_user_id ON notification.user_device_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_device_tokens_active ON notification.user_device_tokens(is_active, last_active_at);

-- Notification History
CREATE TABLE IF NOT EXISTS notification.notifications (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES user_management.users(id) ON DELETE CASCADE,
    type notification_type NOT NULL,
    priority notification_priority NOT NULL DEFAULT 'NORMAL',
    title VARCHAR(200) NOT NULL,
    body TEXT,
    data JSONB,
    image_url VARCHAR(500),
    action_url VARCHAR(500),
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    read_at TIMESTAMP WITH TIME ZONE,
    push_sent BOOLEAN NOT NULL DEFAULT FALSE,
    push_sent_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_notifications_user_unread ON notification.notifications(user_id, is_read, created_at DESC);

-- User Notification Preferences
CREATE TABLE IF NOT EXISTS notification.user_notification_preferences (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL UNIQUE REFERENCES user_management.users(id) ON DELETE CASCADE,
    push_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    email_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    daily_reminder_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    daily_reminder_time TIME DEFAULT '08:00:00',
    assignment_notifications BOOLEAN NOT NULL DEFAULT TRUE,
    achievement_notifications BOOLEAN NOT NULL DEFAULT TRUE,
    social_notifications BOOLEAN NOT NULL DEFAULT TRUE,
    marketing_notifications BOOLEAN NOT NULL DEFAULT FALSE,
    quiet_hours_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    quiet_hours_start TIME DEFAULT '22:00:00',
    quiet_hours_end TIME DEFAULT '07:00:00',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_notification_prefs_user ON notification.user_notification_preferences(user_id);

-- ==================== Completion Notice ====================
DO $$ BEGIN 
    RAISE NOTICE 'Notification schema migration completed successfully!'; 
END $$;

-- Migration 59: Simplify notification preferences
-- Drop marketing_notifications (no code triggered NEW_CONTENT_AVAILABLE / SYSTEM_UPDATE).
-- Rename social_notifications → class_notifications (only gates CLASS_* now;
-- roleplay/pronunciation feedback moved to in-app display, no push).

ALTER TABLE notification.user_notification_preferences
    DROP COLUMN IF EXISTS marketing_notifications;

ALTER TABLE notification.user_notification_preferences
    RENAME COLUMN social_notifications TO class_notifications;

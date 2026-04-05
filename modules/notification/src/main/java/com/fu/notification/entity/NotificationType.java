package com.fu.notification.entity;

/**
 * Types of notifications in the system.
 * Grouped by category for easier management.
 */
public enum NotificationType {
    
    // ── Learning Notifications ────────────────────────────────────────────────
    DAILY_REMINDER,
    STREAK_ALERT,
    LESSON_COMPLETE,
    ACHIEVEMENT_UNLOCKED,
    NEW_CONTENT_AVAILABLE,
    LEARNING_GOAL_REACHED,
    
    // ── Assignment Notifications ──────────────────────────────────────────────
    ASSIGNMENT_CREATED,
    ASSIGNMENT_DUE_SOON,
    ASSIGNMENT_OVERDUE,
    ASSIGNMENT_GRADED,
    SUBMISSION_RECEIVED,
    
    // ── Class Notifications ───────────────────────────────────────────────────
    CLASS_JOINED,
    CLASS_REMOVED,
    CLASS_ANNOUNCEMENT,
    
    // ── Social/Conversation Notifications ─────────────────────────────────────
    NEW_MESSAGE,
    ROLEPLAY_FEEDBACK,
    PRONUNCIATION_RESULT,
    
    // ── System Notifications ──────────────────────────────────────────────────
    ACCOUNT_SECURITY,
    PASSWORD_CHANGED,
    MAINTENANCE_NOTICE,
    SYSTEM_UPDATE,
    WELCOME
}

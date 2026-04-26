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

    // ── Curriculum Import Notifications (MANAGER only) ───────────────────────
    CURRICULUM_IMPORT_PENDING_APPROVAL,
    CURRICULUM_IMPORT_FAILED,

    // ── Practice Notifications ────────────────────────────────────────────────
    PRACTICE_FEEDBACK_READY,

    // ── System Notifications ──────────────────────────────────────────────────
    ACCOUNT_SECURITY,
    PASSWORD_CHANGED,
    MAINTENANCE_NOTICE,
    WELCOME
}

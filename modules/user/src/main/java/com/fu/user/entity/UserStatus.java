package com.fu.user.entity;

/**
 * User account status.
 * Maps to PostgreSQL ENUM: user_status
 */
public enum UserStatus {
    PENDING_ACTIVATION,  // Account created but not yet activated
    ACTIVE,              // Normal active account
    INACTIVE,            // Soft-deleted/deactivated account
    SUSPENDED,           // Temporarily suspended by admin
    DELETED              // Marked for permanent deletion
}

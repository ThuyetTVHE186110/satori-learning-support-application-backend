package com.fu.user.entity;

/**
 * Authentication provider for user accounts.
 * Maps to PostgreSQL ENUM: auth_provider
 */
public enum AuthProvider {
    LOCAL,   // Email/password authentication
    GOOGLE   // Google OAuth2
}

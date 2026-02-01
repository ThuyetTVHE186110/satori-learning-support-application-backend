package com.fu.user.entity;

import com.fu.infrastructure.persistence.SoftDeletableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * User entity representing system users.
 * Extends SoftDeletableEntity for audit trail (createdBy, updatedBy, deletedAt, deletedBy).
 */
@Entity
@Table(name = "users", schema = "user_management")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class User extends SoftDeletableEntity {

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    // Profile information
    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Gender gender;

    // Account status
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private UserRole role = UserRole.LEARNER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private UserStatus status = UserStatus.PENDING_ACTIVATION;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_provider", nullable = false)
    @Builder.Default
    private AuthProvider authProvider = AuthProvider.LOCAL;

    @Column(name = "google_id", unique = true)
    private String googleId;

    // Security fields
    @Column(name = "activation_token")
    private String activationToken;

    @Column(name = "activation_token_expires_at")
    private LocalDateTime activationTokenExpiresAt;

    @Column(name = "password_reset_token")
    private String passwordResetToken;

    @Column(name = "password_reset_token_expires_at")
    private LocalDateTime passwordResetTokenExpiresAt;

    @Column(name = "last_password_change_at")
    private LocalDateTime lastPasswordChangeAt;

    @Column(name = "failed_login_attempts")
    @Builder.Default
    private Integer failedLoginAttempts = 0;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    // Metadata
    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "email_verified_at")
    private LocalDateTime emailVerifiedAt;

    /**
     * Get full name (first + last name or display name)
     */
    public String getFullName() {
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        StringBuilder sb = new StringBuilder();
        if (firstName != null) sb.append(firstName);
        if (lastName != null) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(lastName);
        }
        return sb.length() > 0 ? sb.toString() : email;
    }

    /**
     * Check if user can login
     */
    public boolean canLogin() {
        return status == UserStatus.ACTIVE 
            && !isDeleted() 
            && (lockedUntil == null || lockedUntil.isBefore(LocalDateTime.now()));
    }

    /**
     * Check if user has manager/admin privileges
     */
    public boolean isManager() {
        return role == UserRole.MANAGER || role == UserRole.ADMIN;
    }

    /**
     * Check if user is a teacher
     */
    public boolean isTeacher() {
        return role == UserRole.TEACHER;
    }
}

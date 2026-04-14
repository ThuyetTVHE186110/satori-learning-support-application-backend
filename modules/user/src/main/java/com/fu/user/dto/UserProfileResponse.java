package com.fu.user.dto;

import com.fu.user.entity.Gender;
import com.fu.user.entity.UserRole;
import com.fu.user.entity.UserStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Full profile response for the authenticated user.
 * Combines basic account info, gender, auth meta, and learning preferences.
 *
 * Returned by GET /api/v1/profile.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileResponse {

    // ── Identity ────────────────────────────────────────────────────────────
    private UUID id;
    private String email;

    private String firstName;
    private String lastName;
    private String displayName;
    private String fullName;

    private String avatarUrl;
    private String phoneNumber;
    private LocalDate dateOfBirth;
    private Gender gender;

    // ── Account meta ────────────────────────────────────────────────────────
    private UserRole role;
    private UserStatus status;
    private boolean emailVerified;

    // ── Timestamps ──────────────────────────────────────────────────────────
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastLoginAt;

    // ── Learning preferences ────────────────────────────────────────────────
    private LearningPreferencesResponse learningPreferences;

    // ── Enrolled classes (populated when class management is available) ─────
    private List<ClassEnrollmentResponse> enrolledClasses;
}

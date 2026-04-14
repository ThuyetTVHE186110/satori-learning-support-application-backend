package com.fu.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fu.user.entity.Gender;
import com.fu.user.entity.UserRole;
import com.fu.user.entity.UserStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for user data.
 * Used in list view and detail view.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserResponse {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private UUID id;
    private String email;

    // Profile information
    private String firstName;
    private String lastName;
    private String displayName;
    private String fullName;  // Computed: displayName or firstName + lastName
    private String avatarUrl;
    private String phoneNumber;
    private LocalDate dateOfBirth;
    private Gender gender;

    // Account info
    private UserRole role;
    private UserStatus status;
    private boolean emailVerified;

    // Timestamps
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastLoginAt;
}

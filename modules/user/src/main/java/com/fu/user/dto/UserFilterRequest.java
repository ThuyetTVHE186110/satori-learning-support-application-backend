package com.fu.user.dto;

import com.fu.user.entity.UserRole;
import com.fu.user.entity.UserStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for filtering user list (UC-01: View User List).
 * Supports search, filter by role, filter by status.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserFilterRequest {

    /**
     * Search keyword - searches in fullName, email
     */
    private String keyword;

    /**
     * Filter by role: LEARNER, TEACHER
     */
    private UserRole role;

    /**
     * Filter by status: ACTIVE, INACTIVE
     */
    private UserStatus status;

    /**
     * Include soft-deleted users (default: false)
     */
    @Builder.Default
    private Boolean includeDeleted = false;
}

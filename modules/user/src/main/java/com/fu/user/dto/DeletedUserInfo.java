package com.fu.user.dto;

import com.fu.user.entity.UserRole;
import com.fu.user.entity.UserStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for deleted user information.
 * Used when Manager tries to create user with existing deleted email.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeletedUserInfo {

    private UUID userId;
    private String email;
    private String firstName;
    private String lastName;
    private String displayName;
    private UserRole role;
    private UserStatus status;
    private LocalDateTime deletedAt;
    private UUID deletedBy;
    
    /**
     * Message to display to user
     */
    private String message;
}

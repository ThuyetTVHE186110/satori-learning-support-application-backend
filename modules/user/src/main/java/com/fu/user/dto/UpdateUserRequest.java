package com.fu.user.dto;

import com.fu.common.validator.NoEmoji;
import com.fu.common.validator.SafeText;
import com.fu.user.entity.UserRole;
import com.fu.user.entity.UserStatus;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Request DTO for updating user information (UC-04: Update User).
 * All fields are optional - only provided fields will be updated.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateUserRequest {

    @Size(max = 100, message = "First name must not exceed 100 characters")
    @NoEmoji(message = "First name must not contain emoji characters")
    @SafeText(message = "First name contains invalid characters")
    private String firstName;

    @Size(max = 100, message = "Last name must not exceed 100 characters")
    @NoEmoji(message = "Last name must not contain emoji characters")
    @SafeText(message = "Last name contains invalid characters")
    private String lastName;

    @Size(max = 100, message = "Display name must not exceed 100 characters")
    @NoEmoji(message = "Display name must not contain emoji characters")
    @SafeText(message = "Display name contains invalid characters")
    private String displayName;

    @Pattern(regexp = "^[+]?[0-9]{10,15}$", message = "Invalid phone number format")
    private String phoneNumber;

    @Past(message = "Date of birth must be in the past")
    private LocalDate dateOfBirth;

    private String avatarUrl;

    /**
     * Role can only be LEARNER or TEACHER (Manager cannot be changed via this API)
     */
    private UserRole role;

    /**
     * Status: ACTIVE or INACTIVE
     */
    private UserStatus status;
}

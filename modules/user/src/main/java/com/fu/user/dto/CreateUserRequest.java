package com.fu.user.dto;

import com.fu.common.validator.NoEmoji;
import com.fu.common.validator.SafeText;
import com.fu.user.entity.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Request DTO for creating a new user (UC-02: Create User).
 * Used by Manager/Admin to create Learner/Teacher/Manager accounts.
 * Frontend sends firstName and lastName separately.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateUserRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    @Size(max = 255, message = "Email must not exceed 255 characters")
    private String email;

    @NotBlank(message = "First name is required")
    @Size(max = 100, message = "First name must not exceed 100 characters")
    @NoEmoji(message = "First name must not contain emoji characters")
    @SafeText(message = "First name contains invalid characters")
    private String firstName;

    @NotBlank(message = "Last name is required")
    @Size(max = 100, message = "Last name must not exceed 100 characters")
    @NoEmoji(message = "Last name must not contain emoji characters")
    @SafeText(message = "Last name contains invalid characters")
    private String lastName;

    @NotNull(message = "Role is required")
    private UserRole role;

    // Optional fields
    @Pattern(regexp = "^[+]?[0-9]{10,15}$", message = "Invalid phone number format")
    private String phoneNumber;

    @Past(message = "Date of birth must be in the past")
    private LocalDate dateOfBirth;
}

package com.fu.user.dto;

import com.fu.common.validator.NoEmoji;
import com.fu.common.validator.SafeText;
import com.fu.user.entity.UserRole;
import jakarta.validation.constraints.*;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.UUID;

@Builder
@Data
public class ImportUpdateUserRequest {
    @NotBlank
    private UUID userId;

    @NotBlank(message = "Full name is required")
    @Size(max = 100, message = "Full name must not exceed 100 characters")
    @NoEmoji(message = "Full name must not contain emoji characters")
    @SafeText(message = "Full name contains invalid characters")
    private String fullName;

    @Pattern(regexp = "^[+]?[0-9]{10,15}$", message = "Invalid phone number format")
    private String phoneNumber;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    @Size(max = 255, message = "Email must not exceed 255 characters")
    private String email;

    @Past(message = "Date of birth must be in the past")
    private LocalDate dateOfBirth;
}

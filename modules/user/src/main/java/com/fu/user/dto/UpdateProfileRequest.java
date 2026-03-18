package com.fu.user.dto;

import com.fu.user.entity.Gender;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * DTO for user profile update request.
 * Users can only update their own non-sensitive information.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateProfileRequest {

    @Size(max = 100, message = "Họ không được quá 100 ký tự")
    private String firstName;

    @Size(max = 100, message = "Tên không được quá 100 ký tự")
    private String lastName;

    @Size(max = 100, message = "Tên hiển thị không được quá 100 ký tự")
    private String displayName;

    @Pattern(regexp = "^(\\+84|0)[0-9]{9,10}$", message = "Số điện thoại không hợp lệ")
    private String phoneNumber;

    @Past(message = "Ngày sinh phải là ngày trong quá khứ")
    private LocalDate dateOfBirth;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    @Size(max = 255, message = "Email must not exceed 255 characters")
    private String email;

    private Gender gender;

    private String avatarUrl;
}

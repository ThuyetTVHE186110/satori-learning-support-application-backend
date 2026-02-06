package com.fu.user.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for resetting password with reset token.
 * UC-07: Forgot Password - Step 3: Reset Password with Token
 * 
 * Flow:
 * 1. User requests OTP via /forgot-password
 * 2. User verifies OTP via /verify-otp → receives resetToken
 * 3. User resets password via /reset-password with resetToken
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResetPasswordRequest {

    @NotBlank(message = "Token đặt lại mật khẩu không hợp lệ")
    private String resetToken;

    @NotBlank(message = "Hãy nhập mật khẩu mới")
    @Size(min = 8, message = "Mật khẩu phải có ít nhất 8 ký tự")
    @Pattern(
        regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$",
        message = "Mật khẩu phải bao gồm chữ hoa, chữ thường và số"
    )
    private String newPassword;

    @NotBlank(message = "Hãy xác nhận mật khẩu mới")
    private String confirmPassword;
}

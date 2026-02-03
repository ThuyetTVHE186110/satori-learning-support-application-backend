package com.fu.user.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for verifying OTP and resetting password.
 * UC-07: Forgot Password - Step 2: Verify OTP and Reset
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResetPasswordRequest {

    @NotBlank(message = "Hãy nhập email")
    @Email(message = "Hãy nhập đúng email")
    private String email;

    @NotBlank(message = "Hãy nhập mã OTP")
    @Size(min = 6, max = 6, message = "Mã OTP phải có 6 ký tự")
    private String otp;

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

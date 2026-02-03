package com.fu.user.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for changing password.
 * Authenticated users can change their own password.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChangePasswordRequest {

    @NotBlank(message = "Hãy nhập mật khẩu hiện tại")
    private String currentPassword;

    @NotBlank(message = "Hãy nhập mật khẩu mới")
    @Size(min = 8, message = "Mật khẩu phải có ít nhất 8 ký tự")
    @Pattern(
        regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$",
        message = "Mật khẩu phải bao gồm chữ hoa, chữ thường và số"
    )
    private String newPassword;

    @NotBlank(message = "Hãy xác nhận mật khẩu mới")
    private String confirmPassword;

    /**
     * Whether to logout from other devices after password change.
     * Default: true (force re-login on other devices)
     */
    @Builder.Default
    private boolean logoutOtherDevices = true;
}

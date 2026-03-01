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
 * Request DTO for email/password login.
 * UC-06: Login by Email
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginRequest {

    @NotBlank(message = "Hãy nhập email")
    @Email(message = "Hãy nhập đúng email")
    private String email;

    @NotBlank(message = "Hãy nhập mật khẩu")
    @Size(min = 8, message = "Mật khẩu phải có ít nhất 8 ký tự")
    @Pattern(
            regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[\\W_]).{8,}$",
            message = "Mật khẩu phải bao gồm chữ hoa, chữ thường, số và ký tự đặc biệt"
    )
    private String password;

    /**
     * Optional device information for session tracking.
     */
    private DeviceInfo deviceInfo;
}

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

//    @NotBlank(message = "Hãy nhập email")
//    @Email(message = "Hãy nhập đúng email")
    private String email;

    @NotBlank(message = "Hãy nhập mật khẩu")
    private String password;

    /**
     * Optional device information for session tracking.
     */
    private DeviceInfo deviceInfo;
}

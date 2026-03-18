package com.fu.user.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for verifying OTP.
 * UC-07: Forgot Password - Step 2: Verify OTP
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerifyOtpRequest {

    @NotBlank(message = "Hãy nhập email")
    @Email(message = "Hãy nhập đúng email")
    private String email;

    @NotBlank(message = "Hãy nhập mã OTP")
    @Size(min = 6, max = 6, message = "Mã OTP phải có 6 ký tự")
    private String otp;
}

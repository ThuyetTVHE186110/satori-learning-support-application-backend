package com.fu.user.controller;

import com.fu.common.response.ApiResponse;
import com.fu.user.dto.auth.*;
import com.fu.user.security.AuthRateLimiter;
import com.fu.user.security.CustomUserDetails;
import com.fu.user.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST Controller for Authentication operations.
 * Implements UC-06 (Login), UC-07 (Forgot Password), UC-10 (Logout).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuthRateLimiter authRateLimiter;

    // ==================== UC-06: Login by Email ====================

    /**
     * Login with email and password.
     * 
     * POST /api/v1/auth/login
     * 
     * @param request Login credentials
     * @param httpRequest HTTP request for IP and user agent
     * @return JWT tokens and user info
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        
        String ipAddress = getClientIpAddress(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        authRateLimiter.checkLogin(ipAddress);
        LoginResponse response = authService.login(request, ipAddress, userAgent);
        
        return ResponseEntity.ok(ApiResponse.success("Đăng nhập thành công", response));
    }

    // ==================== UC-07: Forgot Password ====================

    /**
     * Request password reset OTP.
     * 
     * POST /api/v1/auth/forgot-password
     * 
     * @param request Email address
     * @return Success message (always, for security)
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request,
            HttpServletRequest httpRequest) {

        authRateLimiter.checkForgotPassword(getClientIpAddress(httpRequest));
        authService.forgotPassword(request);
        
        return ResponseEntity.ok(ApiResponse.success(
                "Nếu email tồn tại trong hệ thống, bạn sẽ nhận được mã OTP", null));
    }

    /**
     * Verify OTP and get reset token.
     * 
     * POST /api/v1/auth/verify-otp
     * 
     * @param request Email and OTP
     * @return Reset token for password reset
     */
    @PostMapping("/verify-otp")
    public ResponseEntity<ApiResponse<VerifyOtpResponse>> verifyOtp(
            @Valid @RequestBody VerifyOtpRequest request,
            HttpServletRequest httpRequest) {

        authRateLimiter.checkVerifyOtp(getClientIpAddress(httpRequest));
        VerifyOtpResponse response = authService.verifyOtp(request);
        
        return ResponseEntity.ok(ApiResponse.success("Xác thực OTP thành công", response));
    }

    /**
     * Reset password with reset token.
     * 
     * POST /api/v1/auth/reset-password
     * 
     * @param request Reset token and new password
     * @return Success message
     */
    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) {
        
        authService.resetPassword(request);
        
        return ResponseEntity.ok(ApiResponse.success("Đặt lại mật khẩu thành công", null));
    }

    // ==================== Token Refresh ====================

    /**
     * Refresh access token.
     * 
     * POST /api/v1/auth/refresh
     * 
     * @param request Refresh token
     * @return New access token
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<LoginResponse>> refreshToken(
            @Valid @RequestBody RefreshTokenRequest request) {
        
        LoginResponse response = authService.refreshToken(request);
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ==================== UC-10: Logout ====================

    /**
     * Logout current session or all sessions.
     * 
     * POST /api/v1/auth/logout
     * 
     * @param request Logout options
     * @param userDetails Current authenticated user
     * @return Success message
     */
    @PostMapping("/logout")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Void>> logout(
            @RequestBody(required = false) LogoutRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        
        if (request == null) {
            request = new LogoutRequest();
        }
        
        authService.logout(userDetails.getId(), request);
        
        return ResponseEntity.ok(ApiResponse.success("Đăng xuất thành công", null));
    }

    // ==================== Password Change ====================

    /**
     * Change password for authenticated user.
     * 
     * POST /api/v1/auth/change-password
     * 
     * @param request Current and new password
     * @param userDetails Current authenticated user
     * @return Success message
     */
    @PostMapping("/change-password")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        
        authService.changePassword(userDetails.getId(), request, userDetails.getSessionId());
        
        return ResponseEntity.ok(ApiResponse.success("Đổi mật khẩu thành công", null));
    }

    // ==================== Session Management ====================

    /**
     * Get all active sessions for current user.
     * 
     * GET /api/v1/auth/sessions
     * 
     * @param userDetails Current authenticated user
     * @return List of active sessions
     */
    @GetMapping("/sessions")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<SessionResponse>>> getActiveSessions(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        
        List<SessionResponse> sessions = authService.getActiveSessions(
                userDetails.getId(), userDetails.getSessionId());
        
        return ResponseEntity.ok(ApiResponse.success(sessions));
    }

    /**
     * Revoke a specific session.
     * 
     * DELETE /api/v1/auth/sessions/{sessionId}
     * 
     * @param sessionId Session ID to revoke
     * @param userDetails Current authenticated user
     * @return Success message
     */
    @DeleteMapping("/sessions/{sessionId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Void>> revokeSession(
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        
        authService.revokeSession(userDetails.getId(), sessionId);
        
        return ResponseEntity.ok(ApiResponse.success("Đã thu hồi phiên đăng nhập", null));
    }

    /**
     * Get current authenticated user info.
     * 
     * GET /api/v1/auth/me
     * 
     * @param userDetails Current authenticated user
     * @return User info
     */
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<LoginResponse.UserInfo>> getCurrentUser(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        
        LoginResponse.UserInfo userInfo = LoginResponse.UserInfo.builder()
                .id(userDetails.getId())
                .email(userDetails.getEmail())
                .fullName(userDetails.getFullName())
                .role(com.fu.user.entity.UserRole.valueOf(userDetails.getRole()))
                .build();
        
        return ResponseEntity.ok(ApiResponse.success(userInfo));
    }

    // ==================== Helper Methods ====================

    /**
     * Extract client IP address from request.
     * Handles proxy headers.
     */
    private String getClientIpAddress(HttpServletRequest request) {
        String[] headerNames = {
                "X-Forwarded-For",
                "X-Real-IP",
                "Proxy-Client-IP",
                "WL-Proxy-Client-IP",
                "HTTP_X_FORWARDED_FOR",
                "HTTP_X_FORWARDED",
                "HTTP_X_CLUSTER_CLIENT_IP",
                "HTTP_CLIENT_IP",
                "HTTP_FORWARDED_FOR",
                "HTTP_FORWARDED"
        };

        for (String header : headerNames) {
            String ip = request.getHeader(header);
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                // X-Forwarded-For can contain multiple IPs, take the first one
                return ip.split(",")[0].trim();
            }
        }

        return request.getRemoteAddr();
    }
}

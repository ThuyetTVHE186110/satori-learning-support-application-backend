package com.fu.user.service;

import com.fu.user.dto.auth.*;

import java.util.List;
import java.util.UUID;

/**
 * Service interface for Authentication operations.
 * Implements UC-06 (Login), UC-07 (Forgot Password), UC-09 (Google Login), UC-10 (Logout).
 */
public interface AuthService {

    // ==================== UC-06: Login by Email ====================
    
    /**
     * Authenticate user with email and password.
     * 
     * Main flow:
     * 1. Validate email format
     * 2. Find user by email
     * 3. Verify password with BCrypt
     * 4. Check account status (must be ACTIVE)
     * 5. Create session and generate tokens
     * 
     * Alternative flows:
     * - A6.1: Missing fields → throws BusinessException
     * - A6.2: Invalid email format → throws BusinessException
     * - A6.3: Wrong credentials → throws BusinessException
     * 
     * Exceptions:
     * - E6.4: Account suspended/locked → throws BusinessException
     * 
     * @param request Login credentials and device info
     * @param ipAddress Client IP address
     * @param userAgent User agent string
     * @return JWT tokens and user info
     */
    LoginResponse login(LoginRequest request, String ipAddress, String userAgent);

    // ==================== UC-09: Login with Google ====================
    
    /**
     * Authenticate user with Google OAuth ID Token.
     * 
     * Logic:
     * 1. Verify Google ID Token with Firebase
     * 2. Extract email and Google ID from token
     * 3. Find user by email or Google ID
     * 4. If user not found → access denied
     * 5. Check account status (must be ACTIVE)
     * 6. Create session and generate tokens
     * 
     * @param request Google ID token and device info
     * @param ipAddress Client IP address
     * @param userAgent User agent string
     * @return JWT tokens and user info
     */
    LoginResponse loginWithGoogle(GoogleLoginRequest request, String ipAddress, String userAgent);

    // ==================== UC-07: Forgot Password ====================
    
    /**
     * Request password reset OTP.
     * Generates a 6-digit OTP with expiration and sends via email.
     * 
     * @param request Email address
     */
    void forgotPassword(ForgotPasswordRequest request);

    /**
     * Verify OTP and reset password.
     * 
     * @param request Email, OTP, and new password
     */
    void resetPassword(ResetPasswordRequest request);

    // ==================== Password Change ====================
    
    /**
     * Change password for authenticated user.
     * 
     * Business rules:
     * - Must verify current password
     * - New password must meet complexity requirements
     * - Cannot reuse last 3 passwords
     * - Force re-login on other devices (optional)
     * 
     * @param userId User ID
     * @param request Current and new password
     * @param currentSessionId Current session to preserve
     */
    void changePassword(UUID userId, ChangePasswordRequest request, UUID currentSessionId);

    // ==================== Token Management ====================
    
    /**
     * Refresh access token using refresh token.
     * 
     * @param request Refresh token
     * @return New access token (and optionally new refresh token)
     */
    LoginResponse refreshToken(RefreshTokenRequest request);

    // ==================== UC-10: Logout ====================
    
    /**
     * Logout user (revoke session).
     * 
     * Logic:
     * - Single logout: Revoke current session
     * - Logout all: Revoke all sessions for user
     * 
     * @param userId User ID
     * @param request Logout options
     */
    void logout(UUID userId, LogoutRequest request);

    /**
     * Logout from all devices.
     * 
     * @param userId User ID
     */
    void logoutAll(UUID userId);

    // ==================== Session Management ====================
    
    /**
     * Get all active sessions for a user.
     * 
     * @param userId User ID
     * @param currentSessionId Current session ID to mark
     * @return List of active sessions
     */
    List<SessionResponse> getActiveSessions(UUID userId, UUID currentSessionId);

    /**
     * Revoke a specific session.
     * 
     * @param userId User ID (for authorization)
     * @param sessionId Session ID to revoke
     */
    void revokeSession(UUID userId, UUID sessionId);
}

package com.fu.user.service.impl;

import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.infrastructure.email.EmailService;
import com.fu.user.dto.auth.*;
import com.fu.user.entity.*;
import com.fu.user.repository.UserRepository;
import com.fu.user.repository.UserSessionRepository;
import com.fu.user.security.JwtService;
import com.fu.user.service.AuthService;
import com.fu.user.service.LoginAttemptService;
import com.fu.user.util.DeviceDetector;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Implementation of AuthService.
 * Handles all authentication use cases (UC-06, UC-07, UC-09, UC-10).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final UserSessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final EmailService emailService;
    // Firebase Auth is optional - may be null if not configured
    private final Optional<FirebaseAuth> firebaseAuth;
    private final LoginAttemptService loginAttemptService;

    @Value("${app.security.otp-expiration-minutes:1}")
    private int otpExpirationMinutes;
    private static final String EMAIL_REGEX = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$";
    private static final String OTP_CHARACTERS = "0123456789";
    private static final int OTP_LENGTH = 6;
    private static final int RESET_TOKEN_EXPIRATION_MINUTES = 5;

    // ==================== UC-06: Login by Email ====================

    @Override
    @Transactional
    public LoginResponse login(LoginRequest request, String ipAddress, String userAgent) {
        log.info("Login attempt for email: {}", request.getEmail());
        // Validate email format
        if (request.getEmail() == null || request.getEmail().isBlank() || !Pattern.matches(EMAIL_REGEX, request.getEmail().trim())) {
            throw new BusinessException("INVALID_INCREDENTIALS",
                    "Sai email hoặc mật khẩu. Hãy thử lại");
        }

        // Find user by email
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> {
                    log.warn("Login failed - email not found: {}", request.getEmail());
                    return new BusinessException("INVALID_CREDENTIALS",
                            "Sai email hoặc mật khẩu. Hãy thử lại");
                });

        // Check if account is soft-deleted
        if (user.isDeleted()) {
            log.warn("Login failed - account deleted: {}", request.getEmail());
            throw new BusinessException("ACCOUNT_DELETED",
                    "Tài khoản không tồn tại");
        }

        // E6.4: Check if account is suspended
        if (user.getStatus() == UserStatus.SUSPENDED) {
            log.warn("Login failed - account suspended: {}", request.getEmail());
            throw new BusinessException("ACCOUNT_SUSPENDED",
                    "Tài khoản của bạn đã bị chặn. Hãy liên hệ quản lý");
        }

        // Check if account is pending activation
        if (user.getStatus() == UserStatus.PENDING_ACTIVATION) {
            log.warn("Login failed - account pending activation: {}", request.getEmail());
            throw new BusinessException("ACCOUNT_NOT_ACTIVATED",
                    "Tài khoản chưa được kích hoạt. Vui lòng kiểm tra email");
        }

        // Check if account is locked
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now())) {
            log.warn("Login failed - account locked until: {}", user.getLockedUntil());
            throw new BusinessException("ACCOUNT_LOCKED",
                    "Tài khoản đã bị khóa tạm thời. Vui lòng thử lại sau");
        }

        // A6.3: Verify password
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            loginAttemptService.handleFailedLogin(user);

            if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now())) {
                log.warn("Login failed - account just locked due to max attempts: {}", request.getEmail());
                throw new BusinessException("ACCOUNT_LOCKED",
                        "Tài khoản đã bị khóa tạm thời. Vui lòng thử lại sau");
            } else {
                log.warn("Login failed - wrong password for: {}", request.getEmail());
                throw new BusinessException("INVALID_CREDENTIALS",
                        "Sai email hoặc mật khẩu. Hãy thử lại");
            }
        }

        // Reset failed login attempts on successful login
        if (user.getFailedLoginAttempts() > 0) {
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(null);
        }

        // Update last login time
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);

        // Create session and generate tokens
        return createSessionAndGenerateTokens(user, request.getDeviceInfo(), ipAddress, userAgent);
    }

    // ==================== UC-09: Login with Google ====================

    @Override
    @Transactional
    public LoginResponse loginWithGoogle(GoogleLoginRequest request, String ipAddress, String userAgent) {
        log.info("Google login attempt");

        // Verify Google ID token
        FirebaseToken decodedToken = verifyGoogleIdToken(request.getIdToken());
        String email = decodedToken.getEmail();
        String googleId = decodedToken.getUid();

        log.info("Google login - verified email: {}", email);

        // Find user by Google ID or email
        User user = userRepository.findByGoogleId(googleId)
                .or(() -> userRepository.findByEmail(email))
                .orElseThrow(() -> {
                    log.warn("Google login failed - user not found: {}", email);
                    return new BusinessException("USER_NOT_FOUND",
                            "Email chưa được đăng ký trong hệ thống. Vui lòng liên hệ quản lý");
                });

        // Check account status
        validateAccountStatus(user);

        // Link Google ID if not already linked
        if (user.getGoogleId() == null) {
            user.setGoogleId(googleId);
            user.setAuthProvider(AuthProvider.GOOGLE);
        }

        // Update last login time
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);

        return createSessionAndGenerateTokens(user, request.getDeviceInfo(), ipAddress, userAgent);
    }

    // ==================== UC-07: Forgot Password ====================

    @Override
    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        log.info("Forgot password request for email: {}", request.getEmail());

        User user = userRepository.findActiveByEmail(request.getEmail())
                .orElse(null);

        // Don't reveal if email exists or not (security best practice)
        if (user == null) {
            log.info("Forgot password - email not found (silent): {}", request.getEmail());
            return;
        }

        // Check if user can reset password (not Google-only account)
        if (user.getAuthProvider() == AuthProvider.GOOGLE && user.getPasswordHash() == null) {
            log.info("Forgot password - Google account: {}", request.getEmail());
            // Still don't reveal this to the user
            return;
        }

        // Generate OTP
        String otp = generateOTP();
        user.setPasswordResetToken(otp);
        user.setPasswordResetTokenExpiresAt(LocalDateTime.now().plusMinutes(otpExpirationMinutes));
        userRepository.save(user);

        // Send OTP via email
        emailService.sendPasswordResetOtp(
                user.getEmail(),
                user.getFullName(),
                otp,
                otpExpirationMinutes);
        log.info("OTP sent to user: {}", user.getEmail());
    }

    @Override
    @Transactional
    public VerifyOtpResponse verifyOtp(VerifyOtpRequest request) {
        log.info("Verify OTP for email: {}", request.getEmail());

        User user = userRepository.findActiveByEmail(request.getEmail())
                .orElseThrow(() -> new BusinessException("INVALID_OTP",
                        "Mã OTP không hợp lệ"));

        // Verify OTP
        if (user.getPasswordResetToken() == null ||
                !user.getPasswordResetToken().equals(request.getOtp())) {
            throw new BusinessException("INVALID_OTP", "Mã OTP không hợp lệ");
        }

        // Check OTP expiration
        if (user.getPasswordResetTokenExpiresAt() == null ||
                user.getPasswordResetTokenExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException("OTP_EXPIRED", "Mã OTP đã hết hạn");
        }

        // Generate reset token (UUID-based for security)
        String resetToken = UUID.randomUUID().toString();
        user.setPasswordResetToken(resetToken);
        user.setPasswordResetTokenExpiresAt(LocalDateTime.now().plusMinutes(RESET_TOKEN_EXPIRATION_MINUTES));
        userRepository.save(user);

        log.info("OTP verified successfully for: {}", user.getEmail());

        return VerifyOtpResponse.builder()
                .resetToken(resetToken)
                .expiresInSeconds(RESET_TOKEN_EXPIRATION_MINUTES * 60)
                .email(user.getEmail())
                .build();
    }

    @Override
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        log.info("Reset password attempt with reset token");

        // Validate password confirmation
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new BusinessException("PASSWORD_MISMATCH",
                    "Mật khẩu xác nhận không khớp");
        }

        // Find user by reset token
        User user = userRepository.findByPasswordResetToken(request.getResetToken())
                .orElseThrow(() -> new BusinessException("INVALID_TOKEN",
                        "Token đặt lại mật khẩu không hợp lệ hoặc đã hết hạn"));

        // Check reset token expiration
        if (user.getPasswordResetTokenExpiresAt() == null ||
                user.getPasswordResetTokenExpiresAt().isBefore(LocalDateTime.now())) {
            // Clear expired token
            user.setPasswordResetToken(null);
            user.setPasswordResetTokenExpiresAt(null);
            userRepository.save(user);
            throw new BusinessException("TOKEN_EXPIRED", "Token đặt lại mật khẩu đã hết hạn. Vui lòng thử lại");
        }

        // Update password
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setPasswordResetToken(null);
        user.setPasswordResetTokenExpiresAt(null);
        user.setLastPasswordChangeAt(LocalDateTime.now());
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        // Revoke all sessions (force re-login)
        sessionRepository.revokeAllSessionsByUserId(user.getId(), LocalDateTime.now());

        // Send password changed notification email
        emailService.sendPasswordChangedNotification(user.getEmail(), user.getFullName());

        log.info("Password reset successful for: {}", user.getEmail());
    }

    // ==================== Password Change ====================

    @Override
    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request, UUID currentSessionId) {
        log.info("Change password for user: {}", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        // Validate password confirmation
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new BusinessException("PASSWORD_MISMATCH",
                    "Mật khẩu xác nhận không khớp");
        }

        // Verify current password
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new BusinessException("WRONG_PASSWORD",
                    "Mật khẩu hiện tại không chính xác");
        }

        // Prevent reusing the current password
        if (passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw new BusinessException("PASSWORD_REUSE",
                    "Mật khẩu mới không được trùng với mật khẩu cũ.");
        }

        // Update password
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setLastPasswordChangeAt(LocalDateTime.now());
        userRepository.save(user);

        // BR-63: Revoke ALL sessions (including current) when logoutOtherDevices = true
        if (request.isLogoutOtherDevices()) {
            sessionRepository.revokeAllSessionsByUserId(userId, LocalDateTime.now());
        }

        // Send password changed notification email
        emailService.sendPasswordChangedNotification(user.getEmail(), user.getFullName());

        log.info("Password changed successfully for user: {}", userId);
    }

    // ==================== Token Management ====================

    @Override
    @Transactional
    public LoginResponse refreshToken(RefreshTokenRequest request) {
        log.debug("Refresh token request");

        // Validate refresh token format
        UUID sessionId = jwtService.validateRefreshToken(request.getRefreshToken());
        if (sessionId == null) {
            throw new BusinessException("INVALID_REFRESH_TOKEN",
                    "Refresh token không hợp lệ hoặc đã hết hạn");
        }

        // Find active session
        UserSession session = sessionRepository.findById(sessionId)
                .filter(UserSession::isActive)
                .orElseThrow(() -> new BusinessException("SESSION_EXPIRED",
                        "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại"));

        User user = session.getUser();

        // Validate account status
        validateAccountStatus(user);

        // Generate new tokens
        String accessToken = jwtService.generateAccessToken(user, session.getId());

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(request.getRefreshToken()) // Keep same refresh token
                .tokenType("Bearer")
                .expiresIn(jwtService.getAccessTokenExpirationSeconds())
                .user(buildUserInfo(user))
                .build();
    }

    // ==================== UC-10: Logout ====================

    @Override
    @Transactional
    public void logout(UUID userId, LogoutRequest request) {
        log.info("Logout request for user: {}", userId);

        if (request.isLogoutAll()) {
            logoutAll(userId);
            return;
        }

        // Revoke specific session by refresh token
        if (request.getRefreshToken() != null) {
            UUID sessionId = jwtService.validateRefreshToken(request.getRefreshToken());
            if (sessionId != null) {
                sessionRepository.findById(sessionId)
                        .filter(s -> s.getUser().getId().equals(userId))
                        .ifPresent(UserSession::revoke);
            }
        }
    }

    @Override
    @Transactional
    public void logoutAll(UUID userId) {
        log.info("Logout all sessions for user: {}", userId);
        int revokedCount = sessionRepository.revokeAllSessionsByUserId(userId, LocalDateTime.now());
        log.info("Revoked {} sessions for user: {}", revokedCount, userId);
    }

    // ==================== Session Management ====================

    @Override
    public List<SessionResponse> getActiveSessions(UUID userId, UUID currentSessionId) {
        List<UserSession> sessions = sessionRepository.findActiveSessionsByUserId(userId, LocalDateTime.now());

        return sessions.stream()
                .map(session -> mapToSessionResponse(session, session.getId().equals(currentSessionId)))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void revokeSession(UUID userId, UUID sessionId) {
        UserSession session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Session", "id", sessionId));

        session.revoke();
        sessionRepository.save(session);
        log.info("Session {} revoked for user {}", sessionId, userId);
    }

    // ==================== Private Helper Methods ====================

    private LoginResponse createSessionAndGenerateTokens(User user, DeviceInfo deviceInfo,
                                                         String ipAddress, String userAgent) {
        // Detect device info from User-Agent and client-provided DeviceInfo
        // This properly distinguishes between Mobile App and Web Browser sessions
        Map<String, Object> detectedDeviceInfo = DeviceDetector.detectDevice(userAgent, deviceInfo);

        // Create session
        UserSession session = UserSession.builder()
                .user(user)
                .deviceInfo(detectedDeviceInfo)
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .expiresAt(LocalDateTime.now().plusSeconds(jwtService.getRefreshTokenExpirationMs() / 1000))
                .build();

        session = sessionRepository.save(session);

        // Generate tokens
        String accessToken = jwtService.generateAccessToken(user, session.getId());
        String refreshToken = jwtService.generateRefreshToken(session.getId());

        // Store refresh token hash in session
        session.setRefreshToken(session.getId().toString()); // Store session ID as reference
        sessionRepository.save(session);

        log.info("Session created for user: {}, sessionId: {}", user.getEmail(), session.getId());

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtService.getAccessTokenExpirationSeconds())
                .user(buildUserInfo(user))
                .build();
    }

    private void validateAccountStatus(User user) {
        if (user.isDeleted()) {
            throw new BusinessException("ACCOUNT_DELETED", "Tài khoản không tồn tại");
        }
        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new BusinessException("ACCOUNT_SUSPENDED",
                    "Tài khoản của bạn đã bị chặn. Hãy liên hệ trung tâm để được hỗ trợ.");
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException("ACCOUNT_INACTIVE",
                    "Tài khoản chưa được kích hoạt");
        }
    }

    private FirebaseToken verifyGoogleIdToken(String idToken) {
        if (firebaseAuth.isEmpty()) {
            throw new BusinessException("GOOGLE_AUTH_NOT_CONFIGURED",
                    "Đăng nhập Google chưa được cấu hình");
        }

        try {
            return firebaseAuth.get().verifyIdToken(idToken);
        } catch (Exception e) {
            log.error("Failed to verify Google ID token: {}", e.getMessage());
            throw new BusinessException("INVALID_GOOGLE_TOKEN",
                    "Token Google không hợp lệ");
        }
    }

    private String generateOTP() {
        SecureRandom random = new SecureRandom();
        StringBuilder otp = new StringBuilder(OTP_LENGTH);
        for (int i = 0; i < OTP_LENGTH; i++) {
            otp.append(OTP_CHARACTERS.charAt(random.nextInt(OTP_CHARACTERS.length())));
        }
        return otp.toString();
    }

    private LoginResponse.UserInfo buildUserInfo(User user) {
        return LoginResponse.UserInfo.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .avatarUrl(user.getAvatarUrl())
                .role(user.getRole())
                .status(user.getStatus())
                .build();
    }

    private SessionResponse mapToSessionResponse(UserSession session, boolean isCurrent) {
        // Convert stored device info back to DTO using DeviceDetector utility
        DeviceInfo deviceInfo = DeviceDetector.fromMap(session.getDeviceInfo());

        // Get human-readable device description
        String deviceDescription = DeviceDetector.getDeviceDescription(session.getDeviceInfo());

        return SessionResponse.builder()
                .id(session.getId())
                .deviceInfo(deviceInfo)
                .deviceDescription(deviceDescription)
                .ipAddress(session.getIpAddress())
                .createdAt(session.getCreatedAt())
                .expiresAt(session.getExpiresAt())
                .currentSession(isCurrent)
                .build();
    }
}

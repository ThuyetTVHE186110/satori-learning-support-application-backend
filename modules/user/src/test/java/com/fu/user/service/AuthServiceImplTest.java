package com.fu.user.service;

import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.infrastructure.email.EmailService;
import com.fu.user.dto.auth.*;
import com.fu.user.entity.*;
import com.fu.user.repository.UserRepository;
import com.fu.user.repository.UserSessionRepository;
import com.fu.user.security.JwtService;
import com.fu.user.service.impl.AuthServiceImpl;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AuthServiceImpl.
 * Tests authentication use cases:
 * - UC-06: Login with email/password
 * - UC-07: Forgot password (send OTP)
 * - UC-08: Reset password and change password
 * - UC-09: Google authentication
 * - UC-10: Logout
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService Unit Tests - Updated")
class AuthServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserSessionRepository sessionRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private EmailService emailService;

    @Mock
    private FirebaseAuth firebaseAuth;

    @InjectMocks
    private AuthServiceImpl authService;

    @Captor
    private ArgumentCaptor<User> userCaptor;

    @Captor
    private ArgumentCaptor<UserSession> sessionCaptor;

    // Test fixtures
    private User testUser;
    private UserSession testSession;
    private UUID userId;
    private DeviceInfo deviceInfo;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();

        testUser = User.builder()
                .email("test@example.com")
                .passwordHash("hashedPassword")
                .firstName("Test")
                .lastName("User")
                .displayName("Tester")
                .role(UserRole.LEARNER)
                .status(UserStatus.ACTIVE)
                .authProvider(AuthProvider.LOCAL)
                .failedLoginAttempts(0)
                .build();
        ReflectionTestUtils.setField(testUser, "id", userId);

        deviceInfo = DeviceInfo.builder()
                .deviceType("desktop")
                .os("Windows 11")
                .browser("Chrome")
                .build();

        testSession = UserSession.builder()
                .user(testUser)
                .refreshToken("refresh-token")
                .ipAddress("127.0.0.1")
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build();
        ReflectionTestUtils.setField(testSession, "id", UUID.randomUUID());

        // Set default expiration minutes
        ReflectionTestUtils.setField(authService, "otpExpirationMinutes", 10);

        // Inject Optional<FirebaseAuth> for Google auth tests
        ReflectionTestUtils.setField(authService, "firebaseAuth", Optional.of(firebaseAuth));
    }

    @Nested
    @DisplayName("UC-06: Login Tests")
    class LoginTests {

        @Test
        @DisplayName("Should login successfully with valid credentials")
        void shouldLoginSuccessfully() {
            // Given
            LoginRequest request = new LoginRequest();
            request.setEmail("test@example.com");
            request.setPassword("ValidPassword123!");
            request.setDeviceInfo(deviceInfo);

            String accessToken = "jwt-access-token";
            String refreshToken = "jwt-refresh-token";

            given(userRepository.findByEmail(request.getEmail())).willReturn(Optional.of(testUser));
            given(passwordEncoder.matches(request.getPassword(), testUser.getPasswordHash())).willReturn(true);
            given(jwtService.generateAccessToken(eq(testUser), any(UUID.class))).willReturn(accessToken);
            given(jwtService.generateRefreshToken(any(UUID.class))).willReturn(refreshToken);
            given(sessionRepository.save(any(UserSession.class))).willReturn(testSession);

            // When
            LoginResponse result = authService.login(request, "127.0.0.1", "Chrome");

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getAccessToken()).isEqualTo(accessToken);
            assertThat(result.getRefreshToken()).isEqualTo(refreshToken);

            verify(userRepository).save(userCaptor.capture());
            User savedUser = userCaptor.getValue();
            assertThat(savedUser.getFailedLoginAttempts()).isEqualTo(0);
            assertThat(savedUser.getLastLoginAt()).isNotNull();

            verify(sessionRepository, times(2)).save(any(UserSession.class));
        }

        @Test
        @DisplayName("Should reject login with invalid password")
        void shouldRejectInvalidPassword() {
            // Given
            LoginRequest request = new LoginRequest();
            request.setEmail("test@example.com");
            request.setPassword("WrongPassword");
            request.setDeviceInfo(deviceInfo);

            given(userRepository.findByEmail(request.getEmail())).willReturn(Optional.of(testUser));
            given(passwordEncoder.matches(request.getPassword(), testUser.getPasswordHash())).willReturn(false);

            // When/Then
            assertThatThrownBy(() -> authService.login(request, "127.0.0.1", "Chrome"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("email hoặc mật khẩu");

            verify(userRepository).save(userCaptor.capture());
            User savedUser = userCaptor.getValue();
            assertThat(savedUser.getFailedLoginAttempts()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should lock account after 5 failed attempts")
        void shouldLockAccountAfterFailedAttempts() {
            // Given
            testUser.setFailedLoginAttempts(4); // One more will reach 5
            LoginRequest request = new LoginRequest();
            request.setEmail("test@example.com");
            request.setPassword("WrongPassword");
            request.setDeviceInfo(deviceInfo);

            given(userRepository.findByEmail(request.getEmail())).willReturn(Optional.of(testUser));
            given(passwordEncoder.matches(request.getPassword(), testUser.getPasswordHash())).willReturn(false);

            // When/Then
            assertThatThrownBy(() -> authService.login(request, "127.0.0.1", "Chrome"))
                    .isInstanceOf(BusinessException.class);

            verify(userRepository).save(userCaptor.capture());
            User savedUser = userCaptor.getValue();
            assertThat(savedUser.getFailedLoginAttempts()).isEqualTo(5);
            assertThat(savedUser.getLockedUntil()).isNotNull();
        }

        @Test
        @DisplayName("Should reject login for inactive user")
        void shouldRejectInactiveUser() {
            // Given
            testUser.setStatus(UserStatus.INACTIVE);
            LoginRequest request = new LoginRequest();
            request.setEmail("test@example.com");
            request.setPassword("password");
            request.setDeviceInfo(deviceInfo);

            given(userRepository.findByEmail(request.getEmail())).willReturn(Optional.of(testUser));

            // When/Then
            assertThatThrownBy(() -> authService.login(request, "127.0.0.1", "Chrome"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("Should reject login for locked user")
        void shouldRejectLockedUser() {
            // Given
            testUser.setLockedUntil(LocalDateTime.now().plusHours(1));
            LoginRequest request = new LoginRequest();
            request.setEmail("test@example.com");
            request.setPassword("password");
            request.setDeviceInfo(deviceInfo);

            given(userRepository.findByEmail(request.getEmail())).willReturn(Optional.of(testUser));

            // When/Then
            assertThatThrownBy(() -> authService.login(request, "127.0.0.1", "Chrome"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("khóa");
        }
    }

    @Nested
    @DisplayName("UC-07: Forgot Password Tests")
    class ForgotPasswordTests {

        @Test
        @DisplayName("Should send OTP successfully for valid email")
        void shouldSendOtpSuccessfully() {
            // Given
            ForgotPasswordRequest request = new ForgotPasswordRequest();
            request.setEmail("test@example.com");

            given(userRepository.findActiveByEmail(request.getEmail())).willReturn(Optional.of(testUser));
            given(userRepository.save(any(User.class))).willReturn(testUser);
            doNothing().when(emailService).sendPasswordResetOtp(anyString(), anyString(), anyString(), anyInt());

            // When
            authService.forgotPassword(request);

            // Then
            verify(userRepository).save(userCaptor.capture());
            User savedUser = userCaptor.getValue();
            assertThat(savedUser.getPasswordResetToken()).isNotNull();
            assertThat(savedUser.getPasswordResetToken()).hasSize(6);
            assertThat(savedUser.getPasswordResetToken()).matches("\\d{6}"); // 6 digits
            assertThat(savedUser.getPasswordResetTokenExpiresAt()).isAfter(LocalDateTime.now());

            verify(emailService).sendPasswordResetOtp(
                    eq("test@example.com"),
                    anyString(),
                    anyString(),
                    eq(10));
        }

        @Test
        @DisplayName("Should not reveal non-existent email (security)")
        void shouldNotRevealNonExistentEmail() {
            // Given
            ForgotPasswordRequest request = new ForgotPasswordRequest();
            request.setEmail("nonexistent@example.com");

            given(userRepository.findActiveByEmail(request.getEmail())).willReturn(Optional.empty());

            // When/Then - Should not throw exception (security best practice)
            assertThatNoException().isThrownBy(() -> authService.forgotPassword(request));

            verify(userRepository, never()).save(any(User.class));
            verify(emailService, never()).sendPasswordResetOtp(anyString(), anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("Should silently handle Google auth user")
        void shouldHandleGoogleAuthUserSilently() {
            // Given
            testUser.setAuthProvider(AuthProvider.GOOGLE);
            testUser.setPasswordHash(null); // Google-only account
            ForgotPasswordRequest request = new ForgotPasswordRequest();
            request.setEmail("test@example.com");

            given(userRepository.findActiveByEmail(request.getEmail())).willReturn(Optional.of(testUser));

            // When/Then - Should not throw exception or send email
            assertThatNoException().isThrownBy(() -> authService.forgotPassword(request));

            verify(emailService, never()).sendPasswordResetOtp(anyString(), anyString(), anyString(), anyInt());
        }
    }

    @Nested
    @DisplayName("UC-07: Verify OTP Tests")
    class VerifyOtpTests {

        @Test
        @DisplayName("Should verify OTP and return reset token successfully")
        void shouldVerifyOtpSuccessfully() {
            // Given
            testUser.setPasswordResetToken("123456");
            testUser.setPasswordResetTokenExpiresAt(LocalDateTime.now().plusMinutes(10));

            VerifyOtpRequest request = new VerifyOtpRequest();
            request.setEmail("test@example.com");
            request.setOtp("123456");

            given(userRepository.findActiveByEmail(request.getEmail())).willReturn(Optional.of(testUser));
            given(userRepository.save(any(User.class))).willReturn(testUser);

            // When
            VerifyOtpResponse response = authService.verifyOtp(request);

            // Then
            assertThat(response).isNotNull();
            assertThat(response.getResetToken()).isNotNull();
            assertThat(response.getEmail()).isEqualTo("test@example.com");
            assertThat(response.getExpiresInSeconds()).isEqualTo(300); // 5 minutes

            verify(userRepository).save(userCaptor.capture());
            User savedUser = userCaptor.getValue();
            assertThat(savedUser.getPasswordResetToken()).isNotEqualTo("123456"); // Token changed to UUID
            assertThat(savedUser.getPasswordResetTokenExpiresAt()).isAfter(LocalDateTime.now());
        }

        @Test
        @DisplayName("Should reject invalid OTP")
        void shouldRejectInvalidOtp() {
            // Given
            testUser.setPasswordResetToken("123456");
            testUser.setPasswordResetTokenExpiresAt(LocalDateTime.now().plusMinutes(10));

            VerifyOtpRequest request = new VerifyOtpRequest();
            request.setEmail("test@example.com");
            request.setOtp("999999");

            given(userRepository.findActiveByEmail(request.getEmail())).willReturn(Optional.of(testUser));

            // When/Then
            assertThatThrownBy(() -> authService.verifyOtp(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("OTP");
        }

        @Test
        @DisplayName("Should reject expired OTP")
        void shouldRejectExpiredOtp() {
            // Given
            testUser.setPasswordResetToken("123456");
            testUser.setPasswordResetTokenExpiresAt(LocalDateTime.now().minusMinutes(1)); // Expired

            VerifyOtpRequest request = new VerifyOtpRequest();
            request.setEmail("test@example.com");
            request.setOtp("123456");

            given(userRepository.findActiveByEmail(request.getEmail())).willReturn(Optional.of(testUser));

            // When/Then
            assertThatThrownBy(() -> authService.verifyOtp(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("hết hạn");
        }
    }

    @Nested
    @DisplayName("UC-08: Reset Password Tests")
    class ResetPasswordTests {

        @Test
        @DisplayName("Should reset password successfully with valid reset token")
        void shouldResetPasswordSuccessfully() {
            // Given
            String resetToken = UUID.randomUUID().toString();
            testUser.setPasswordResetToken(resetToken);
            testUser.setPasswordResetTokenExpiresAt(LocalDateTime.now().plusMinutes(5));

            ResetPasswordRequest request = new ResetPasswordRequest();
            request.setResetToken(resetToken);
            request.setNewPassword("NewPassword123!");
            request.setConfirmPassword("NewPassword123!");

            given(userRepository.findByPasswordResetToken(resetToken)).willReturn(Optional.of(testUser));
            given(passwordEncoder.encode(request.getNewPassword())).willReturn("newHashedPassword");
            given(userRepository.save(any(User.class))).willReturn(testUser);
            doNothing().when(emailService).sendPasswordChangedNotification(anyString(), anyString());

            // When
            authService.resetPassword(request);

            // Then
            verify(userRepository).save(userCaptor.capture());
            User savedUser = userCaptor.getValue();
            assertThat(savedUser.getPasswordHash()).isEqualTo("newHashedPassword");
            assertThat(savedUser.getPasswordResetToken()).isNull();
            assertThat(savedUser.getPasswordResetTokenExpiresAt()).isNull();

            verify(emailService).sendPasswordChangedNotification(
                    eq("test@example.com"),
                    anyString());
        }

        @Test
        @DisplayName("Should reject invalid reset token")
        void shouldRejectInvalidResetToken() {
            // Given
            ResetPasswordRequest request = new ResetPasswordRequest();
            request.setResetToken("invalid-token");
            request.setNewPassword("NewPassword123!");
            request.setConfirmPassword("NewPassword123!");

            given(userRepository.findByPasswordResetToken("invalid-token")).willReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> authService.resetPassword(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Token");
        }

        @Test
        @DisplayName("Should reject expired reset token")
        void shouldRejectExpiredResetToken() {
            // Given
            String resetToken = UUID.randomUUID().toString();
            testUser.setPasswordResetToken(resetToken);
            testUser.setPasswordResetTokenExpiresAt(LocalDateTime.now().minusMinutes(1)); // Expired

            ResetPasswordRequest request = new ResetPasswordRequest();
            request.setResetToken(resetToken);
            request.setNewPassword("NewPassword123!");
            request.setConfirmPassword("NewPassword123!");

            given(userRepository.findByPasswordResetToken(resetToken)).willReturn(Optional.of(testUser));
            given(userRepository.save(any(User.class))).willReturn(testUser);

            // When/Then
            assertThatThrownBy(() -> authService.resetPassword(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("hết hạn");
        }
    }

    @Nested
    @DisplayName("UC-08: Change Password Tests")
    class ChangePasswordTests {

        @Test
        @DisplayName("Should change password successfully")
        void shouldChangePasswordSuccessfully() {
            // Given
            ChangePasswordRequest request = new ChangePasswordRequest();
            request.setCurrentPassword("OldPassword123!");
            request.setNewPassword("NewPassword456@");
            request.setConfirmPassword("NewPassword456@");

            given(userRepository.findById(userId)).willReturn(Optional.of(testUser));
            given(passwordEncoder.matches(request.getCurrentPassword(), testUser.getPasswordHash())).willReturn(true);
            given(passwordEncoder.encode(request.getNewPassword())).willReturn("newHashedPassword");
            given(userRepository.save(any(User.class))).willReturn(testUser);
            doNothing().when(emailService).sendPasswordChangedNotification(anyString(), anyString());

            // When
            UUID currentSessionId = UUID.randomUUID();
            authService.changePassword(userId, request, currentSessionId);

            // Then
            verify(userRepository).save(userCaptor.capture());
            User savedUser = userCaptor.getValue();
            assertThat(savedUser.getPasswordHash()).isEqualTo("newHashedPassword");

            verify(emailService).sendPasswordChangedNotification(
                    eq("test@example.com"),
                    anyString());
        }

        @Test
        @DisplayName("Should reject change password with wrong current password")
        void shouldRejectWrongCurrentPassword() {
            // Given
            ChangePasswordRequest request = new ChangePasswordRequest();
            request.setCurrentPassword("WrongPassword");
            request.setNewPassword("NewPassword456@");
            request.setConfirmPassword("NewPassword456@");

            given(userRepository.findById(userId)).willReturn(Optional.of(testUser));
            given(passwordEncoder.matches(request.getCurrentPassword(), testUser.getPasswordHash())).willReturn(false);

            // When/Then
            UUID currentSessionId = UUID.randomUUID();
            assertThatThrownBy(() -> authService.changePassword(userId, request, currentSessionId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("hiện tại");

            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        @DisplayName("Should reject change password for Google auth user without password")
        void shouldRejectChangePasswordForGoogleUser() {
            // Given
            testUser.setAuthProvider(AuthProvider.GOOGLE);
            testUser.setPasswordHash(null); // Google-only account
            ChangePasswordRequest request = new ChangePasswordRequest();
            request.setCurrentPassword("password");
            request.setNewPassword("newPassword");

            given(userRepository.findById(userId)).willReturn(Optional.of(testUser));

            // When/Then
            UUID currentSessionId = UUID.randomUUID();
            assertThatThrownBy(() -> authService.changePassword(userId, request, currentSessionId))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("UC-09: Google Authentication Tests")
    class GoogleAuthTests {

        @Test
        @DisplayName("Should login existing Google user successfully")
        void shouldLoginExistingGoogleUser() throws Exception {
            // Given
            testUser.setAuthProvider(AuthProvider.GOOGLE);
            testUser.setGoogleId("google-user-id");

            GoogleLoginRequest request = new GoogleLoginRequest();
            request.setIdToken("firebase-id-token");
            request.setDeviceInfo(deviceInfo);

            FirebaseToken mockToken = mock(FirebaseToken.class);
            given(firebaseAuth.verifyIdToken(request.getIdToken())).willReturn(mockToken);
            given(mockToken.getEmail()).willReturn("test@example.com");
            given(mockToken.getUid()).willReturn("google-user-id");

            given(userRepository.findByGoogleId("google-user-id")).willReturn(Optional.of(testUser));
            given(jwtService.generateAccessToken(eq(testUser), any(UUID.class))).willReturn("access-token");
            given(jwtService.generateRefreshToken(any(UUID.class))).willReturn("refresh-token");
            given(sessionRepository.save(any(UserSession.class))).willReturn(testSession);

            // When
            LoginResponse result = authService.loginWithGoogle(request, "127.0.0.1", "Chrome");

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getAccessToken()).isNotNull();
            verify(userRepository).save(any(User.class)); // Update lastLoginAt
        }

        @Test
        @DisplayName("Should reject Google login for non-existent user")
        void shouldRejectNonExistentGoogleUser() throws Exception {
            // Given
            GoogleLoginRequest request = new GoogleLoginRequest();
            request.setIdToken("firebase-id-token");
            request.setDeviceInfo(deviceInfo);

            FirebaseToken mockToken = mock(FirebaseToken.class);
            given(firebaseAuth.verifyIdToken(request.getIdToken())).willReturn(mockToken);
            given(mockToken.getEmail()).willReturn("newuser@gmail.com");
            given(mockToken.getUid()).willReturn("new-google-id");

            given(userRepository.findByGoogleId("new-google-id")).willReturn(Optional.empty());
            given(userRepository.findByEmail("newuser@gmail.com")).willReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> authService.loginWithGoogle(request, "127.0.0.1", "Chrome"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("chưa được đăng ký");
        }

        @Test
        @DisplayName("Should reject invalid Firebase token")
        void shouldRejectInvalidFirebaseToken() throws Exception {
            // Given
            GoogleLoginRequest request = new GoogleLoginRequest();
            request.setIdToken("invalid-token");
            request.setDeviceInfo(deviceInfo);

            given(firebaseAuth.verifyIdToken(request.getIdToken()))
                    .willThrow(new RuntimeException("Invalid token"));

            // When/Then
            assertThatThrownBy(() -> authService.loginWithGoogle(request, "127.0.0.1", "Chrome"))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("UC-10: Logout Tests")
    class LogoutTests {

        @Test
        @DisplayName("Should logout successfully")
        void shouldLogoutSuccessfully() {
            // Given
            LogoutRequest request = LogoutRequest.builder()
                    .refreshToken("refresh-token")
                    .logoutAll(false)
                    .build();
            UUID sessionId = (UUID) ReflectionTestUtils.getField(testSession, "id");

            given(jwtService.validateRefreshToken("refresh-token")).willReturn(sessionId);
            given(sessionRepository.findById(sessionId)).willReturn(Optional.of(testSession));

            // When
            authService.logout(userId, request);

            // Then
            assertThat(testSession.getRevokedAt()).isNotNull();
        }

        @Test
        @DisplayName("Should logout from all devices")
        void shouldLogoutAll() {
            // Given
            LogoutRequest request = LogoutRequest.builder()
                    .logoutAll(true)
                    .build();

            // When
            authService.logout(userId, request);

            // Then
            verify(sessionRepository).revokeAllSessionsByUserId(eq(userId), any(LocalDateTime.class));
        }
    }

    // =========================================================================
    // Additional tests mapped to the test plan (TC_AUTH_LG_003/004/005/007/010,
    // TC_AUTH_FP_006/008/009, TC_AUTH_CP_002/004/005, TC_AUTH_LO_004)
    // =========================================================================

    @Nested
    @DisplayName("TC_AUTH_LG_003/004/005/007 — Login edge cases")
    class LoginEdgeCaseTests {

        /**
         * TC_AUTH_LG_003 — Non-existing email returns the SAME error as wrong password.
         * Security: client must not be able to enumerate valid emails.
         */
        @Test
        @DisplayName("TC_AUTH_LG_003 — Non-existing email → INVALID_CREDENTIALS (same message as LG_002)")
        void nonExistingEmailReturnsInvalidCredentials() {
            LoginRequest request = new LoginRequest();
            request.setEmail("ghost@test.com");
            request.setPassword("ValidPass@1");
            request.setDeviceInfo(deviceInfo);

            given(userRepository.findByEmail("ghost@test.com")).willReturn(Optional.empty());

            assertThatThrownBy(() -> authService.login(request, "127.0.0.1", "Chrome"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo("INVALID_CREDENTIALS");

            // Service must NOT save anything when email is not found
            verify(userRepository, never()).save(any(User.class));
        }

        /**
         * TC_AUTH_LG_004 — SUSPENDED account → ACCOUNT_SUSPENDED is thrown.
         * HTTP mapping to 403 is the responsibility of UserExceptionHandler (see NOTE
         * FOR DEV
         * in AuthControllerTest.TC_AUTH_LG_004).
         */
        @Test
        @DisplayName("TC_AUTH_LG_004 — SUSPENDED account → throws ACCOUNT_SUSPENDED")
        void suspendedAccountThrowsAccountSuspended() {
            testUser.setStatus(UserStatus.SUSPENDED);
            LoginRequest request = new LoginRequest();
            request.setEmail("learner@test.com");
            request.setPassword("ValidPass@1");
            request.setDeviceInfo(deviceInfo);

            given(userRepository.findByEmail("learner@test.com")).willReturn(Optional.of(testUser));

            assertThatThrownBy(() -> authService.login(request, "127.0.0.1", "Chrome"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo("ACCOUNT_SUSPENDED");
        }

        /**
         * TC_AUTH_LG_005 — Soft-deleted account → ACCOUNT_DELETED is thrown.
         */
        @Test
        @DisplayName("TC_AUTH_LG_005 — Soft-deleted account → throws ACCOUNT_DELETED")
        void softDeletedAccountThrowsAccountDeleted() {
            // Simulate soft-delete: deletedAt is set
            ReflectionTestUtils.setField(testUser, "deletedAt", java.time.LocalDateTime.now().minusDays(1));
            LoginRequest request = new LoginRequest();
            request.setEmail("learner@test.com");
            request.setPassword("ValidPass@1");
            request.setDeviceInfo(deviceInfo);

            given(userRepository.findByEmail("learner@test.com")).willReturn(Optional.of(testUser));

            assertThatThrownBy(() -> authService.login(request, "127.0.0.1", "Chrome"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo("ACCOUNT_DELETED");
        }

        /**
         * TC_AUTH_LG_007 — Correct credentials succeed after lock period expires.
         * lockedUntil is in the past → login proceeds, failedAttempts and lockedUntil
         * reset.
         */
        @Test
        @DisplayName("TC_AUTH_LG_007 — Login succeeds after lock period expires; counter reset")
        void loginSucceedsAfterLockPeriodExpires() {
            testUser.setFailedLoginAttempts(5);
            testUser.setLockedUntil(LocalDateTime.now().minusSeconds(1)); // expired lock
            LoginRequest request = new LoginRequest();
            request.setEmail("test@example.com");
            request.setPassword("ValidPass@1");
            request.setDeviceInfo(deviceInfo);

            given(userRepository.findByEmail("test@example.com")).willReturn(Optional.of(testUser));
            given(passwordEncoder.matches("ValidPass@1", testUser.getPasswordHash())).willReturn(true);
            given(jwtService.generateAccessToken(eq(testUser), any(UUID.class))).willReturn("access-token");
            given(jwtService.generateRefreshToken(any(UUID.class))).willReturn("refresh-token");
            given(sessionRepository.save(any(UserSession.class))).willReturn(testSession);

            LoginResponse result = authService.login(request, "127.0.0.1", "Chrome");

            assertThat(result).isNotNull();
            assertThat(result.getAccessToken()).isNotEmpty();

            // failedAttempts must be reset to 0 and lockedUntil cleared
            verify(userRepository).save(userCaptor.capture());
            User saved = userCaptor.getValue();
            assertThat(saved.getFailedLoginAttempts()).isEqualTo(0);
            assertThat(saved.getLockedUntil()).isNull();
        }
    }

    @Nested
    @DisplayName("TC_AUTH_LG_010 — Refresh token reuse (Known Security Gap)")
    class RefreshTokenReuseTests {

        /**
         * TC_AUTH_LG_010 — Refresh token reuse is allowed (known security gap /
         * by-design).
         *
         * KNOWN BUG / SECURITY NOTE FOR DEV:
         * Refresh token rotation is NOT implemented. The same refresh token can be
         * reused as long as the underlying UserSession is still active.
         * This test documents the CURRENT behavior (reuse succeeds) so that a future
         * regression is detected if rotation is accidentally broken in the other
         * direction.
         *
         * Recommendation: Implement refresh-token rotation — invalidate the refresh
         * token
         * (e.g. by revoking the session or storing a one-time token ID) after each use.
         * When that fix is applied, this test should be updated to expect
         * SESSION_EXPIRED
         * on the second call.
         */
        @Test
        @DisplayName("TC_AUTH_LG_010 — Refresh token reuse succeeds (KNOWN BUG: rotation not implemented)")
        void refreshTokenReuseSucceeds_knownSecurityGap() {
            // Arrange: a session that is still active
            UUID sessionId = (UUID) ReflectionTestUtils.getField(testSession, "id");
            RefreshTokenRequest request = new RefreshTokenRequest();
            request.setRefreshToken("valid-refresh-token");

            given(jwtService.validateRefreshToken("valid-refresh-token")).willReturn(sessionId);
            given(sessionRepository.findById(sessionId)).willReturn(Optional.of(testSession));
            given(jwtService.generateAccessToken(eq(testUser), eq(sessionId)))
                    .willReturn("new-access-token-1")
                    .willReturn("new-access-token-2");

            // First use
            LoginResponse first = authService.refreshToken(request);
            assertThat(first.getAccessToken()).isEqualTo("new-access-token-1");

            // Second use with SAME refresh token — should also succeed (current behavior)
            LoginResponse second = authService.refreshToken(request);
            assertThat(second.getAccessToken()).isEqualTo("new-access-token-2");
            // Same refresh token returned (no rotation)
            assertThat(second.getRefreshToken()).isEqualTo("valid-refresh-token");
        }
    }

    @Nested
    @DisplayName("TC_AUTH_FP_006/008/009 — Forgot password security")
    class ForgotPasswordSecurityTests {

        /**
         * TC_AUTH_FP_006 — Reset token is single-use: after resetPassword() succeeds,
         * the token field on the User is cleared (null), so a second lookup finds
         * nothing.
         */
        @Test
        @DisplayName("TC_AUTH_FP_006 — One-time reset token: token cleared after first use")
        void resetTokenClearedAfterSuccessfulUse() {
            String resetToken = UUID.randomUUID().toString();
            testUser.setPasswordResetToken(resetToken);
            testUser.setPasswordResetTokenExpiresAt(LocalDateTime.now().plusMinutes(5));

            ResetPasswordRequest request = ResetPasswordRequest.builder()
                    .resetToken(resetToken)
                    .newPassword("NewPass@1")
                    .confirmPassword("NewPass@1")
                    .build();

            given(userRepository.findByPasswordResetToken(resetToken)).willReturn(Optional.of(testUser));
            given(passwordEncoder.encode("NewPass@1")).willReturn("$2a$10$newHashed");
            given(userRepository.save(any(User.class))).willReturn(testUser);
            doNothing().when(emailService).sendPasswordChangedNotification(anyString(), anyString());

            authService.resetPassword(request);

            // passwordResetToken must be null after reset
            verify(userRepository).save(userCaptor.capture());
            User saved = userCaptor.getValue();
            assertThat(saved.getPasswordResetToken()).isNull();
            assertThat(saved.getPasswordResetTokenExpiresAt()).isNull();

            // Simulate second attempt: token is gone → findByPasswordResetToken returns
            // empty
            given(userRepository.findByPasswordResetToken(resetToken)).willReturn(Optional.empty());

            assertThatThrownBy(() -> authService.resetPassword(request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo("INVALID_TOKEN");
        }

        /**
         * TC_AUTH_FP_008 — Password mismatch during resetPassword → PASSWORD_MISMATCH.
         */
        @Test
        @DisplayName("TC_AUTH_FP_008 — Confirm password mismatch on reset → throws PASSWORD_MISMATCH")
        void resetPasswordWithMismatchThrowsPasswordMismatch() {
            ResetPasswordRequest request = ResetPasswordRequest.builder()
                    .resetToken(UUID.randomUUID().toString())
                    .newPassword("NewPass@1")
                    .confirmPassword("NewPass@2") // mismatch
                    .build();

            // Service checks mismatch BEFORE findByPasswordResetToken
            assertThatThrownBy(() -> authService.resetPassword(request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo("PASSWORD_MISMATCH");

            verify(userRepository, never()).findByPasswordResetToken(anyString());
        }

        /**
         * TC_AUTH_FP_009 — OTP brute force (Known Security Gap).
         *
         * KNOWN BUG / SECURITY NOTE FOR DEV:
         * No OTP attempt counter is implemented. The system allows unlimited
         * wrong OTP submissions without locking the user or invalidating the OTP.
         * An attacker can enumerate all 10^6 combinations within the OTP validity
         * window.
         *
         * Recommendation: Track attempt count on the User or a dedicated OTP entity;
         * invalidate (null) the OTP after N (e.g. 5) failed attempts.
         * When that fix is applied, this test must be updated to expect OTP_EXPIRED or
         * INVALID_OTP after N+1 attempts.
         */
        @Test
        @DisplayName("TC_AUTH_FP_009 — OTP brute force: unlimited wrong attempts accepted (KNOWN SECURITY GAP)")
        void otpBruteForceAllowed_knownSecurityGap() {
            testUser.setPasswordResetToken("654321");
            testUser.setPasswordResetTokenExpiresAt(LocalDateTime.now().plusMinutes(10));

            given(userRepository.findActiveByEmail("test@example.com"))
                    .willReturn(Optional.of(testUser));

            VerifyOtpRequest wrongRequest = new VerifyOtpRequest();
            wrongRequest.setEmail("test@example.com");
            wrongRequest.setOtp("000000");

            // 10 consecutive wrong attempts — all return INVALID_OTP, none lock or
            // invalidate
            for (int i = 0; i < 10; i++) {
                assertThatThrownBy(() -> authService.verifyOtp(wrongRequest))
                        .isInstanceOf(BusinessException.class)
                        .extracting("errorCode")
                        .isEqualTo("INVALID_OTP");
            }

            // Token is still valid — correct OTP still works after 10 failures
            VerifyOtpRequest correctRequest = new VerifyOtpRequest();
            correctRequest.setEmail("test@example.com");
            correctRequest.setOtp("654321");
            given(userRepository.save(any(User.class))).willReturn(testUser);

            VerifyOtpResponse response = authService.verifyOtp(correctRequest);
            assertThat(response.getResetToken()).isNotNull();
        }
    }

    @Nested
    @DisplayName("TC_AUTH_CP_002/004/005 — Change password edge cases")
    class ChangePasswordEdgeCaseTests {

        /**
         * TC_AUTH_CP_002 — logoutOtherDevices=true revokes all OTHER sessions.
         * Current session is preserved; all others are revoked.
         */
        @Test
        @DisplayName("TC_AUTH_CP_002 — logoutOtherDevices=true invokes revokeOtherSessions for all other sessions")
        void changePasswordRevokesOtherSessions() {
            UUID currentSessionId = UUID.randomUUID();

            ChangePasswordRequest request = ChangePasswordRequest.builder()
                    .currentPassword("OldPass@1")
                    .newPassword("NewPass@1")
                    .confirmPassword("NewPass@1")
                    .logoutOtherDevices(true)
                    .build();

            given(userRepository.findById(userId)).willReturn(Optional.of(testUser));
            given(passwordEncoder.matches("OldPass@1", testUser.getPasswordHash())).willReturn(true);
            given(passwordEncoder.encode("NewPass@1")).willReturn("$2a$10$newHash");
            given(userRepository.save(any(User.class))).willReturn(testUser);
            doNothing().when(emailService).sendPasswordChangedNotification(anyString(), anyString());
            given(sessionRepository.revokeOtherSessionsByUserId(eq(userId), eq(currentSessionId), any()))
                    .willReturn(2);

            authService.changePassword(userId, request, currentSessionId);

            // Verify revokeOtherSessionsByUserId was called with the current session
            // preserved
            verify(sessionRepository).revokeOtherSessionsByUserId(
                    eq(userId), eq(currentSessionId), any(LocalDateTime.class));
        }

        /**
         * TC_AUTH_CP_004 — New password identical to current password.
         *
         * NOTE FOR DEV (BR-59 NOT YET IMPLEMENTED):
         * The business rule "new password must not equal current password" (BR-59)
         * is documented in the test plan but is NOT yet enforced in AuthServiceImpl.
         * Currently, the service will silently succeed (re-hash the same password).
         *
         * This test verifies the DESIRED behavior (throws SAME_PASSWORD).
         * It will FAIL until the following check is added in
         * AuthServiceImpl.changePassword():
         *
         * if (passwordEncoder.matches(request.getNewPassword(),
         * user.getPasswordHash())) {
         * throw new BusinessException("SAME_PASSWORD",
         * "Mật khẩu mới không được trùng với mật khẩu cũ");
         * }
         *
         * After adding that check, remove @Disabled and this note.
         */
        @Test
        @org.junit.jupiter.api.Disabled("TC_AUTH_CP_004: BR-59 not yet implemented in AuthServiceImpl — see NOTE FOR DEV")
        @DisplayName("TC_AUTH_CP_004 — New password same as current → SAME_PASSWORD (BR-59, @Disabled until fixed)")
        void newPasswordSameAsCurrentThrowsSamePassword() {
            ChangePasswordRequest request = ChangePasswordRequest.builder()
                    .currentPassword("OldPass@1")
                    .newPassword("OldPass@1") // same as current
                    .confirmPassword("OldPass@1")
                    .build();

            given(userRepository.findById(userId)).willReturn(Optional.of(testUser));
            // Step 1: current password matches
            given(passwordEncoder.matches("OldPass@1", testUser.getPasswordHash())).willReturn(true);
            // Step 2: new password also matches current (equals check for BR-59)
            given(passwordEncoder.matches("OldPass@1", testUser.getPasswordHash())).willReturn(true);

            assertThatThrownBy(() -> authService.changePassword(userId, request, UUID.randomUUID()))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo("SAME_PASSWORD");

            verify(userRepository, never()).save(any(User.class));
        }

        /**
         * TC_AUTH_CP_005 — Confirm password does not match new password →
         * PASSWORD_MISMATCH.
         */
        @Test
        @DisplayName("TC_AUTH_CP_005 — Confirm mismatch on changePassword → throws PASSWORD_MISMATCH")
        void changePasswordConfirmMismatchThrowsPasswordMismatch() {
            ChangePasswordRequest request = ChangePasswordRequest.builder()
                    .currentPassword("OldPass@1")
                    .newPassword("NewPass@1")
                    .confirmPassword("NewPass@2") // mismatch
                    .build();

            given(userRepository.findById(userId)).willReturn(Optional.of(testUser));

            assertThatThrownBy(() -> authService.changePassword(userId, request, UUID.randomUUID()))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo("PASSWORD_MISMATCH");

            verify(userRepository, never()).save(any(User.class));
        }
    }

    @Nested
    @DisplayName("TC_AUTH_LO_004 — Logout with another user's refresh token")
    class LogoutSecurityTests {

        /**
         * TC_AUTH_LO_004 — Logout submitted with a refresh token belonging to a
         * DIFFERENT user.
         * The system must NOT revoke another user's session.
         *
         * Mechanism: sessionRepository.findById(sessionId).filter(s ->
         * s.getUser().getId().equals(userId))
         * The filter predicate rejects sessions whose owner ≠ the authenticated user.
         * As a result, the session is simply ignored (not revoked).
         */
        @Test
        @DisplayName("TC_AUTH_LO_004 — Logout with other user's refresh token does NOT revoke their session")
        void logoutWithOtherUsersTokenDoesNotRevokeTheirSession() {
            // userB's session (owned by a different user)
            UUID userBId = UUID.randomUUID();
            User userB = User.builder()
                    .email("userB@test.com")
                    .role(UserRole.LEARNER)
                    .status(UserStatus.ACTIVE)
                    .authProvider(AuthProvider.LOCAL)
                    .failedLoginAttempts(0)
                    .build();
            ReflectionTestUtils.setField(userB, "id", userBId);

            UserSession userBSession = UserSession.builder()
                    .user(userB)
                    .refreshToken("userB-refresh-token")
                    .expiresAt(LocalDateTime.now().plusDays(7))
                    .build();
            UUID userBSessionId = UUID.randomUUID();
            ReflectionTestUtils.setField(userBSession, "id", userBSessionId);

            LogoutRequest request = LogoutRequest.builder()
                    .refreshToken("userB-refresh-token")
                    .logoutAll(false)
                    .build();

            // jwtService decodes userB's refresh token → returns userB's sessionId
            given(jwtService.validateRefreshToken("userB-refresh-token")).willReturn(userBSessionId);
            // Session belongs to userB, not testUser (userId)
            given(sessionRepository.findById(userBSessionId)).willReturn(Optional.of(userBSession));

            // Should NOT throw; should silently do nothing (filter rejects cross-user
            // revocation)
            assertThatNoException().isThrownBy(() -> authService.logout(userId, request));

            // userB's session revoke() must NOT have been called — revokedAt stays null
            assertThat(userBSession.getRevokedAt()).isNull();
        }
    }

    @Nested
    @DisplayName("Refresh Token Tests")
    class RefreshTokenTests {

        @Test
        @DisplayName("Should refresh access token successfully")
        void shouldRefreshTokenSuccessfully() {
            // Given
            RefreshTokenRequest request = new RefreshTokenRequest();
            request.setRefreshToken("valid-refresh-token");
            String newAccessToken = "new-access-token";
            UUID sessionId = (UUID) ReflectionTestUtils.getField(testSession, "id");

            given(jwtService.validateRefreshToken("valid-refresh-token")).willReturn(sessionId);
            given(sessionRepository.findById(sessionId))
                    .willReturn(Optional.of(testSession));
            given(jwtService.generateAccessToken(eq(testUser), eq(sessionId))).willReturn(newAccessToken);

            // When
            LoginResponse result = authService.refreshToken(request);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getAccessToken()).isEqualTo(newAccessToken);
            assertThat(result.getRefreshToken()).isEqualTo("valid-refresh-token");
        }

        @Test
        @DisplayName("Should reject expired refresh token")
        void shouldRejectExpiredRefreshToken() {
            // Given
            RefreshTokenRequest request = new RefreshTokenRequest();
            request.setRefreshToken("expired-token");

            given(jwtService.validateRefreshToken("expired-token")).willReturn(null);

            // When/Then
            assertThatThrownBy(() -> authService.refreshToken(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("hết hạn");
        }

        @Test
        @DisplayName("Should reject invalid refresh token")
        void shouldRejectInvalidRefreshToken() {
            // Given
            RefreshTokenRequest request = new RefreshTokenRequest();
            request.setRefreshToken("invalid-token");

            given(jwtService.validateRefreshToken("invalid-token")).willReturn(null);

            // When/Then
            assertThatThrownBy(() -> authService.refreshToken(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Refresh token");
        }
    }

    // =========================================================================
    // TC_AUTH_LG_003 – TC_AUTH_LG_007 : Login account-state tests
    // =========================================================================

    @Nested
    @DisplayName("Login — Account State (TC_AUTH_LG_003 / 004 / 005 / 007)")
    class LoginAccountStateTests {

        /**
         * TC_AUTH_LG_003
         * Non-existent email must return the SAME errorCode/message as wrong password
         * so that attackers cannot enumerate valid email addresses.
         */
        @Test
        @DisplayName("TC_AUTH_LG_003 — Non-existent email returns IDENTICAL error to wrong password (no email enumeration)")
        void emailNotFoundReturnsSameErrorAsWrongPassword() {
            LoginRequest request = new LoginRequest();
            request.setEmail("ghost@test.com");
            request.setPassword("ValidPass@1");
            request.setDeviceInfo(deviceInfo);

            given(userRepository.findByEmail("ghost@test.com")).willReturn(Optional.empty());

            BusinessException ex = catchThrowableOfType(
                    () -> authService.login(request, "127.0.0.1", "Chrome"),
                    BusinessException.class);

            assertThat(ex.getErrorCode()).isEqualTo("INVALID_CREDENTIALS");
            assertThat(ex.getMessage()).isEqualTo("Sai email hoặc mật khẩu. Hãy thử lại");
            // The message must be identical to the wrong-password case (TC_AUTH_LG_002)
        }

        /**
         * TC_AUTH_LG_004
         * SUSPENDED account must throw ACCOUNT_SUSPENDED.
         *
         * NOTE FOR DEV: The service correctly throws
         * BusinessException("ACCOUNT_SUSPENDED", ...).
         * However, GlobalExceptionHandler maps ALL BusinessException to HTTP 400.
         * The test plan requires HTTP 403 Forbidden.
         * Fix: add ACCOUNT_SUSPENDED → 403 mapping in UserExceptionHandler.
         */
        @Test
        @DisplayName("TC_AUTH_LG_004 — SUSPENDED account → errorCode ACCOUNT_SUSPENDED")
        void suspendedAccountThrowsAccountSuspended() {
            testUser.setStatus(UserStatus.SUSPENDED);
            LoginRequest request = new LoginRequest();
            request.setEmail("test@example.com");
            request.setPassword("ValidPass@1");
            request.setDeviceInfo(deviceInfo);

            given(userRepository.findByEmail("test@example.com")).willReturn(Optional.of(testUser));

            BusinessException ex = catchThrowableOfType(
                    () -> authService.login(request, "127.0.0.1", "Chrome"),
                    BusinessException.class);
            assertThat(ex.getErrorCode()).isEqualTo("ACCOUNT_SUSPENDED");
            // NOTE FOR DEV: UserExceptionHandler must map ACCOUNT_SUSPENDED → HTTP 403.
        }

        /**
         * TC_AUTH_LG_005
         * Soft-deleted account must throw ACCOUNT_DELETED.
         *
         * NOTE FOR DEV: same HTTP status issue as TC_AUTH_LG_004 → needs HTTP 403.
         */
        @Test
        @DisplayName("TC_AUTH_LG_005 — Soft-deleted account (isDeleted=true) → errorCode ACCOUNT_DELETED")
        void deletedAccountThrowsAccountDeleted() {
            // Simulate soft-delete: non-null deletedAt makes isDeleted() return true
            ReflectionTestUtils.setField(testUser, "deletedAt", LocalDateTime.now().minusDays(1));
            LoginRequest request = new LoginRequest();
            request.setEmail("test@example.com");
            request.setPassword("ValidPass@1");
            request.setDeviceInfo(deviceInfo);

            given(userRepository.findByEmail("test@example.com")).willReturn(Optional.of(testUser));

            BusinessException ex = catchThrowableOfType(
                    () -> authService.login(request, "127.0.0.1", "Chrome"),
                    BusinessException.class);
            assertThat(ex.getErrorCode()).isEqualTo("ACCOUNT_DELETED");
            // NOTE FOR DEV: UserExceptionHandler must map ACCOUNT_DELETED → HTTP 403.
        }

        /**
         * TC_AUTH_LG_007
         * When lockedUntil is in the past the lock is expired:
         * login must succeed and failedLoginAttempts/lockedUntil must reset.
         */
        @Test
        @DisplayName("TC_AUTH_LG_007 — Expired lock: login succeeds, failedLoginAttempts reset to 0, lockedUntil = null")
        void expiredLockAllowsLoginAndResetsCounter() {
            testUser.setFailedLoginAttempts(5);
            testUser.setLockedUntil(LocalDateTime.now().minusSeconds(1)); // lock expired

            LoginRequest request = new LoginRequest();
            request.setEmail("test@example.com");
            request.setPassword("ValidPass@1");
            request.setDeviceInfo(deviceInfo);

            given(userRepository.findByEmail("test@example.com")).willReturn(Optional.of(testUser));
            given(passwordEncoder.matches("ValidPass@1", testUser.getPasswordHash())).willReturn(true);
            given(jwtService.generateAccessToken(eq(testUser), any(UUID.class))).willReturn("access-token");
            given(jwtService.generateRefreshToken(any(UUID.class))).willReturn("refresh-token");
            given(sessionRepository.save(any(UserSession.class))).willReturn(testSession);

            LoginResponse result = authService.login(request, "127.0.0.1", "Chrome");

            assertThat(result.getAccessToken()).isNotNull();
            verify(userRepository).save(userCaptor.capture());
            User saved = userCaptor.getValue();
            assertThat(saved.getFailedLoginAttempts()).isEqualTo(0);
            assertThat(saved.getLockedUntil()).isNull();
        }
    }

    // =========================================================================
    // TC_AUTH_LG_009 / TC_AUTH_LG_010 : Refresh token lifecycle
    // =========================================================================

    @Nested
    @DisplayName("Refresh Token Lifecycle (TC_AUTH_LG_009 / 010)")
    class RefreshTokenLifecycleTests {

        /**
         * TC_AUTH_LG_009
         * Refresh produces a NEW access token while the refresh token stays the same.
         */
        @Test
        @DisplayName("TC_AUTH_LG_009 — Valid refresh: new accessToken issued, same refreshToken returned (no rotation)")
        void refreshIssuesNewAccessTokenKeepingSameRefreshToken() {
            String refreshToken = "original-refresh-token";
            String oldAccessToken = "old-access-token";
            String newAccessToken = "new-access-token";
            UUID sessionId = (UUID) ReflectionTestUtils.getField(testSession, "id");

            RefreshTokenRequest request = new RefreshTokenRequest();
            request.setRefreshToken(refreshToken);

            given(jwtService.validateRefreshToken(refreshToken)).willReturn(sessionId);
            given(sessionRepository.findById(sessionId)).willReturn(Optional.of(testSession));
            given(jwtService.generateAccessToken(eq(testUser), eq(sessionId))).willReturn(newAccessToken);
            given(jwtService.getAccessTokenExpirationSeconds()).willReturn(1800L);

            LoginResponse result = authService.refreshToken(request);

            assertThat(result.getAccessToken()).isEqualTo(newAccessToken);
            assertThat(result.getAccessToken()).isNotEqualTo(oldAccessToken);
            assertThat(result.getRefreshToken()).isEqualTo(refreshToken); // unchanged
            assertThat(result.getExpiresIn()).isEqualTo(1800L);
        }

        /**
         * TC_AUTH_LG_010 — KNOWN BUG (by design)
         * Refresh token reuse: the same refresh token is accepted on every call
         * because no rotation / invalidation is implemented.
         *
         * NOTE FOR DEV: This is a documented security gap. The same refresh token can
         * be
         * reused unlimited times until it expires. If the token is compromised an
         * attacker
         * can continuously obtain valid access tokens.
         * Recommendation: implement token rotation — invalidate refresh token after
         * each use.
         */
        @Test
        @DisplayName("TC_AUTH_LG_010 — KNOWN BUG: refresh token reuse allowed (no rotation)")
        void refreshTokenReuseIsAllowedByDesign() {
            String refreshToken = "stable-refresh-token";
            UUID sessionId = (UUID) ReflectionTestUtils.getField(testSession, "id");

            RefreshTokenRequest request = new RefreshTokenRequest();
            request.setRefreshToken(refreshToken);

            given(jwtService.validateRefreshToken(refreshToken)).willReturn(sessionId);
            given(sessionRepository.findById(sessionId)).willReturn(Optional.of(testSession));
            given(jwtService.generateAccessToken(eq(testUser), eq(sessionId)))
                    .willReturn("access-1", "access-2");

            LoginResponse firstUse = authService.refreshToken(request);
            LoginResponse secondUse = authService.refreshToken(request);

            // Both calls succeed — same refresh token accepted twice
            assertThat(firstUse.getAccessToken()).isNotNull();
            assertThat(secondUse.getAccessToken()).isNotNull();
            // Same refresh token is returned both times (no rotation)
            assertThat(firstUse.getRefreshToken()).isEqualTo(refreshToken);
            assertThat(secondUse.getRefreshToken()).isEqualTo(refreshToken);
        }
    }

    // =========================================================================
    // TC_AUTH_FP_006, TC_AUTH_FP_008, TC_AUTH_FP_009 : Forgot-password extras
    // =========================================================================

    @Nested
    @DisplayName("Forgot Password — Additional (TC_AUTH_FP_006 / 008 / 009)")
    class ForgotPasswordAdditionalTests {

        /**
         * TC_AUTH_FP_006
         * Reset token is single-use: after a successful reset the token is cleared,
         * so a second attempt with the same token must fail with INVALID_TOKEN.
         */
        @Test
        @DisplayName("TC_AUTH_FP_006 — Reset token single-use: reuse after successful reset → INVALID_TOKEN")
        void resetTokenIsSingleUse() {
            String resetToken = UUID.randomUUID().toString();
            testUser.setPasswordResetToken(resetToken);
            testUser.setPasswordResetTokenExpiresAt(LocalDateTime.now().plusMinutes(5));

            ResetPasswordRequest firstUse = new ResetPasswordRequest();
            firstUse.setResetToken(resetToken);
            firstUse.setNewPassword("NewPass@1");
            firstUse.setConfirmPassword("NewPass@1");

            // First use: simulate that save() clears the token (as the real impl does)
            given(userRepository.findByPasswordResetToken(resetToken)).willReturn(Optional.of(testUser));
            given(passwordEncoder.encode("NewPass@1")).willReturn("hashed-new");
            given(userRepository.save(any(User.class))).willAnswer(inv -> {
                testUser.setPasswordResetToken(null);
                testUser.setPasswordResetTokenExpiresAt(null);
                return testUser;
            });
            doNothing().when(emailService).sendPasswordChangedNotification(anyString(), anyString());

            assertThatNoException().isThrownBy(() -> authService.resetPassword(firstUse));

            // Second use: token is now null in DB → lookup returns empty
            given(userRepository.findByPasswordResetToken(resetToken)).willReturn(Optional.empty());

            ResetPasswordRequest secondUse = new ResetPasswordRequest();
            secondUse.setResetToken(resetToken);
            secondUse.setNewPassword("AnotherPass@2");
            secondUse.setConfirmPassword("AnotherPass@2");

            BusinessException ex = catchThrowableOfType(
                    () -> authService.resetPassword(secondUse),
                    BusinessException.class);
            assertThat(ex.getErrorCode()).isEqualTo("INVALID_TOKEN");
        }

        /**
         * TC_AUTH_FP_008
         * confirmPassword != newPassword on reset → PASSWORD_MISMATCH.
         */
        @Test
        @DisplayName("TC_AUTH_FP_008 — confirmPassword ≠ newPassword on reset-password → PASSWORD_MISMATCH")
        void resetPasswordConfirmMismatch() {
            ResetPasswordRequest request = new ResetPasswordRequest();
            request.setResetToken(UUID.randomUUID().toString());
            request.setNewPassword("NewPass@1");
            request.setConfirmPassword("NewPass@2");

            BusinessException ex = catchThrowableOfType(
                    () -> authService.resetPassword(request),
                    BusinessException.class);
            assertThat(ex.getErrorCode()).isEqualTo("PASSWORD_MISMATCH");
        }

        /**
         * TC_AUTH_FP_009 — KNOWN BUG
         * No OTP attempt limit: repeated wrong OTPs never block the user.
         * Correct OTP still works after multiple failures.
         *
         * NOTE FOR DEV: An attacker can brute-force all 10^6 OTP combinations within
         * the OTP validity window (currently 1 minute per config).
         * Recommendation: limit to 5 wrong attempts then invalidate the OTP.
         */
        @Test
        @DisplayName("TC_AUTH_FP_009 — KNOWN BUG: 3 wrong OTPs cause no lockout — correct OTP still works")
        void otpHasNoAttemptLimit() {
            testUser.setPasswordResetToken("654321");
            testUser.setPasswordResetTokenExpiresAt(LocalDateTime.now().plusMinutes(10));
            given(userRepository.findActiveByEmail("test@example.com")).willReturn(Optional.of(testUser));

            // Three consecutive wrong OTPs — every one throws INVALID_OTP, user never
            // locked
            for (int attempt = 0; attempt < 3; attempt++) {
                VerifyOtpRequest wrongReq = new VerifyOtpRequest();
                wrongReq.setEmail("test@example.com");
                wrongReq.setOtp("000000");

                BusinessException ex = catchThrowableOfType(
                        () -> authService.verifyOtp(wrongReq),
                        BusinessException.class);
                assertThat(ex.getErrorCode()).isEqualTo("INVALID_OTP");
            }

            // Correct OTP still accepted after the failed attempts
            given(userRepository.save(any(User.class))).willReturn(testUser);
            VerifyOtpRequest correctReq = new VerifyOtpRequest();
            correctReq.setEmail("test@example.com");
            correctReq.setOtp("654321");

            VerifyOtpResponse response = authService.verifyOtp(correctReq);
            assertThat(response.getResetToken()).isNotNull();
        }
    }

    // =========================================================================
    // TC_AUTH_CP_002, TC_AUTH_CP_004, TC_AUTH_CP_005 : Change-password extras
    // =========================================================================

    @Nested
    @DisplayName("Change Password — Additional (TC_AUTH_CP_002 / 004 / 005)")
    class ChangePasswordAdditionalTests {

        /**
         * TC_AUTH_CP_002
         * logoutOtherDevices=true must invoke revokeOtherSessionsByUserId
         * so that all sessions except the current one are revoked.
         */
        @Test
        @DisplayName("TC_AUTH_CP_002 — logoutOtherDevices=true invokes revokeOtherSessionsByUserId (BR-63)")
        void logoutOtherDevicesRevokesOtherSessions() {
            UUID currentSessionId = UUID.randomUUID();

            ChangePasswordRequest request = new ChangePasswordRequest();
            request.setCurrentPassword("OldPass@1");
            request.setNewPassword("NewPass@1");
            request.setConfirmPassword("NewPass@1");
            request.setLogoutOtherDevices(true);

            given(userRepository.findById(userId)).willReturn(Optional.of(testUser));
            given(passwordEncoder.matches("OldPass@1", testUser.getPasswordHash())).willReturn(true);
            given(passwordEncoder.encode("NewPass@1")).willReturn("hashed-new");
            given(userRepository.save(any(User.class))).willReturn(testUser);
            doNothing().when(emailService).sendPasswordChangedNotification(anyString(), anyString());

            authService.changePassword(userId, request, currentSessionId);

            verify(sessionRepository).revokeOtherSessionsByUserId(
                    eq(userId), eq(currentSessionId), any(LocalDateTime.class));
        }

        /**
         * TC_AUTH_CP_004 — KNOWN BUG
         * BR-59 requires rejecting a new password that is identical to the current one
         * with message "Mật khẩu mới không được trùng với mật khẩu cũ" (MSG16).
         *
         * NOTE FOR DEV: AuthServiceImpl does NOT implement this check.
         * The call below succeeds — it should throw BusinessException("SAME_PASSWORD",
         * "...MSG16...").
         * Fix: before encoding, add:
         * if (passwordEncoder.matches(request.getNewPassword(),
         * user.getPasswordHash()))
         * throw new BusinessException("SAME_PASSWORD", "Mật khẩu mới không được trùng
         * với mật khẩu cũ");
         *
         * This test documents the current (broken) behaviour and will need updating
         * after the fix.
         */
        @Test
        @DisplayName("TC_AUTH_CP_004 — KNOWN BUG: same-as-current password NOT rejected (BR-59 missing)")
        void samePasswordNotRejected_BR59NotImplemented() {
            ChangePasswordRequest request = new ChangePasswordRequest();
            request.setCurrentPassword("OldPass@1");
            request.setNewPassword("OldPass@1"); // same as current
            request.setConfirmPassword("OldPass@1");

            given(userRepository.findById(userId)).willReturn(Optional.of(testUser));
            given(passwordEncoder.matches("OldPass@1", testUser.getPasswordHash())).willReturn(true);
            given(passwordEncoder.encode("OldPass@1")).willReturn("hashed-same");
            given(userRepository.save(any(User.class))).willReturn(testUser);
            doNothing().when(emailService).sendPasswordChangedNotification(anyString(), anyString());

            // Current behaviour: no exception thrown (BR-59 not enforced)
            assertThatNoException()
                    .as("KNOWN BUG — BR-59 not enforced: same password should be rejected")
                    .isThrownBy(() -> authService.changePassword(userId, request, UUID.randomUUID()));
        }

        /**
         * TC_AUTH_CP_005
         * confirmPassword != newPassword on change-password → PASSWORD_MISMATCH.
         */
        @Test
        @DisplayName("TC_AUTH_CP_005 — confirmPassword ≠ newPassword on change-password → PASSWORD_MISMATCH")
        void changePasswordConfirmMismatch() {
            ChangePasswordRequest request = new ChangePasswordRequest();
            request.setCurrentPassword("OldPass@1");
            request.setNewPassword("NewPass@1");
            request.setConfirmPassword("NewPass@2"); // mismatch

            given(userRepository.findById(userId)).willReturn(Optional.of(testUser));

            BusinessException ex = catchThrowableOfType(
                    () -> authService.changePassword(userId, request, UUID.randomUUID()),
                    BusinessException.class);
            assertThat(ex.getErrorCode()).isEqualTo("PASSWORD_MISMATCH");
            assertThat(ex.getMessage()).contains("Mật khẩu");
        }
    }

    // =========================================================================
    // TC_AUTH_LO_004 : Logout cross-user token
    // =========================================================================

    @Nested
    @DisplayName("Logout — Cross-User Token (TC_AUTH_LO_004)")
    class LogoutCrossUserTests {

        /**
         * TC_AUTH_LO_004
         * UserA must not be able to revoke UserB's session by supplying userB's refresh
         * token
         * in their own logout request.
         * The service filters sessions by userId, so userB's session is silently
         * ignored.
         */
        @Test
        @DisplayName("TC_AUTH_LO_004 — Logout with another user's refresh token does NOT revoke that session")
        void logoutWithOtherUserTokenDoesNotRevokeIt() {
            // Build userB with a different UUID
            User userB = User.builder()
                    .email("userb@test.com")
                    .passwordHash("hashB")
                    .role(UserRole.LEARNER)
                    .status(UserStatus.ACTIVE)
                    .authProvider(AuthProvider.LOCAL)
                    .failedLoginAttempts(0)
                    .build();
            UUID userBId = UUID.randomUUID();
            ReflectionTestUtils.setField(userB, "id", userBId);

            UserSession userBSession = UserSession.builder()
                    .user(userB)
                    .refreshToken("tokenB")
                    .ipAddress("127.0.0.1")
                    .expiresAt(LocalDateTime.now().plusDays(7))
                    .build();
            UUID userBSessionId = UUID.randomUUID();
            ReflectionTestUtils.setField(userBSession, "id", userBSessionId);

            // UserA's logout request carries userB's refresh token
            LogoutRequest request = LogoutRequest.builder()
                    .refreshToken("refresh-token-of-user-b")
                    .logoutAll(false)
                    .build();

            given(jwtService.validateRefreshToken("refresh-token-of-user-b")).willReturn(userBSessionId);
            // The session belongs to userB — the filter
            // `s.getUser().getId().equals(userId)` will reject it
            given(sessionRepository.findById(userBSessionId)).willReturn(Optional.of(userBSession));

            // UserA (userId) calls logout
            authService.logout(userId, request);

            // UserB's session must remain intact
            assertThat(userBSession.getRevokedAt())
                    .as("UserB's session must NOT be revoked by UserA's logout")
                    .isNull();
        }
    }
}

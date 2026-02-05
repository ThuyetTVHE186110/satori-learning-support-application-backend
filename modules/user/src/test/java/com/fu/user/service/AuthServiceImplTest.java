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
            assertThatThrownBy(() -> 
                authService.login(request, "127.0.0.1", "Chrome")
            )
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
            assertThatThrownBy(() -> 
                authService.login(request, "127.0.0.1", "Chrome")
            )
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
            assertThatThrownBy(() -> 
                authService.login(request, "127.0.0.1", "Chrome")
            )
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
            assertThatThrownBy(() -> 
                authService.login(request, "127.0.0.1", "Chrome")
            )
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
                    eq(10)
            );
        }

        @Test
        @DisplayName("Should not reveal non-existent email (security)")
        void shouldNotRevealNonExistentEmail() {
            // Given
            ForgotPasswordRequest request = new ForgotPasswordRequest();
            request.setEmail("nonexistent@example.com");
            
            given(userRepository.findActiveByEmail(request.getEmail())).willReturn(Optional.empty());
            
            // When/Then - Should not throw exception (security best practice)
            assertThatNoException().isThrownBy(() -> 
                authService.forgotPassword(request)
            );
            
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
            assertThatNoException().isThrownBy(() -> 
                authService.forgotPassword(request)
            );
            
            verify(emailService, never()).sendPasswordResetOtp(anyString(), anyString(), anyString(), anyInt());
        }
    }

    @Nested
    @DisplayName("UC-08: Reset Password Tests")
    class ResetPasswordTests {

        @Test
        @DisplayName("Should reset password successfully with valid OTP")
        void shouldResetPasswordSuccessfully() {
            // Given
            testUser.setPasswordResetToken("123456");
            testUser.setPasswordResetTokenExpiresAt(LocalDateTime.now().plusMinutes(10));
            
            ResetPasswordRequest request = new ResetPasswordRequest();
            request.setEmail("test@example.com");
            request.setOtp("123456");
            request.setNewPassword("NewPassword123!");
            request.setConfirmPassword("NewPassword123!");
            
            given(userRepository.findActiveByEmail(request.getEmail())).willReturn(Optional.of(testUser));
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
                    anyString()
            );
        }

        @Test
        @DisplayName("Should reject invalid OTP")
        void shouldRejectInvalidOtp() {
            // Given
            testUser.setPasswordResetToken("123456");
            testUser.setPasswordResetTokenExpiresAt(LocalDateTime.now().plusMinutes(10));
            
            ResetPasswordRequest request = new ResetPasswordRequest();
            request.setEmail("test@example.com");
            request.setOtp("999999");
            request.setNewPassword("NewPassword123!");
            request.setConfirmPassword("NewPassword123!");
            
            given(userRepository.findActiveByEmail(request.getEmail())).willReturn(Optional.of(testUser));
            
            // When/Then
            assertThatThrownBy(() -> 
                authService.resetPassword(request)
            )
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("OTP");
        }

        @Test
        @DisplayName("Should reject expired OTP")
        void shouldRejectExpiredOtp() {
            // Given
            testUser.setPasswordResetToken("123456");
            testUser.setPasswordResetTokenExpiresAt(LocalDateTime.now().minusMinutes(1)); // Expired
            
            ResetPasswordRequest request = new ResetPasswordRequest();
            request.setEmail("test@example.com");
            request.setOtp("123456");
            request.setNewPassword("NewPassword123!");
            request.setConfirmPassword("NewPassword123!");
            
            given(userRepository.findActiveByEmail(request.getEmail())).willReturn(Optional.of(testUser));
            
            // When/Then
            assertThatThrownBy(() -> 
                authService.resetPassword(request)
            )
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
                    anyString()
            );
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
            assertThatThrownBy(() -> 
                authService.changePassword(userId, request, currentSessionId)
            )
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
            assertThatThrownBy(() -> 
                authService.changePassword(userId, request, currentSessionId)
            )
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
            assertThatThrownBy(() -> 
                authService.loginWithGoogle(request, "127.0.0.1", "Chrome")
            )
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
            assertThatThrownBy(() -> 
                authService.loginWithGoogle(request, "127.0.0.1", "Chrome")
            )
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
            assertThatThrownBy(() -> 
                authService.refreshToken(request)
            )
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
            assertThatThrownBy(() -> 
                authService.refreshToken(request)
            )
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Refresh token");
        }
    }
}

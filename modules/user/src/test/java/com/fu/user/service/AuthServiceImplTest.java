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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AuthServiceImpl.
 * Tests authentication use cases: UC-06, UC-07, UC-08, UC-09, UC-10.
 * 
 * <h2>Use Cases Covered:</h2>
 * <ul>
 *   <li>UC-06: Login by Email</li>
 *   <li>UC-07: Forgot Password (OTP 6 digits, 2 minutes)</li>
 *   <li>UC-08: Change Password</li>
 *   <li>UC-09: Login with Google</li>
 *   <li>UC-10: Logout</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService Unit Tests")
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
    private Optional<FirebaseAuth> firebaseAuth;
    
    @InjectMocks
    private AuthServiceImpl authService;

    @Captor
    private ArgumentCaptor<User> userCaptor;

    @Captor
    private ArgumentCaptor<UserSession> sessionCaptor;

    private User testUser;
    private UUID userId;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        
        testUser = User.builder()
                .email("test@example.com")
                .passwordHash("hashedPassword")
                .firstName("Test")
                .lastName("User")
                .displayName("Test User")
                .role(UserRole.LEARNER)
                .status(UserStatus.ACTIVE)
                .authProvider(AuthProvider.LOCAL)
                .failedLoginAttempts(0)
                .build();
        ReflectionTestUtils.setField(testUser, "id", userId);
        
        // Set configuration values
        ReflectionTestUtils.setField(authService, "maxFailedAttempts", 5);
        ReflectionTestUtils.setField(authService, "lockDurationMinutes", 30);
        ReflectionTestUtils.setField(authService, "otpExpirationMinutes", 2);
    }

    // ==================== UC-06: Login by Email Tests ====================
    @Nested
    @DisplayName("UC-06: Login by Email")
    class LoginByEmailTests {

        @Test
        @DisplayName("Should login successfully with valid credentials")
        void login_validCredentials_returnsLoginResponse() {
            // Given
            LoginRequest request = LoginRequest.builder()
                    .email("test@example.com")
                    .password("password123")
                    .build();
            
            given(userRepository.findByEmail("test@example.com")).willReturn(Optional.of(testUser));
            given(passwordEncoder.matches("password123", "hashedPassword")).willReturn(true);
            given(sessionRepository.save(any(UserSession.class))).willAnswer(invocation -> {
                UserSession session = invocation.getArgument(0);
                ReflectionTestUtils.setField(session, "id", sessionId);
                return session;
            });
            given(jwtService.generateAccessToken(any(User.class), any(UUID.class))).willReturn("accessToken");
            given(jwtService.generateRefreshToken(any(UUID.class))).willReturn("refreshToken");
            given(jwtService.getAccessTokenExpirationSeconds()).willReturn(1800L);
            given(jwtService.getRefreshTokenExpirationMs()).willReturn(604800000L);

            // When
            LoginResponse response = authService.login(request, "127.0.0.1", "Mozilla/5.0");

            // Then
            assertThat(response).isNotNull();
            assertThat(response.getAccessToken()).isEqualTo("accessToken");
            assertThat(response.getRefreshToken()).isEqualTo("refreshToken");
            assertThat(response.getTokenType()).isEqualTo("Bearer");
            assertThat(response.getUser().getEmail()).isEqualTo("test@example.com");
            
            verify(userRepository).save(userCaptor.capture());
            assertThat(userCaptor.getValue().getLastLoginAt()).isNotNull();
            assertThat(userCaptor.getValue().getFailedLoginAttempts()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should throw exception for non-existent email")
        void login_nonExistentEmail_throwsBusinessException() {
            // Given
            LoginRequest request = LoginRequest.builder()
                    .email("nonexistent@example.com")
                    .password("password123")
                    .build();
            
            given(userRepository.findByEmail("nonexistent@example.com")).willReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> authService.login(request, "127.0.0.1", "Mozilla/5.0"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "INVALID_CREDENTIALS")
                    .hasMessageContaining("Sai email hoặc mật khẩu");
            
            verify(passwordEncoder, never()).matches(anyString(), anyString());
        }

        @Test
        @DisplayName("A6.3: Should throw exception and increment failed attempts for wrong password")
        void login_wrongPassword_throwsExceptionAndIncrementsFailedAttempts() {
            // Given
            LoginRequest request = LoginRequest.builder()
                    .email("test@example.com")
                    .password("wrongPassword")
                    .build();
            
            given(userRepository.findByEmail("test@example.com")).willReturn(Optional.of(testUser));
            given(passwordEncoder.matches("wrongPassword", "hashedPassword")).willReturn(false);

            // When/Then
            assertThatThrownBy(() -> authService.login(request, "127.0.0.1", "Mozilla/5.0"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "INVALID_CREDENTIALS");
            
            verify(userRepository).save(userCaptor.capture());
            assertThat(userCaptor.getValue().getFailedLoginAttempts()).isEqualTo(1);
        }

        @Test
        @DisplayName("E6.4: Should throw exception for suspended account")
        void login_suspendedAccount_throwsBusinessException() {
            // Given
            testUser.setStatus(UserStatus.SUSPENDED);
            LoginRequest request = LoginRequest.builder()
                    .email("test@example.com")
                    .password("password123")
                    .build();
            
            given(userRepository.findByEmail("test@example.com")).willReturn(Optional.of(testUser));

            // When/Then
            assertThatThrownBy(() -> authService.login(request, "127.0.0.1", "Mozilla/5.0"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "ACCOUNT_SUSPENDED")
                    .hasMessageContaining("bị chặn");
        }

        @Test
        @DisplayName("Should throw exception for deleted account")
        void login_deletedAccount_throwsBusinessException() {
            // Given
            testUser.setDeletedAt(LocalDateTime.now());
            LoginRequest request = LoginRequest.builder()
                    .email("test@example.com")
                    .password("password123")
                    .build();
            
            given(userRepository.findByEmail("test@example.com")).willReturn(Optional.of(testUser));

            // When/Then
            assertThatThrownBy(() -> authService.login(request, "127.0.0.1", "Mozilla/5.0"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "ACCOUNT_DELETED");
        }

        @Test
        @DisplayName("Should throw exception for locked account")
        void login_lockedAccount_throwsBusinessException() {
            // Given
            testUser.setLockedUntil(LocalDateTime.now().plusMinutes(15));
            LoginRequest request = LoginRequest.builder()
                    .email("test@example.com")
                    .password("password123")
                    .build();
            
            given(userRepository.findByEmail("test@example.com")).willReturn(Optional.of(testUser));

            // When/Then
            assertThatThrownBy(() -> authService.login(request, "127.0.0.1", "Mozilla/5.0"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "ACCOUNT_LOCKED");
        }

        @Test
        @DisplayName("Should lock account after max failed attempts")
        void login_maxFailedAttempts_locksAccount() {
            // Given
            testUser.setFailedLoginAttempts(4); // One more will lock
            LoginRequest request = LoginRequest.builder()
                    .email("test@example.com")
                    .password("wrongPassword")
                    .build();
            
            given(userRepository.findByEmail("test@example.com")).willReturn(Optional.of(testUser));
            given(passwordEncoder.matches("wrongPassword", "hashedPassword")).willReturn(false);

            // When/Then
            assertThatThrownBy(() -> authService.login(request, "127.0.0.1", "Mozilla/5.0"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "INVALID_CREDENTIALS");
            
            verify(userRepository).save(userCaptor.capture());
            User savedUser = userCaptor.getValue();
            assertThat(savedUser.getFailedLoginAttempts()).isEqualTo(5);
            assertThat(savedUser.getLockedUntil()).isAfter(LocalDateTime.now());
        }

        @Test
        @DisplayName("Should allow login when lock period has expired")
        void login_expiredLock_allowsLogin() {
            // Given
            testUser.setLockedUntil(LocalDateTime.now().minusMinutes(5)); // Expired lock
            testUser.setFailedLoginAttempts(5);
            LoginRequest request = LoginRequest.builder()
                    .email("test@example.com")
                    .password("password123")
                    .build();
            
            given(userRepository.findByEmail("test@example.com")).willReturn(Optional.of(testUser));
            given(passwordEncoder.matches("password123", "hashedPassword")).willReturn(true);
            given(sessionRepository.save(any(UserSession.class))).willAnswer(invocation -> {
                UserSession session = invocation.getArgument(0);
                ReflectionTestUtils.setField(session, "id", sessionId);
                return session;
            });
            given(jwtService.generateAccessToken(any(User.class), any(UUID.class))).willReturn("accessToken");
            given(jwtService.generateRefreshToken(any(UUID.class))).willReturn("refreshToken");
            given(jwtService.getAccessTokenExpirationSeconds()).willReturn(1800L);
            given(jwtService.getRefreshTokenExpirationMs()).willReturn(604800000L);

            // When
            LoginResponse response = authService.login(request, "127.0.0.1", "Mozilla/5.0");

            // Then
            assertThat(response).isNotNull();
            verify(userRepository).save(userCaptor.capture());
            assertThat(userCaptor.getValue().getFailedLoginAttempts()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should throw exception for INACTIVE account")
        void login_inactiveAccount_throwsBusinessException() {
            // Given
            testUser.setStatus(UserStatus.INACTIVE);
            LoginRequest request = LoginRequest.builder()
                    .email("test@example.com")
                    .password("password123")
                    .build();
            
            given(userRepository.findByEmail("test@example.com")).willReturn(Optional.of(testUser));

            // When/Then - INACTIVE should be treated same as suspended/deleted
            assertThatThrownBy(() -> authService.login(request, "127.0.0.1", "Mozilla/5.0"))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ==================== UC-07: Forgot Password Tests ====================
    @Nested
    @DisplayName("UC-07: Forgot Password")
    class ForgotPasswordTests {

        @Test
        @DisplayName("Should send 6-digit OTP email (valid for 2 minutes)")
        void forgotPassword_validEmail_sends6DigitOtp() {
            // Given
            ForgotPasswordRequest request = new ForgotPasswordRequest();
            request.setEmail("test@example.com");
            
            given(userRepository.findActiveByEmail("test@example.com")).willReturn(Optional.of(testUser));

            // When
            authService.forgotPassword(request);

            // Then
            verify(userRepository).save(userCaptor.capture());
            User savedUser = userCaptor.getValue();
            assertThat(savedUser.getPasswordResetToken()).isNotNull();
            assertThat(savedUser.getPasswordResetToken()).hasSize(6); // 6-digit OTP
            assertThat(savedUser.getPasswordResetToken()).matches("\\d{6}"); // Only digits
            assertThat(savedUser.getPasswordResetTokenExpiresAt()).isAfter(LocalDateTime.now());
            
            verify(emailService).sendPasswordResetOtp(
                    eq("test@example.com"),
                    eq("Test User"),
                    anyString(),
                    eq(2) // OTP expiration = 2 minutes as per business requirement
            );
        }

        @Test
        @DisplayName("Should not reveal if email does not exist (security)")
        void forgotPassword_nonExistentEmail_silentlyIgnores() {
            // Given
            ForgotPasswordRequest request = new ForgotPasswordRequest();
            request.setEmail("nonexistent@example.com");
            
            given(userRepository.findActiveByEmail("nonexistent@example.com")).willReturn(Optional.empty());

            // When
            authService.forgotPassword(request);

            // Then - no exception, no email sent
            verify(userRepository, never()).save(any());
            verify(emailService, never()).sendPasswordResetOtp(anyString(), anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("Should not send OTP for Google-only account")
        void forgotPassword_googleOnlyAccount_silentlyIgnores() {
            // Given
            testUser.setAuthProvider(AuthProvider.GOOGLE);
            testUser.setPasswordHash(null);
            ForgotPasswordRequest request = new ForgotPasswordRequest();
            request.setEmail("test@example.com");
            
            given(userRepository.findActiveByEmail("test@example.com")).willReturn(Optional.of(testUser));

            // When
            authService.forgotPassword(request);

            // Then
            verify(userRepository, never()).save(any());
            verify(emailService, never()).sendPasswordResetOtp(anyString(), anyString(), anyString(), anyInt());
        }
    }

    // ==================== Reset Password Tests ====================
    @Nested
    @DisplayName("UC-07: Reset Password with OTP")
    class ResetPasswordTests {

        @Test
        @DisplayName("Should reset password with valid OTP and send confirmation email")
        void resetPassword_validOtp_resetsPasswordAndSendsEmail() {
            // Given
            testUser.setPasswordResetToken("123456");
            testUser.setPasswordResetTokenExpiresAt(LocalDateTime.now().plusMinutes(1));
            
            ResetPasswordRequest request = ResetPasswordRequest.builder()
                    .email("test@example.com")
                    .otp("123456")
                    .newPassword("newPassword123")
                    .confirmPassword("newPassword123")
                    .build();
            
            given(userRepository.findActiveByEmail("test@example.com")).willReturn(Optional.of(testUser));
            given(passwordEncoder.encode("newPassword123")).willReturn("newHashedPassword");

            // When
            authService.resetPassword(request);

            // Then
            verify(userRepository).save(userCaptor.capture());
            User savedUser = userCaptor.getValue();
            assertThat(savedUser.getPasswordHash()).isEqualTo("newHashedPassword");
            assertThat(savedUser.getPasswordResetToken()).isNull();
            assertThat(savedUser.getPasswordResetTokenExpiresAt()).isNull();
            assertThat(savedUser.getLastPasswordChangeAt()).isNotNull();
            
            // Verify sessions revoked
            verify(sessionRepository).revokeAllSessionsByUserId(eq(userId), any(LocalDateTime.class));
            
            // Verify confirmation email sent (UC-07 requirement)
            verify(emailService).sendPasswordChangedNotification("test@example.com", "Test User");
        }

        @Test
        @DisplayName("Should throw exception for invalid OTP")
        void resetPassword_invalidOtp_throwsBusinessException() {
            // Given
            testUser.setPasswordResetToken("123456");
            testUser.setPasswordResetTokenExpiresAt(LocalDateTime.now().plusMinutes(1));
            
            ResetPasswordRequest request = ResetPasswordRequest.builder()
                    .email("test@example.com")
                    .otp("654321") // Wrong OTP
                    .newPassword("newPassword123")
                    .confirmPassword("newPassword123")
                    .build();
            
            given(userRepository.findActiveByEmail("test@example.com")).willReturn(Optional.of(testUser));

            // When/Then
            assertThatThrownBy(() -> authService.resetPassword(request))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "INVALID_OTP");
        }

        @Test
        @DisplayName("Should throw exception for expired OTP (after 2 minutes)")
        void resetPassword_expiredOtp_throwsBusinessException() {
            // Given
            testUser.setPasswordResetToken("123456");
            testUser.setPasswordResetTokenExpiresAt(LocalDateTime.now().minusMinutes(1)); // Expired
            
            ResetPasswordRequest request = ResetPasswordRequest.builder()
                    .email("test@example.com")
                    .otp("123456")
                    .newPassword("newPassword123")
                    .confirmPassword("newPassword123")
                    .build();
            
            given(userRepository.findActiveByEmail("test@example.com")).willReturn(Optional.of(testUser));

            // When/Then
            assertThatThrownBy(() -> authService.resetPassword(request))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "OTP_EXPIRED");
        }

        @Test
        @DisplayName("Should throw exception for password mismatch")
        void resetPassword_passwordMismatch_throwsBusinessException() {
            // Given
            ResetPasswordRequest request = ResetPasswordRequest.builder()
                    .email("test@example.com")
                    .otp("123456")
                    .newPassword("newPassword123")
                    .confirmPassword("differentPassword") // Mismatch
                    .build();

            // When/Then
            assertThatThrownBy(() -> authService.resetPassword(request))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "PASSWORD_MISMATCH");
        }
    }

    // ==================== UC-08: Change Password Tests ====================
    @Nested
    @DisplayName("UC-08: Change Password")
    class ChangePasswordTests {

        @Test
        @DisplayName("Should change password and send confirmation email")
        void changePassword_validRequest_changesPasswordAndSendsEmail() {
            // Given
            ChangePasswordRequest request = ChangePasswordRequest.builder()
                    .currentPassword("oldPassword")
                    .newPassword("newPassword123")
                    .confirmPassword("newPassword123")
                    .logoutOtherDevices(false)
                    .build();
            
            given(userRepository.findById(userId)).willReturn(Optional.of(testUser));
            given(passwordEncoder.matches("oldPassword", "hashedPassword")).willReturn(true);
            given(passwordEncoder.encode("newPassword123")).willReturn("newHashedPassword");

            // When
            authService.changePassword(userId, request, sessionId);

            // Then
            verify(userRepository).save(userCaptor.capture());
            User savedUser = userCaptor.getValue();
            assertThat(savedUser.getPasswordHash()).isEqualTo("newHashedPassword");
            assertThat(savedUser.getLastPasswordChangeAt()).isNotNull();
            
            // UC-08: Send confirmation email
            verify(emailService).sendPasswordChangedNotification("test@example.com", "Test User");
        }

        @Test
        @DisplayName("Should logout other devices when requested")
        void changePassword_logoutOtherDevices_revokesOtherSessions() {
            // Given
            ChangePasswordRequest request = ChangePasswordRequest.builder()
                    .currentPassword("oldPassword")
                    .newPassword("newPassword123")
                    .confirmPassword("newPassword123")
                    .logoutOtherDevices(true)
                    .build();
            
            given(userRepository.findById(userId)).willReturn(Optional.of(testUser));
            given(passwordEncoder.matches("oldPassword", "hashedPassword")).willReturn(true);
            given(passwordEncoder.encode("newPassword123")).willReturn("newHashedPassword");

            // When
            authService.changePassword(userId, request, sessionId);

            // Then
            verify(sessionRepository).revokeOtherSessionsByUserId(eq(userId), eq(sessionId), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("Should throw exception for wrong current password")
        void changePassword_wrongCurrentPassword_throwsBusinessException() {
            // Given
            ChangePasswordRequest request = ChangePasswordRequest.builder()
                    .currentPassword("wrongPassword")
                    .newPassword("newPassword123")
                    .confirmPassword("newPassword123")
                    .build();
            
            given(userRepository.findById(userId)).willReturn(Optional.of(testUser));
            given(passwordEncoder.matches("wrongPassword", "hashedPassword")).willReturn(false);

            // When/Then
            assertThatThrownBy(() -> authService.changePassword(userId, request, sessionId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "WRONG_PASSWORD");
        }

        @Test
        @DisplayName("Should throw exception for password mismatch")
        void changePassword_passwordMismatch_throwsBusinessException() {
            // Given
            ChangePasswordRequest request = ChangePasswordRequest.builder()
                    .currentPassword("oldPassword")
                    .newPassword("newPassword123")
                    .confirmPassword("differentPassword")
                    .build();
            
            given(userRepository.findById(userId)).willReturn(Optional.of(testUser));

            // When/Then
            assertThatThrownBy(() -> authService.changePassword(userId, request, sessionId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "PASSWORD_MISMATCH");
        }

        @Test
        @DisplayName("Should throw exception for non-existent user")
        void changePassword_nonExistentUser_throwsResourceNotFoundException() {
            // Given
            ChangePasswordRequest request = ChangePasswordRequest.builder()
                    .currentPassword("oldPassword")
                    .newPassword("newPassword123")
                    .confirmPassword("newPassword123")
                    .build();
            
            given(userRepository.findById(userId)).willReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> authService.changePassword(userId, request, sessionId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ==================== UC-10: Logout Tests ====================
    @Nested
    @DisplayName("UC-10: Logout")
    class LogoutTests {

        @Test
        @DisplayName("Should logout current session")
        void logout_singleSession_revokesSession() {
            // Given
            UserSession session = UserSession.builder()
                    .user(testUser)
                    .build();
            ReflectionTestUtils.setField(session, "id", sessionId);
            
            LogoutRequest request = LogoutRequest.builder()
                    .refreshToken("validRefreshToken")
                    .logoutAll(false)
                    .build();
            
            given(jwtService.validateRefreshToken("validRefreshToken")).willReturn(sessionId);
            given(sessionRepository.findById(sessionId)).willReturn(Optional.of(session));

            // When
            authService.logout(userId, request);

            // Then
            assertThat(session.isActive()).isFalse();
        }

        @Test
        @DisplayName("Should logout all sessions when requested")
        void logout_allSessions_revokesAllSessions() {
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

    // ==================== Token Refresh Tests ====================
    @Nested
    @DisplayName("Token Refresh")
    class TokenRefreshTests {

        @Test
        @DisplayName("Should refresh token successfully")
        void refreshToken_validToken_returnsNewAccessToken() {
            // Given
            UserSession session = UserSession.builder()
                    .user(testUser)
                    .expiresAt(LocalDateTime.now().plusDays(7))
                    .build();
            ReflectionTestUtils.setField(session, "id", sessionId);
            
            RefreshTokenRequest request = RefreshTokenRequest.builder()
                    .refreshToken("validRefreshToken")
                    .build();
            
            given(jwtService.validateRefreshToken("validRefreshToken")).willReturn(sessionId);
            given(sessionRepository.findById(sessionId)).willReturn(Optional.of(session));
            given(jwtService.generateAccessToken(testUser, sessionId)).willReturn("newAccessToken");
            given(jwtService.getAccessTokenExpirationSeconds()).willReturn(1800L);

            // When
            LoginResponse response = authService.refreshToken(request);

            // Then
            assertThat(response.getAccessToken()).isEqualTo("newAccessToken");
            assertThat(response.getRefreshToken()).isEqualTo("validRefreshToken"); // Same refresh token
        }

        @Test
        @DisplayName("Should throw exception for invalid refresh token")
        void refreshToken_invalidToken_throwsBusinessException() {
            // Given
            RefreshTokenRequest request = RefreshTokenRequest.builder()
                    .refreshToken("invalidToken")
                    .build();
            
            given(jwtService.validateRefreshToken("invalidToken")).willReturn(null);

            // When/Then
            assertThatThrownBy(() -> authService.refreshToken(request))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "INVALID_REFRESH_TOKEN");
        }

        @Test
        @DisplayName("Should throw exception for expired/revoked session")
        void refreshToken_revokedSession_throwsBusinessException() {
            // Given
            UserSession session = UserSession.builder()
                    .user(testUser)
                    .build();
            session.revoke(); // Session is revoked
            
            RefreshTokenRequest request = RefreshTokenRequest.builder()
                    .refreshToken("validRefreshToken")
                    .build();
            
            given(jwtService.validateRefreshToken("validRefreshToken")).willReturn(sessionId);
            given(sessionRepository.findById(sessionId)).willReturn(Optional.of(session));

            // When/Then
            assertThatThrownBy(() -> authService.refreshToken(request))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "SESSION_EXPIRED");
        }
    }
}

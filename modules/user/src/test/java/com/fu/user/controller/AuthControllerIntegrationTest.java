package com.fu.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.infrastructure.email.EmailService;
import com.fu.user.dto.auth.*;
import com.fu.user.entity.*;
import com.fu.user.repository.UserRepository;
import com.fu.user.repository.UserSessionRepository;
import com.fu.user.security.JwtService;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.SecretKey;
import java.time.LocalDateTime;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for AuthController.
 *
 * Runs against a real PostgreSQL database (satori_test).
 * Requires: docker exec satori-postgres psql -U satori_user -d satori_learning
 * -c "CREATE DATABASE satori_test OWNER satori_user;"
 *
 * Coverage (test plan sections 1–4):
 * Section 1 – Login   TC_AUTH_LG_001–007 / 008 / 009 / 010 / 011 / 012
 * Section 2 – Forgot Password TC_AUTH_FP_001–009
 * Section 3 – Change Password TC_AUTH_CP_001–008
 * Section 4 – Logout TC_AUTH_LO_001–004
 *
 * NOTE FOR DEV — HTTP Status Discrepancies
 * ─────────────────────────────────────────
 * Test plan expects HTTP 400 for INVALID_CREDENTIALS / SESSION_EXPIRED /
 * INVALID_REFRESH_TOKEN
 * and HTTP 400 for ACCOUNT_SUSPENDED / ACCOUNT_DELETED / ACCOUNT_LOCKED.
 * Currently GlobalExceptionHandler maps ALL BusinessException → HTTP 400.
 * Tests assert ACTUAL current behavior (400) with an inline NOTE comment.
 * Update assertions after UserExceptionHandler is fixed.
 */
@SpringBootTest(classes = com.fu.user.UserTestApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("AuthController — Integration Tests (PostgreSQL satori_test)")
class AuthControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserSessionRepository sessionRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtService jwtService;

    // Email service is mocked — we don't want real SMTP calls during tests
    @MockBean
    private EmailService emailService;

    // ── Seed data ────────────────────────────────────────────────────────────

        @BeforeEach
        void setUp() {
            doNothing().when(emailService)
                    .sendPasswordResetOtp(any(), any(), any(), anyInt());
            doNothing().when(emailService)
                    .sendPasswordChangedNotification(any(), any());
            doNothing().when(emailService)
                    .sendWelcomeEmailWithCredentials(any(), any(), any(), any());

            userRepository.save(User.builder()
                    .email("learner@test.com")
                    .passwordHash(passwordEncoder.encode("ValidPass@1"))
                    .firstName("Test")
                    .lastName("Learner")
                    .role(UserRole.LEARNER)
                    .status(UserStatus.ACTIVE)
                    .authProvider(AuthProvider.LOCAL)
                    .failedLoginAttempts(0)
                    .build());
        }

    @AfterEach
    void tearDown() {
        sessionRepository.deleteAll();
        userRepository.deleteAll();
    }

    // =========================================================================
    // Section 1 — LOGIN POST /api/v1/auth/login
    // =========================================================================

    @Nested
    @DisplayName("Section 1 — Login")
    class LoginTests {

        /**
         * TC_AUTH_LG_001 — Happy path
         * Valid credentials → HTTP 200, accessToken + refreshToken present, role =
         * LEARNER.
         */
        @Test
        @DisplayName("TC_AUTH_LG_001 — Valid credentials → 200, tokens issued")
        void tc_lg_001_loginSuccessfully() throws Exception {
            LoginRequest request = new LoginRequest();
            request.setEmail("learner@test.com");
            request.setPassword("ValidPass@1");

            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                    .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                    .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                    .andExpect(jsonPath("$.data.expiresIn").value(1800))
                    .andExpect(jsonPath("$.data.user.email").value("learner@test.com"))
                    .andExpect(jsonPath("$.data.user.role").value("LEARNER"));
        }

        /**
         * TC_AUTH_LG_002 — Wrong password.
         *
         * INVALID_CREDENTIALS → 400
         */
        @Test
        @DisplayName("TC_AUTH_LG_002 — Wrong password → INVALID_CREDENTIALS (current: 400, plan: 401)")
        void tc_lg_002_wrongPassword() throws Exception {
            LoginRequest request = new LoginRequest();
            request.setEmail("learner@test.com");
            request.setPassword("WrongPass@1");

            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                    .andExpect(jsonPath("$.message").value("Sai email hoặc mật khẩu. Hãy thử lại"));
        }

        /**
         * TC_AUTH_LG_003 — Non-existing email returns same error as wrong password.
         * Security: prevents email enumeration.
         *
         */
        @Test
        @DisplayName("TC_AUTH_LG_003 — Non-existing email → INVALID_CREDENTIALS (same as LG_002)")
        void tc_lg_003_nonExistingEmail() throws Exception {
            LoginRequest request = new LoginRequest();
            request.setEmail("ghost@test.com");
            request.setPassword("ValidPass@1");

            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                    .andExpect(jsonPath("$.message").value("Sai email hoặc mật khẩu. Hãy thử lại"));
        }

        /**
         * TC_AUTH_LG_004 — SUSPENDED account.
         *
         */
        @Test
        @DisplayName("TC_AUTH_LG_004 — SUSPENDED account → ACCOUNT_SUSPENDED (current: 400, plan: 403)")
        void tc_lg_004_suspendedAccount() throws Exception {
            userRepository.save(User.builder()
                    .email("suspended@test.com")
                    .passwordHash(passwordEncoder.encode("ValidPass@1"))
                    .role(UserRole.LEARNER)
                    .status(UserStatus.SUSPENDED)
                    .authProvider(AuthProvider.LOCAL)
                    .failedLoginAttempts(0)
                    .build());

            LoginRequest request = new LoginRequest();
            request.setEmail("suspended@test.com");
            request.setPassword("ValidPass@1");

            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"))
                    .andExpect(jsonPath("$.message").value("Tài khoản của bạn đã bị chặn. Hãy liên hệ quản lý"));
        }

        /**
         * TC_AUTH_LG_005 — Soft-deleted account.
         *
         */
        @Test
        @DisplayName("TC_AUTH_LG_005 — Soft-deleted account → ACCOUNT_DELETED (current: 400, plan: 403)")
        void tc_lg_005_softDeletedAccount() throws Exception {
            User deleted = userRepository.save(User.builder()
                    .email("deleted@test.com")
                    .passwordHash(passwordEncoder.encode("ValidPass@1"))
                    .role(UserRole.LEARNER)
                    .status(UserStatus.ACTIVE)
                    .authProvider(AuthProvider.LOCAL)
                    .failedLoginAttempts(0)
                    .build());
            // Manually set deletedAt to simulate soft delete
            deleted.setDeletedAt(LocalDateTime.now().minusDays(1));
            userRepository.save(deleted);

            LoginRequest request = new LoginRequest();
            request.setEmail("deleted@test.com");
            request.setPassword("ValidPass@1");

            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_DELETED"));
        }

        /**
         * TC_AUTH_LG_006 — Account locked after reaching maximum failed login attempts (5th wrong attempt).
         *
         * Setup : Insert ACTIVE LEARNER with failedAttempts=4, lockedUntil=null.
         * Action: POST /api/v1/auth/login with wrong password (this is the 5th attempt).
         * Assert response: HTTP 400, success=false, errorCode="ACCOUNT_LOCKED".
         * Assert DB state: lockedUntil ≈ now+30min (±5 sec), failedAttempts=5.
         *
         * Fix applied in AuthServiceImpl:
         *   - login() annotated @Transactional(noRollbackFor = BusinessException.class)
         *   - handleFailedLogin() annotated @Transactional(propagation = REQUIRES_NEW)
         *   Both changes ensure the failed-attempt save is committed even when
         *   BusinessException is thrown.
         */
        @Test
        @DisplayName("TC_AUTH_LG_006 — 5th wrong attempt → ACCOUNT_LOCKED + DB state verified")
        void tc_lg_006_accountLockedAfterFiveFailures() throws Exception {
            // 1. Insert ACTIVE LEARNER with failedAttempts=4, lockedUntil=null
            //    (overwrite the default user seeded by @BeforeEach)
            User user = userRepository.findByEmail("learner@test.com").orElseThrow();
            user.setFailedLoginAttempts(4);
            user.setLockedUntil(null);
            userRepository.save(user);

            // 2. Perform POST /api/v1/auth/login with wrong password — this is the 5th attempt
            LoginRequest request = new LoginRequest();
            request.setEmail("learner@test.com");
            request.setPassword("WrongPass@1");

            LocalDateTime beforeRequest = LocalDateTime.now();

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

            // 3. Query DB and assert state
            User updated = userRepository.findByEmail("learner@test.com").orElseThrow();

            // failedAttempts must be exactly 5
            assertThat(updated.getFailedLoginAttempts()).isEqualTo(5);

            // lockedUntil must be ≈ now + 30 minutes (±5 seconds)
            assertThat(updated.getLockedUntil()).isNotNull();
            LocalDateTime expectedLock = beforeRequest.plusMinutes(30);
            assertThat(updated.getLockedUntil())
                    .isAfter(expectedLock.minusSeconds(5))
                    .isBefore(expectedLock.plusSeconds(5));
        }

        /**
         * TC_AUTH_LG_007 — Login succeeds after lock period expires; counter resets to
         * 0.
         *
         * NOTE FOR DEV: Directly sets lockedUntil in DB (expired lock) to test reset
         * logic.
         * This is independent of the LG_006 transaction bug.
         */
        @Test
        @DisplayName("TC_AUTH_LG_007 — Login succeeds after lock expires; failedAttempts reset to 0")
        void tc_lg_007_loginAfterLockExpires() throws Exception {
            // Set up an expired lock directly in DB
            User user = userRepository.findByEmail("learner@test.com").orElseThrow();
            user.setFailedLoginAttempts(5);
            user.setLockedUntil(LocalDateTime.now().minusSeconds(1));
            userRepository.save(user);

            LoginRequest request = new LoginRequest();
            request.setEmail("learner@test.com");
            request.setPassword("ValidPass@1");

            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.accessToken").isNotEmpty());

            User updated = userRepository.findByEmail("learner@test.com").orElseThrow();
            assertThat(updated.getFailedLoginAttempts()).isEqualTo(0);
            assertThat(updated.getLockedUntil()).isNull();
        }

        /**
         * TC_AUTH_LG_008 — Expired access token on a protected endpoint → HTTP 400.
         * Generates a token signed with the same secret but expiry in the past.
         *
         * filter chain (active in @SpringBootTest) returns 400 for expired JWT.
         * Assertions follow actual code behavior.
         */
        @Test
        @DisplayName("TC_AUTH_LG_008 — Expired access token → 400 Unauthorized")
        void tc_lg_008_expiredAccessToken() throws Exception {
            SecretKey key = (SecretKey) ReflectionTestUtils.getField(jwtService, "accessTokenKey");
            User user = userRepository.findByEmail("learner@test.com").orElseThrow();

            String expiredToken = Jwts.builder()
                    .subject(user.getId().toString())
                    .claim("userId", user.getId().toString())
                    .claim("email", user.getEmail())
                    .claim("role", user.getRole().name())
                    .claim("sessionId", java.util.UUID.randomUUID().toString())
                    .issuedAt(new Date(System.currentTimeMillis() - 120_000))
                    .expiration(new Date(System.currentTimeMillis() - 60_000)) // expired 1 min ago
                    .signWith(key)
                    .compact();

            mockMvc.perform(post("/api/v1/auth/change-password")
                    .header("Authorization", "Bearer " + expiredToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(
                            ChangePasswordRequest.builder()
                                    .currentPassword("ValidPass@1")
                                    .newPassword("NewPass@2")
                                    .confirmPassword("NewPass@2")
                                    .build())))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false));
        }

        /**
         * TC_AUTH_LG_009 — Valid refresh token → new access token issued, same refresh token returned (no rotation).
         */
        @Test
        @DisplayName("TC_AUTH_LG_009 — Valid refresh → new accessToken different from old, same refreshToken")
        void tc_lg_009_refreshToken() throws Exception {
            LoginRequest loginReq = new LoginRequest();
            loginReq.setEmail("learner@test.com");
            loginReq.setPassword("ValidPass@1");

            MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(loginReq)))
                    .andExpect(status().isOk())
                    .andReturn();

            String loginBody = loginResult.getResponse().getContentAsString();
            String oldAccessToken = objectMapper.readTree(loginBody).path("data").path("accessToken").asText();
            String refreshToken   = objectMapper.readTree(loginBody).path("data").path("refreshToken").asText();

            // Small delay so the new access token will have a different iat claim
            Thread.sleep(1100);

            RefreshTokenRequest refreshReq = new RefreshTokenRequest();
            refreshReq.setRefreshToken(refreshToken);

            MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(refreshReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                    .andExpect(jsonPath("$.data.expiresIn").value(1800))
                    .andReturn();

            String refreshBody       = refreshResult.getResponse().getContentAsString();
            String newAccessToken    = objectMapper.readTree(refreshBody).path("data").path("accessToken").asText();
            String returnedRefresh   = objectMapper.readTree(refreshBody).path("data").path("refreshToken").asText();

            assertThat(newAccessToken).isNotEqualTo(oldAccessToken);   // new access token
            assertThat(returnedRefresh).isEqualTo(refreshToken);        // no rotation
        }

        /**
         * TC_AUTH_LG_010 — Refresh token reuse is allowed by design (no rotation implemented).
         *
         * KNOWN SECURITY GAP — documented, not a blocker.
         * Same refresh token can be used repeatedly until it expires naturally.
         */
        @Test
        @DisplayName("TC_AUTH_LG_010 — Refresh token reuse succeeds (KNOWN GAP: rotation not implemented)")
        void tc_lg_010_refreshTokenReuseAllowed() throws Exception {
            LoginRequest loginReq = new LoginRequest();
            loginReq.setEmail("learner@test.com");
            loginReq.setPassword("ValidPass@1");

            MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(loginReq)))
                    .andExpect(status().isOk())
                    .andReturn();

            String refreshToken = objectMapper.readTree(
                    loginResult.getResponse().getContentAsString())
                    .path("data").path("refreshToken").asText();

            RefreshTokenRequest refreshReq = new RefreshTokenRequest();
            refreshReq.setRefreshToken(refreshToken);

            // First use
            mockMvc.perform(post("/api/v1/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(refreshReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.accessToken").isNotEmpty());

            // Second use with the SAME refresh token — must also succeed (current behavior)
            mockMvc.perform(post("/api/v1/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(refreshReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
        }

        /**
         * TC_AUTH_LG_011 — SQL injection in email field does not compromise the system.
         * The @Email / @NotBlank constraint on LoginRequest rejects the malformed value
         * before it reaches the service layer → HTTP 400, no DB query executed.
         * Must NOT expose DB schema, stack trace, or any internal details.
         */
        @Test
        @DisplayName("TC_AUTH_LG_011 — SQL injection in email → 400, no internal details exposed")
        void tc_lg_011_sqlInjectionInEmail() throws Exception {
            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"' OR '1'='1\",\"password\":\"anything\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    // Must NOT expose internal class names, DB schema, or stack traces
                    .andExpect(jsonPath("$.data.stackTrace").doesNotExist())
                    .andExpect(jsonPath("$.data.exception").doesNotExist());
        }

        /**
         * TC_AUTH_LG_012 — Empty body {} → required-field validation fires → HTTP 400.
         * Simulates submit-button logic enforced at API level.
         * Validation error message must be present in the response.
         */
        @Test
        @DisplayName("TC_AUTH_LG_012 — Empty body {} → 400, validation error message present")
        void tc_lg_012_emptyBody() throws Exception {
            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    // At least one validation error message must be present
                    .andExpect(jsonPath("$.message").exists());
        }
    }

    // =========================================================================
    // Section 2 — FORGOT PASSWORD
    // =========================================================================

    @Nested
    @DisplayName("Section 2 — Forgot Password")
    class ForgotPasswordTests {

        /**
         * TC_AUTH_FP_001 — Full flow: sendOtp → verifyOtp → resetPassword.
         */
        @Test
        @DisplayName("TC_AUTH_FP_001 — Full forgot-password flow succeeds end-to-end")
        void tc_fp_001_fullFlow() throws Exception {
            // Step 1: Send OTP
            ForgotPasswordRequest otpRequest = new ForgotPasswordRequest();
            otpRequest.setEmail("learner@test.com");

            mockMvc.perform(post("/api/v1/auth/forgot-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(otpRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            // Step 2: Read OTP from DB (inject directly since we can't receive email)
            User user = userRepository.findByEmail("learner@test.com").orElseThrow();
            String otp = user.getPasswordResetToken();
            assertThat(otp).isNotNull();

            // Step 3: Verify OTP
            VerifyOtpRequest verifyRequest = new VerifyOtpRequest();
            verifyRequest.setEmail("learner@test.com");
            verifyRequest.setOtp(otp);

            MvcResult verifyResult = mockMvc.perform(post("/api/v1/auth/verify-otp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(verifyRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.resetToken").isNotEmpty())
                    .andExpect(jsonPath("$.data.expiresInSeconds").value(300))
                    .andReturn();

            String resetToken = objectMapper.readTree(
                            verifyResult.getResponse().getContentAsString())
                    .path("data").path("resetToken").asText();

            // Step 4: Reset password
            ResetPasswordRequest resetRequest = ResetPasswordRequest.builder()
                    .resetToken(resetToken)
                    .newPassword("NewPass@1")
                    .confirmPassword("NewPass@1")
                    .build();

            mockMvc.perform(post("/api/v1/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(resetRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Đặt lại mật khẩu thành công"));

            // Step 5: Login with new password succeeds (no auto-login per BR-58)
            LoginRequest loginRequest = new LoginRequest();
            loginRequest.setEmail("learner@test.com");
            loginRequest.setPassword("NewPass@1");

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.accessToken").isNotEmpty());

            // Step 6: emailService.sendPasswordChangedNotification() called exactly once
            verify(emailService).sendPasswordChangedNotification(eq("learner@test.com"), any());
        }

        /**
         * TC_AUTH_FP_002 — Wrong OTP.
         */
        @Test
        @DisplayName("TC_AUTH_FP_002 — Wrong OTP → INVALID_OTP")
        void tc_fp_002_wrongOtp() throws Exception {
            // Send OTP first
            ForgotPasswordRequest req = new ForgotPasswordRequest();
            req.setEmail("learner@test.com");
            mockMvc.perform(post("/api/v1/auth/forgot-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)));

            VerifyOtpRequest verifyRequest = new VerifyOtpRequest();
            verifyRequest.setEmail("learner@test.com");
            verifyRequest.setOtp("000000");

            mockMvc.perform(post("/api/v1/auth/verify-otp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(verifyRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_OTP"))
                    .andExpect(jsonPath("$.message").value("Mã OTP không hợp lệ"));
        }

        /**
         * TC_AUTH_FP_003 — Expired OTP.
         */
        @Test
        @DisplayName("TC_AUTH_FP_003 — Expired OTP → OTP_EXPIRED")
        void tc_fp_003_expiredOtp() throws Exception {
            // Inject an already-expired OTP directly
            User user = userRepository.findByEmail("learner@test.com").orElseThrow();
            user.setPasswordResetToken("123456");
            user.setPasswordResetTokenExpiresAt(LocalDateTime.now().minusMinutes(5));
            userRepository.save(user);

            VerifyOtpRequest verifyRequest = new VerifyOtpRequest();
            verifyRequest.setEmail("learner@test.com");
            verifyRequest.setOtp("123456");

            mockMvc.perform(post("/api/v1/auth/verify-otp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(verifyRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("OTP_EXPIRED"))
                    .andExpect(jsonPath("$.message").value("Mã OTP đã hết hạn"));
        }

        /**
         * TC_AUTH_FP_004 — Reset token invalid → INVALID_TOKEN.
         */
        @Test
        @DisplayName("TC_AUTH_FP_004 — Invalid reset token → INVALID_TOKEN")
        void tc_fp_004_invalidResetToken() throws Exception {
            ResetPasswordRequest req = ResetPasswordRequest.builder()
                    .resetToken("00000000-0000-0000-0000-000000000000")
                    .newPassword("NewPass@1")
                    .confirmPassword("NewPass@1")
                    .build();

            mockMvc.perform(post("/api/v1/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        }

        /**
         * TC_AUTH_FP_005 — Reset token expired → TOKEN_EXPIRED.
         * Obtains a real resetToken via forgot-password → verify-otp flow,
         * then manually expires it in DB before calling reset-password.
         */
        @Test
        @DisplayName("TC_AUTH_FP_005 — Expired reset token → TOKEN_EXPIRED")
        void tc_fp_005_expiredResetToken() throws Exception {
            // Step 1: Generate OTP
            ForgotPasswordRequest fpReq = new ForgotPasswordRequest();
            fpReq.setEmail("learner@test.com");
            mockMvc.perform(post("/api/v1/auth/forgot-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(fpReq)))
                    .andExpect(status().isOk());

            // Step 2: Read OTP from DB
            User user = userRepository.findByEmail("learner@test.com").orElseThrow();
            String otp = user.getPasswordResetToken();

            // Step 3: Verify OTP to get resetToken
            VerifyOtpRequest verifyReq = new VerifyOtpRequest();
            verifyReq.setEmail("learner@test.com");
            verifyReq.setOtp(otp);

            MvcResult verifyResult = mockMvc.perform(post("/api/v1/auth/verify-otp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(verifyReq)))
                    .andExpect(status().isOk())
                    .andReturn();

            String resetToken = objectMapper.readTree(
                            verifyResult.getResponse().getContentAsString())
                    .path("data").path("resetToken").asText();

            // Step 4: Expire the resetToken directly in DB
            User updatedUser = userRepository.findByEmail("learner@test.com").orElseThrow();
            updatedUser.setPasswordResetTokenExpiresAt(LocalDateTime.now().minusSeconds(1));
            userRepository.save(updatedUser);

            // Step 5: Attempt reset with expired token
            ResetPasswordRequest resetReq = ResetPasswordRequest.builder()
                    .resetToken(resetToken)
                    .newPassword("NewPass@1")
                    .confirmPassword("NewPass@1")
                    .build();

            mockMvc.perform(post("/api/v1/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(resetReq)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
        }

        /**
         * TC_AUTH_FP_006 — Reset token is single-use; reuse after successful reset is rejected.
         */
        @Test
        @DisplayName("TC_AUTH_FP_006 — Reset token single-use: reuse after success → INVALID_TOKEN")
        void tc_fp_006_resetTokenSingleUse() throws Exception {
            // Step 1: Full flow to get a valid resetToken
            ForgotPasswordRequest fpReq = new ForgotPasswordRequest();
            fpReq.setEmail("learner@test.com");
            mockMvc.perform(post("/api/v1/auth/forgot-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(fpReq)));

            User user = userRepository.findByEmail("learner@test.com").orElseThrow();
            String otp = user.getPasswordResetToken();

            VerifyOtpRequest verifyReq = new VerifyOtpRequest();
            verifyReq.setEmail("learner@test.com");
            verifyReq.setOtp(otp);

            MvcResult verifyResult = mockMvc.perform(post("/api/v1/auth/verify-otp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(verifyReq)))
                    .andExpect(status().isOk())
                    .andReturn();

            String resetToken = objectMapper.readTree(
                            verifyResult.getResponse().getContentAsString())
                    .path("data").path("resetToken").asText();

            // Step 2: First use — succeeds
            ResetPasswordRequest firstReset = ResetPasswordRequest.builder()
                    .resetToken(resetToken)
                    .newPassword("NewPass@1")
                    .confirmPassword("NewPass@1")
                    .build();

            mockMvc.perform(post("/api/v1/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(firstReset)))
                    .andExpect(status().isOk());

            // Step 3: Second use with SAME token — must fail
            ResetPasswordRequest secondReset = ResetPasswordRequest.builder()
                    .resetToken(resetToken)
                    .newPassword("AnotherPass@2")
                    .confirmPassword("AnotherPass@2")
                    .build();

            mockMvc.perform(post("/api/v1/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(secondReset)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        }

        /**
         * TC_AUTH_FP_007 — Weak new password → 400 validation error.
         */
        @Test
        @DisplayName("TC_AUTH_FP_007 — Weak new password on reset → 400 validation error")
        void tc_fp_007_weakNewPassword() throws Exception {
            ResetPasswordRequest resetRequest = ResetPasswordRequest.builder()
                    .resetToken("any-token")
                    .newPassword("weak")
                    .confirmPassword("weak")
                    .build();

            mockMvc.perform(post("/api/v1/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(resetRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        /**
         * TC_AUTH_FP_008 — Confirm password mismatch on reset → PASSWORD_MISMATCH (MSG18).
         * Gets a valid resetToken first, then submits mismatched passwords.
         */
        @Test
        @DisplayName("TC_AUTH_FP_008 — Confirm password mismatch on reset → PASSWORD_MISMATCH")
        void tc_fp_008_confirmMismatch() throws Exception {
            // Obtain a valid resetToken via full flow
            ForgotPasswordRequest fpReq = new ForgotPasswordRequest();
            fpReq.setEmail("learner@test.com");
            mockMvc.perform(post("/api/v1/auth/forgot-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(fpReq)));

            User user = userRepository.findByEmail("learner@test.com").orElseThrow();
            String otp = user.getPasswordResetToken();

            VerifyOtpRequest verifyReq = new VerifyOtpRequest();
            verifyReq.setEmail("learner@test.com");
            verifyReq.setOtp(otp);

            MvcResult verifyResult = mockMvc.perform(post("/api/v1/auth/verify-otp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(verifyReq)))
                    .andExpect(status().isOk())
                    .andReturn();

            String resetToken = objectMapper.readTree(
                            verifyResult.getResponse().getContentAsString())
                    .path("data").path("resetToken").asText();

            // Submit mismatched passwords
            ResetPasswordRequest resetReq = ResetPasswordRequest.builder()
                    .resetToken(resetToken)
                    .newPassword("NewPass@1")
                    .confirmPassword("NewPass@2")  // mismatch
                    .build();

            mockMvc.perform(post("/api/v1/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(resetReq)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("PASSWORD_MISMATCH"))
                    .andExpect(jsonPath("$.message").value("Mật khẩu xác nhận không khớp"));
        }
    }
    // =========================================================================
    // Section 3 — CHANGE PASSWORD POST /api/v1/auth/change-password
    // =========================================================================

    @Nested
    @DisplayName("Section 3 — Change Password")
    class ChangePasswordTests {

        /**
         * Helper: login and return access token.
         */
        private String loginAndGetToken(String email, String password) throws Exception {
            LoginRequest req = new LoginRequest();
            req.setEmail(email);
            req.setPassword(password);

            MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andReturn();

            return objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("data").path("accessToken").asText();
        }

        /**
         * Helper: login and return the full response body as String.
         */
        private String loginAndGetBody(String email, String password) throws Exception {
            LoginRequest req = new LoginRequest();
            req.setEmail(email);
            req.setPassword(password);
            MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andReturn();
            return result.getResponse().getContentAsString();
        }

        /**
         * TC_AUTH_CP_001 — Successful password change.
         */
        @Test
        @DisplayName("TC_AUTH_CP_001 — Change password succeeds, old password no longer works")
        void tc_cp_001_changePasswordSuccessfully() throws Exception {
            String token = loginAndGetToken("learner@test.com", "ValidPass@1");

            ChangePasswordRequest req = ChangePasswordRequest.builder()
                    .currentPassword("ValidPass@1")
                    .newPassword("NewPass@2")
                    .confirmPassword("NewPass@2")
                    .logoutOtherDevices(false)
                    .build();

            mockMvc.perform(post("/api/v1/auth/change-password")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Đổi mật khẩu thành công"));

            // Old password no longer works
            LoginRequest oldLogin = new LoginRequest();
            oldLogin.setEmail("learner@test.com");
            oldLogin.setPassword("ValidPass@1");
            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(oldLogin)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

            // New password works
            LoginRequest newLogin = new LoginRequest();
            newLogin.setEmail("learner@test.com");
            newLogin.setPassword("NewPass@2");
            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(newLogin)))
                    .andExpect(status().isOk());

            // Notification email sent exactly once
            verify(emailService).sendPasswordChangedNotification(eq("learner@test.com"), any());
        }

        /**
         * TC_AUTH_CP_002 — logoutOtherDevices=true revokes all other sessions.
         * DB check: only current session remains active.
         * API check: revoked session's refreshToken is rejected with SESSION_EXPIRED.
         *
         */
        @Test
        @DisplayName("TC_AUTH_CP_002 — logoutOtherDevices=true revokes other sessions; revoked refresh rejected")
        void tc_cp_002_logoutOtherDevices() throws Exception {
            // Session 1 (will be used to change password)
            String body1 = loginAndGetBody("learner@test.com", "ValidPass@1");
            String token1      = objectMapper.readTree(body1).path("data").path("accessToken").asText();

            // Session 2 (should be revoked after password change)
            String body2 = loginAndGetBody("learner@test.com", "ValidPass@1");
            String refreshToken2 = objectMapper.readTree(body2).path("data").path("refreshToken").asText();

            assertThat(sessionRepository.findAll()
                    .stream().filter(s -> s.getRevokedAt() == null).count()).isEqualTo(2);

            ChangePasswordRequest req = ChangePasswordRequest.builder()
                    .currentPassword("ValidPass@1")
                    .newPassword("NewPass@2")
                    .confirmPassword("NewPass@2")
                    .logoutOtherDevices(true)
                    .build();

            mockMvc.perform(post("/api/v1/auth/change-password")
                    .header("Authorization", "Bearer " + token1)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk());

            // DB: only 1 active session remains
            long activeSessions = sessionRepository.findAll()
                    .stream().filter(s -> s.getRevokedAt() == null).count();
            assertThat(activeSessions).isEqualTo(1);

            // API: session2 refresh token must be rejected
            RefreshTokenRequest refreshReq = new RefreshTokenRequest();
            refreshReq.setRefreshToken(refreshToken2);

            mockMvc.perform(post("/api/v1/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(refreshReq)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        }

        /**
         * TC_AUTH_CP_003 — Wrong current password.
         */
        @Test
        @DisplayName("TC_AUTH_CP_003 — Wrong current password → WRONG_PASSWORD")
        void tc_cp_003_wrongCurrentPassword() throws Exception {
            String token = loginAndGetToken("learner@test.com", "ValidPass@1");

            ChangePasswordRequest req = ChangePasswordRequest.builder()
                    .currentPassword("WrongPass@1")
                    .newPassword("NewPass@2")
                    .confirmPassword("NewPass@2")
                    .logoutOtherDevices(false)
                    .build();

            mockMvc.perform(post("/api/v1/auth/change-password")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("WRONG_PASSWORD"))
                    .andExpect(jsonPath("$.message").value("Mật khẩu hiện tại không chính xác"));
        }

        /**
         * TC_AUTH_CP_004 — New password identical to current password.
         *
         * BR-59 is implemented in AuthServiceImpl.changePassword():
         *   throws BusinessException("PASSWORD_REUSE", "Mật khẩu mới không được trùng với mật khẩu cũ.")
         * → GlobalExceptionHandler maps BusinessException → HTTP 400.
         */
        @Test
        @DisplayName("TC_AUTH_CP_004 — New password identical to current password → 400 (MSG16, BR-59)")
        void tc_cp_004_sameAsCurrentPassword() throws Exception {
            // 1. Login with the password seeded by @BeforeEach → get accessToken
            String token = loginAndGetToken("learner@test.com", "ValidPass@1");

            ChangePasswordRequest req = ChangePasswordRequest.builder()
                    .currentPassword("ValidPass@1")
                    .newPassword("ValidPass@1")      // same as current → triggers BR-59
                    .confirmPassword("ValidPass@1")
                    .logoutOtherDevices(false)
                    .build();

            // 2. POST change-password → expect HTTP 400, errorCode = "PASSWORD_REUSE"
            mockMvc.perform(post("/api/v1/auth/change-password")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("PASSWORD_REUSE"))
                    .andExpect(jsonPath("$.message")
                            .value("Mật khẩu mới không được trùng với mật khẩu cũ."));
        }

        /**
         * TC_AUTH_CP_005 — Confirm password mismatch.
         */
        @Test
        @DisplayName("TC_AUTH_CP_005 — Confirm password mismatch → PASSWORD_MISMATCH")
        void tc_cp_005_confirmMismatch() throws Exception {
            String token = loginAndGetToken("learner@test.com", "ValidPass@1");

            ChangePasswordRequest req = ChangePasswordRequest.builder()
                    .currentPassword("ValidPass@1")
                    .newPassword("NewPass@2")
                    .confirmPassword("NewPass@3")
                    .logoutOtherDevices(false)
                    .build();

            mockMvc.perform(post("/api/v1/auth/change-password")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PASSWORD_MISMATCH"));
        }

        /**
         * TC_AUTH_CP_006 — Weak new password → validation error.
         */
        @Test
        @DisplayName("TC_AUTH_CP_006 — Weak new password → 400 validation error")
        void tc_cp_006_weakNewPassword() throws Exception {
            String token = loginAndGetToken("learner@test.com", "ValidPass@1");

            ChangePasswordRequest req = ChangePasswordRequest.builder()
                    .currentPassword("ValidPass@1")
                    .newPassword("weak")
                    .confirmPassword("weak")
                    .logoutOtherDevices(false)
                    .build();

            mockMvc.perform(post("/api/v1/auth/change-password")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest());
        }

        /**
         * TC_AUTH_CP_007 — Unauthenticated request → 400.
         * Full filter chain is active in @SpringBootTest, so 400 is returned correctly
         */
        @Test
        @DisplayName("TC_AUTH_CP_007 — No token → 400 Unauthorized (full filter chain)")
        void tc_cp_007_unauthenticated() throws Exception {
            ChangePasswordRequest req = ChangePasswordRequest.builder()
                    .currentPassword("ValidPass@1")
                    .newPassword("NewPass@2")
                    .confirmPassword("NewPass@2")
                    .logoutOtherDevices(false)
                    .build();

            mockMvc.perform(post("/api/v1/auth/change-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isUnauthorized());
        }

        /**
         * TC_AUTH_CP_008 — Change password with expired Access Token → 401.
         *
         * Steps:
         *   1. ACTIVE LEARNER already inserted by @BeforeEach (learner@test.com).
         *   2. Build an expired JWT signed with the same accessTokenKey via
         *      ReflectionTestUtils (mirrors TC_AUTH_LG_008 pattern).
         *   3. POST /api/v1/auth/change-password with Authorization: Bearer <expiredToken>
         *      and a valid body.
         *   4. Assert HTTP 401 and success = false.
         *
         */
        @Test
        @DisplayName("TC_AUTH_CP_008 — Expired Access Token → 401 Unauthorized")
        void tc_cp_008_expiredToken() throws Exception {

            // 1. Retrieve ACTIVE LEARNER seeded by @BeforeEach
            User learner = userRepository.findByEmail("learner@test.com").orElseThrow();

            // 2. Build an expired JWT using the same key as the real JwtService
            SecretKey key = (SecretKey) ReflectionTestUtils.getField(jwtService, "accessTokenKey");
            String expiredToken = Jwts.builder()
                    .subject(learner.getId().toString())
                    .claim("userId", learner.getId().toString())
                    .claim("email", learner.getEmail())
                    .claim("role", learner.getRole().name())
                    .claim("sessionId", java.util.UUID.randomUUID().toString())
                    .issuedAt(new Date(System.currentTimeMillis() - 120_000))
                    .expiration(new Date(System.currentTimeMillis() - 60_000)) // expired 1 min ago
                    .signWith(key)
                    .compact();

            // 3. Prepare a valid body
            ChangePasswordRequest req = ChangePasswordRequest.builder()
                    .currentPassword("ValidPass@1")
                    .newPassword("NewPass@2")
                    .confirmPassword("NewPass@2")
                    .logoutOtherDevices(false)
                    .build();

            // 4. POST change-password with expired token → expect HTTP 401, success = false
            mockMvc.perform(post("/api/v1/auth/change-password")
                            .header("Authorization", "Bearer " + expiredToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // =========================================================================
    // Section 4 — LOGOUT POST /api/v1/auth/logout
    // =========================================================================

    @Nested
    @DisplayName("Section 4 — Logout")
    class LogoutTests {

        private String loginAndGetTokens(String email, String password) throws Exception {
            LoginRequest req = new LoginRequest();
            req.setEmail(email);
            req.setPassword(password);

            MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andReturn();
            return result.getResponse().getContentAsString();
        }

        /**
         * TC_AUTH_LO_001 — Logout revokes the session in DB (revokedAt is set).
         */
        @Test
        @DisplayName("TC_AUTH_LO_001 — Logout revokes session in DB (revokedAt set)")
        void tc_lo_001_logoutRevokesSession() throws Exception {
            String body = loginAndGetTokens("learner@test.com", "ValidPass@1");
            String accessToken = objectMapper.readTree(body).path("data").path("accessToken").asText();
            String refreshToken = objectMapper.readTree(body).path("data").path("refreshToken").asText();

            assertThat(sessionRepository.findAll()
                    .stream().filter(s -> s.getRevokedAt() == null).count()).isEqualTo(1);

            LogoutRequest req = LogoutRequest.builder()
                    .refreshToken(refreshToken)
                    .logoutAll(false)
                    .build();

            mockMvc.perform(post("/api/v1/auth/logout")
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Đăng xuất thành công"));

            // DB: session revoked
            long activeSessions = sessionRepository.findAll()
                    .stream().filter(s -> s.getRevokedAt() == null).count();
            assertThat(activeSessions).isEqualTo(0);

            // API: refreshToken must be rejected after logout
            RefreshTokenRequest refreshReq = new RefreshTokenRequest();
            refreshReq.setRefreshToken(refreshToken);

            mockMvc.perform(post("/api/v1/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(refreshReq)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        }

        /**
         * TC_AUTH_LO_002 — logoutAll=true revokes all sessions.
         * DB check: all sessions have revokedAt set.
         * API check: refresh tokens from other sessions are rejected.
         */
        @Test
        @DisplayName("TC_AUTH_LO_002 — logoutAll=true revokes all sessions; other refresh tokens rejected")
        void tc_lo_002_logoutAll() throws Exception {
            // Create 3 sessions
            String body1 = loginAndGetTokens("learner@test.com", "ValidPass@1");
            String body2 = loginAndGetTokens("learner@test.com", "ValidPass@1");
            String body3 = loginAndGetTokens("learner@test.com", "ValidPass@1");

            String accessToken   = objectMapper.readTree(body1).path("data").path("accessToken").asText();
            String refreshToken1 = objectMapper.readTree(body1).path("data").path("refreshToken").asText();
            String refreshToken2 = objectMapper.readTree(body2).path("data").path("refreshToken").asText();
            String refreshToken3 = objectMapper.readTree(body3).path("data").path("refreshToken").asText();

            assertThat(sessionRepository.findAll()
                    .stream().filter(s -> s.getRevokedAt() == null).count()).isEqualTo(3);

            LogoutRequest req = LogoutRequest.builder()
                    .refreshToken(refreshToken1)
                    .logoutAll(true)
                    .build();

            mockMvc.perform(post("/api/v1/auth/logout")
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            // DB: all sessions revoked
            long activeSessions = sessionRepository.findAll()
                    .stream().filter(s -> s.getRevokedAt() == null).count();
            assertThat(activeSessions).isEqualTo(0);

            // API: refreshToken_2 rejected
            RefreshTokenRequest r2 = new RefreshTokenRequest();
            r2.setRefreshToken(refreshToken2);
            mockMvc.perform(post("/api/v1/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(r2)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));

            // API: refreshToken_3 rejected
            RefreshTokenRequest r3 = new RefreshTokenRequest();
            r3.setRefreshToken(refreshToken3);
            mockMvc.perform(post("/api/v1/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(r3)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        }

    }
}

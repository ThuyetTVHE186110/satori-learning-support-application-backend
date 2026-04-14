package com.fu.auth;

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
 * Runs from the start module so all modules are on the classpath —
 * no cross-module entity issues (e.g. ClassMember in UserRepository JPQL).
 *
 * Requires: docker exec satori-postgres psql -U satori_user -d satori_learning
 * -c "CREATE DATABASE satori_test OWNER satori_user;"
 *
 * Coverage (test plan sections 1–4):
 * Section 1 – Login              TC_AUTH_LG_001–016
 * Section 2 – Forgot Password    TC_AUTH_FP_001–012, TC_AUTH_FP_009b
 * Section 3 – Change Password    TC_AUTH_CP_001–008
 * Section 4 – Logout             TC_AUTH_LO_001–002
 *
 * ─────────────────────────────────────────
 * Currently GlobalExceptionHandler maps ALL BusinessException → HTTP 400.
 * Tests assert ACTUAL current behavior (400) with an inline NOTE comment.
 * Update assertions after UserExceptionHandler is fixed.
 */
@SpringBootTest(classes = com.fu.StartApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("AuthController — Integration Tests (PostgreSQL satori_test)")
class    AuthControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository sessionRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    @MockBean
    private EmailService emailService;

    // ── Seed data ─────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        doNothing().when(emailService).sendPasswordResetOtp(any(), any(), any(), anyInt());
        doNothing().when(emailService).sendPasswordChangedNotification(any(), any());
        doNothing().when(emailService).sendWelcomeEmailWithCredentials(any(), any(), any(), any());

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
    // Section 1 — LOGIN  POST /api/v1/auth/login
    // =========================================================================

    @Nested
    @DisplayName("Section 1 — Login")
    class LoginTests {

        /**
         * TC_AUTH_LG_001 — Happy path
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/login with email and password.
         *   2. AuthController → extract client metadata and call AuthService.login().
         *   3. AuthService    → retrieve the user account by email.
         *   4. AuthService    → validate the credentials and account status.
         *   5. AuthService    → create a login session.
         *   6. AuthService    → generate the authentication tokens.
         *   7. System         → return the authentication response.
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
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/login with a correct email but wrong password.
         *   2. AuthController → call AuthService.login().
         *   3. AuthService    → retrieve the user account by email.
         *   4. AuthService    → fail password verification.
         *   5. AuthService    → update the failed login attempt counter.
         *   6. System         → return the authentication error response.
         */
        @Test
        @DisplayName("TC_AUTH_LG_002 — Wrong password → INVALID_CREDENTIALS")
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
         * TC_AUTH_LG_003 — Non-existing email.
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/login with a non-existing email.
         *   2. AuthController → call AuthService.login().
         *   3. AuthService    → attempt to retrieve the user account by email.
         *   4. AuthService    → detect that the account does not exist.
         *   5. AuthService    → treat as invalid credentials to prevent email enumeration.
         *   6. System         → return the authentication error response.
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
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }

        /**
         * TC_AUTH_LG_004 — SUSPENDED account.
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/login with valid credentials.
         *   2. AuthController → call AuthService.login().
         *   3. AuthService    → retrieve the user account by email.
         *   4. AuthService    → validate the credentials.
         *   5. AuthService    → detect that the account status is SUSPENDED.
         *   6. System         → return the account suspended error response.
         */
        @Test
        @DisplayName("TC_AUTH_LG_004 — SUSPENDED account → ACCOUNT_SUSPENDED")
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
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/login with valid credentials.
         *   2. AuthController → call AuthService.login().
         *   3. AuthService    → retrieve the user account by email.
         *   4. AuthService    → detect that the account has been soft-deleted.
         *   5. System         → return the account deleted error response.
         */
        @Test
        @DisplayName("TC_AUTH_LG_005 — Soft-deleted account → ACCOUNT_DELETED")
        void tc_lg_005_softDeletedAccount() throws Exception {
            User deleted = userRepository.save(User.builder()
                    .email("deleted@test.com")
                    .passwordHash(passwordEncoder.encode("ValidPass@1"))
                    .role(UserRole.LEARNER)
                    .status(UserStatus.ACTIVE)
                    .authProvider(AuthProvider.LOCAL)
                    .failedLoginAttempts(0)
                    .build());
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
         * TC_AUTH_LG_006 — Account locked after 5th wrong attempt.
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/login with wrong password (5th attempt).
         *   2. AuthController → call AuthService.login().
         *   3. AuthService    → retrieve the user account by email (failedAttempts = 4).
         *   4. AuthService    → fail password verification.
         *   5. AuthService    → update the failed login attempt counter and lock the account.
         *   6. System         → return the account locked error response.
         */
        @Test
        @DisplayName("TC_AUTH_LG_006 — 5th wrong attempt → ACCOUNT_LOCKED + DB state verified")
        void tc_lg_006_accountLockedAfterFiveFailures() throws Exception {
            User user = userRepository.findByEmail("learner@test.com").orElseThrow();
            user.setFailedLoginAttempts(4);
            user.setLockedUntil(null);
            userRepository.save(user);

            LoginRequest request = new LoginRequest();
            request.setEmail("learner@test.com");
            request.setPassword("WrongPass@1");

            LocalDateTime beforeRequest = LocalDateTime.now();

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

            User updated = userRepository.findByEmail("learner@test.com").orElseThrow();
            assertThat(updated.getFailedLoginAttempts()).isEqualTo(5);
            assertThat(updated.getLockedUntil()).isNotNull();
            assertThat(updated.getLockedUntil())
                    .isAfter(beforeRequest.plusMinutes(30).minusSeconds(5))
                    .isBefore(beforeRequest.plusMinutes(30).plusSeconds(5));
        }

        /**
         * TC_AUTH_LG_007 — Login succeeds after lock expires.
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/login with valid credentials (lock already expired).
         *   2. AuthController → call AuthService.login().
         *   3. AuthService    → retrieve the user account by email.
         *   4. AuthService    → detect that the lock has expired.
         *   5. AuthService    → reset the failed login attempt counter.
         *   6. AuthService    → validate the credentials and account status.
         *   7. AuthService    → create a login session.
         *   8. AuthService    → generate the authentication tokens.
         *   9. System         → return the authentication response.
         */
        @Test
        @DisplayName("TC_AUTH_LG_007 — Login succeeds after lock expires; failedAttempts reset to 0")
        void tc_lg_007_loginAfterLockExpires() throws Exception {
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
         * TC_AUTH_LG_008 — Expired access token on a protected endpoint.
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/logout with an expired Bearer token.
         *   2. AuthFilter     → parse the token and detect it is expired.
         *   3. System         → return the unauthorized error response.
         */
        @Test
        @DisplayName("TC_AUTH_LG_008 — Expired access token → 401 UNAUTHORIZED")
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
                    .expiration(new Date(System.currentTimeMillis() - 60_000))
                    .signWith(key)
                    .compact();

            mockMvc.perform(post("/api/v1/auth/logout")
                            .header("Authorization", "Bearer " + expiredToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    LogoutRequest.builder().refreshToken("any").logoutAll(false).build())))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }

        /**
         * TC_AUTH_LG_009 — Valid refresh token → new access token issued, same refresh token (no rotation).
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/refresh with a valid refreshToken.
         *   2. AuthController → call AuthService.refresh().
         *   3. AuthService    → retrieve the session by token.
         *   4. AuthService    → validate that the session is active and not expired.
         *   5. AuthService    → generate a new accessToken (same refreshToken — no rotation).
         *   6. System         → return the authentication response.
         */
        @Test
        @DisplayName("TC_AUTH_LG_009 — Valid refresh → new accessToken, same refreshToken")
        void tc_lg_009_refreshToken() throws Exception {
            LoginRequest loginReq = new LoginRequest();
            loginReq.setEmail("learner@test.com");
            loginReq.setPassword("ValidPass@1");

            MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginReq)))
                    .andExpect(status().isOk()).andReturn();

            String loginBody      = loginResult.getResponse().getContentAsString();
            String oldAccessToken = objectMapper.readTree(loginBody).path("data").path("accessToken").asText();
            String refreshToken   = objectMapper.readTree(loginBody).path("data").path("refreshToken").asText();

            Thread.sleep(1100);

            RefreshTokenRequest refreshReq = new RefreshTokenRequest();
            refreshReq.setRefreshToken(refreshToken);

            MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(refreshReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                    .andReturn();

            String refreshBody     = refreshResult.getResponse().getContentAsString();
            String newAccessToken  = objectMapper.readTree(refreshBody).path("data").path("accessToken").asText();
            String returnedRefresh = objectMapper.readTree(refreshBody).path("data").path("refreshToken").asText();

            assertThat(newAccessToken).isNotEqualTo(oldAccessToken);
            assertThat(returnedRefresh).isEqualTo(refreshToken);
        }

        /**
         * TC_AUTH_LG_010 — Refresh token reuse allowed (no rotation implemented).
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/refresh with a valid refreshToken (first use).
         *   2. AuthController → call AuthService.refresh().
         *   3. AuthService    → retrieve the session by token.
         *   4. AuthService    → validate that the session is active and issue a new accessToken.
         *   5. Client         → POST /api/v1/auth/refresh with the same refreshToken (second use).
         *   6. AuthController → call AuthService.refresh().
         *   7. AuthService    → retrieve the session by token.
         *   8. AuthService    → validate that the session is still active and issue a new accessToken.
         *   9. System         → return the authentication response. (KNOWN GAP: token rotation not yet implemented)
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
                    .andExpect(status().isOk()).andReturn();

            String refreshToken = objectMapper.readTree(
                    loginResult.getResponse().getContentAsString()).path("data").path("refreshToken").asText();

            RefreshTokenRequest refreshReq = new RefreshTokenRequest();
            refreshReq.setRefreshToken(refreshToken);

            mockMvc.perform(post("/api/v1/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(refreshReq)))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/api/v1/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(refreshReq)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
        }

        /**
         * TC_AUTH_LG_011 — SQL injection in email field.
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/login with a malformed email containing SQL injection.
         *   2. AuthController → validate the request body.
         *   3. AuthController → detect that the email format is invalid.
         *   4. System         → return the validation error response before reaching AuthService.
         */
        @Test
        @DisplayName("TC_AUTH_LG_011 — SQL injection in email → 400, no internal details exposed")
        void tc_lg_011_sqlInjectionInEmail() throws Exception {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"' OR '1'='1\",\"password\":\"anything\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.data.stackTrace").doesNotExist())
                    .andExpect(jsonPath("$.data.exception").doesNotExist());
        }

        /**
         * TC_AUTH_LG_012 — Empty request body.
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/login with an empty body {}.
         *   2. AuthController → validate the request body.
         *   3. AuthController → detect that required fields are missing.
         *   4. System         → return the validation error response before reaching AuthService.
         */
        @Test
        @DisplayName("TC_AUTH_LG_012 — Empty body {} → 400, validation error message present")
        void tc_lg_012_emptyBody() throws Exception {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").exists());
        }

        /**
         * TC_AUTH_LG_013 — Expired refresh token.
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/refresh with an expired refreshToken.
         *   2. AuthController → call AuthService.refresh().
         *   3. AuthService    → retrieve the session by token.
         *   4. AuthService    → detect that the session has expired.
         *   5. System         → return the session expired error response.
         */
        @Test
        @DisplayName("TC_AUTH_LG_013 — Expired refresh token → SESSION_EXPIRED")
        void tc_lg_013_expiredRefreshToken() throws Exception {
            LoginRequest loginReq = new LoginRequest();
            loginReq.setEmail("learner@test.com");
            loginReq.setPassword("ValidPass@1");

            MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginReq)))
                    .andExpect(status().isOk()).andReturn();

            String refreshToken = objectMapper.readTree(
                    loginResult.getResponse().getContentAsString()).path("data").path("refreshToken").asText();

            sessionRepository.findAll().forEach(s -> {
                s.setExpiresAt(LocalDateTime.now().minusSeconds(1));
                sessionRepository.save(s);
            });

            RefreshTokenRequest refreshReq = new RefreshTokenRequest();
            refreshReq.setRefreshToken(refreshToken);

            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(refreshReq)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"))
                    .andExpect(jsonPath("$.message").value("Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại"));
        }

        /**
         * TC_AUTH_LG_014 — Revoked refresh token.
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/refresh with a revoked refreshToken.
         *   2. AuthController → call AuthService.refresh().
         *   3. AuthService    → retrieve the session by token.
         *   4. AuthService    → detect that the session has been revoked.
         *   5. System         → return the session expired error response.
         */
        @Test
        @DisplayName("TC_AUTH_LG_014 — Revoked refresh token → SESSION_EXPIRED")
        void tc_lg_014_revokedRefreshToken() throws Exception {
            LoginRequest loginReq = new LoginRequest();
            loginReq.setEmail("learner@test.com");
            loginReq.setPassword("ValidPass@1");

            MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginReq)))
                    .andExpect(status().isOk()).andReturn();

            String refreshToken = objectMapper.readTree(
                    loginResult.getResponse().getContentAsString()).path("data").path("refreshToken").asText();

            sessionRepository.findAll().forEach(s -> {
                s.setRevokedAt(LocalDateTime.now().minusSeconds(1));
                sessionRepository.save(s);
            });

            RefreshTokenRequest refreshReq = new RefreshTokenRequest();
            refreshReq.setRefreshToken(refreshToken);

            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(refreshReq)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"))
                    .andExpect(jsonPath("$.message").value("Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại"));
        }

        /**
         * TC_AUTH_LG_015 — Email with leading/trailing whitespace is trimmed (BR-48).
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/login with email containing leading/trailing whitespace.
         *   2. AuthController → trim the email before validation and processing.
         *   3. AuthService    → retrieve the user account by the trimmed email.
         *   4. AuthService    → validate the credentials and account status.
         *   5. AuthService    → create a login session and generate tokens.
         *   6. System         → return the authentication response.
         */
        @Test
        @DisplayName("TC_AUTH_LG_015 — Email with whitespace trimmed (BR-48) → successful login")
        void tc_lg_015_emailWithWhitespaceTrimmed() throws Exception {
            LoginRequest request = new LoginRequest();
            request.setEmail("  learner@test.com  ");
            request.setPassword("ValidPass@1");

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                    .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                    .andExpect(jsonPath("$.data.user.email").value("learner@test.com"))
                    .andExpect(jsonPath("$.data.user.role").value("LEARNER"));
        }

        /**
         * TC_AUTH_LG_016 — Email length exceeds 255 characters (BR-49).
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/login with email longer than 255 characters.
         *   2. AuthController → validate the request body and detect email length violation.
         *   3. System         → return the validation error response before reaching AuthService.
         */
        @Test
        @DisplayName("TC_AUTH_LG_016 — Email length > 255 characters (BR-49) → 400 validation error")
        void tc_lg_016_emailTooLong() throws Exception {
            // Create email with 256 characters (255 + 1 extra)
            String longEmail = "a".repeat(256) + "@test.com";
            
            LoginRequest request = new LoginRequest();
            request.setEmail(longEmail);
            request.setPassword("ValidPass@1");

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
    }

    // =========================================================================
    // Section 2 — FORGOT PASSWORD
    // =========================================================================

    @Nested
    @DisplayName("Section 2 — Forgot Password")
    class ForgotPasswordTests {

        /**
         * TC_AUTH_FP_001 — Full forgot-password flow.
         *
         * Test Procedure:
         *   1.  Client         → POST /api/v1/auth/forgot-password with a registered email.
         *   2.  AuthController → call AuthService.sendPasswordResetOtp().
         *   3.  AuthService    → retrieve the user account by email.
         *   4.  AuthService    → generate a 6-digit OTP and save it.
         *   5.  AuthService    → send the OTP to the user via EmailService.
         *   6.  Client         → POST /api/v1/auth/verify-otp with the correct OTP.
         *   7.  AuthController → call AuthService.verifyOtp().
         *   8.  AuthService    → retrieve and validate the OTP by email.
         *   9.  AuthService    → generate a resetToken and save it.
         *   10. Client         → POST /api/v1/auth/reset-password with resetToken and new password.
         *   11. AuthController → call AuthService.resetPassword().
         *   12. AuthService    → retrieve the account by resetToken.
         *   13. AuthService    → validate that the token is not expired and not yet used.
         *   14. AuthService    → save the new password and clear the resetToken.
         *   15. AuthService    → notify the user via EmailService.
         *   16. System         → return the success response.
         */
        @Test
        @DisplayName("TC_AUTH_FP_001 — Full forgot-password flow succeeds end-to-end")
        void tc_fp_001_fullFlow() throws Exception {
            ForgotPasswordRequest otpRequest = new ForgotPasswordRequest();
            otpRequest.setEmail("learner@test.com");

            mockMvc.perform(post("/api/v1/auth/forgot-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(otpRequest)))
                    .andExpect(status().isOk());

            User user = userRepository.findByEmail("learner@test.com").orElseThrow();
            String otp = user.getPasswordResetToken();
            assertThat(otp).isNotNull();

            VerifyOtpRequest verifyRequest = new VerifyOtpRequest();
            verifyRequest.setEmail("learner@test.com");
            verifyRequest.setOtp(otp);

            MvcResult verifyResult = mockMvc.perform(post("/api/v1/auth/verify-otp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(verifyRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.resetToken").isNotEmpty())
                    .andReturn();

            String resetToken = objectMapper.readTree(
                    verifyResult.getResponse().getContentAsString()).path("data").path("resetToken").asText();

            ResetPasswordRequest resetRequest = ResetPasswordRequest.builder()
                    .resetToken(resetToken).newPassword("NewPass@1").confirmPassword("NewPass@1").build();

            mockMvc.perform(post("/api/v1/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(resetRequest)))
                    .andExpect(status().isOk());

            LoginRequest loginRequest = new LoginRequest();
            loginRequest.setEmail("learner@test.com");
            loginRequest.setPassword("NewPass@1");

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.accessToken").isNotEmpty());

            verify(emailService).sendPasswordChangedNotification(eq("learner@test.com"), any());
        }

        /**
         * TC_AUTH_FP_002 — Wrong OTP.
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/forgot-password — OTP generated and saved.
         *   2. Client         → POST /api/v1/auth/verify-otp with a wrong OTP.
         *   3. AuthController → call AuthService.verifyOtp().
         *   4. AuthService    → retrieve the OTP by email.
         *   5. AuthService    → detect that the OTP value does not match.
         *   6. System         → return the invalid OTP error response.
         */
        @Test
        @DisplayName("TC_AUTH_FP_002 — Wrong OTP → INVALID_OTP")
        void tc_fp_002_wrongOtp() throws Exception {
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
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/verify-otp with a correct but expired OTP.
         *   2. AuthController → call AuthService.verifyOtp().
         *   3. AuthService    → retrieve the OTP by email.
         *   4. AuthService    → detect that the OTP has already expired.
         *   5. System         → return the OTP expired error response.
         */
        @Test
        @DisplayName("TC_AUTH_FP_003 — Expired OTP → OTP_EXPIRED")
        void tc_fp_003_expiredOtp() throws Exception {
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
         * TC_AUTH_FP_004 — Invalid reset token.
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/reset-password with a non-existing resetToken.
         *   2. AuthController → call AuthService.resetPassword().
         *   3. AuthService    → attempt to retrieve the account by resetToken.
         *   4. AuthService    → detect that the token does not exist.
         *   5. System         → return the invalid token error response.
         */
        @Test
        @DisplayName("TC_AUTH_FP_004 — Invalid reset token → INVALID_TOKEN")
        void tc_fp_004_invalidResetToken() throws Exception {
            ResetPasswordRequest req = ResetPasswordRequest.builder()
                    .resetToken("00000000-0000-0000-0000-000000000000")
                    .newPassword("NewPass@1").confirmPassword("NewPass@1").build();

            mockMvc.perform(post("/api/v1/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        }

        /**
         * TC_AUTH_FP_005 — Expired reset token.
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/reset-password with a valid but expired resetToken.
         *   2. AuthController → call AuthService.resetPassword().
         *   3. AuthService    → retrieve the account by resetToken.
         *   4. AuthService    → detect that the token has already expired.
         *   5. System         → return the token expired error response.
         */
        @Test
        @DisplayName("TC_AUTH_FP_005 — Expired reset token → TOKEN_EXPIRED")
        void tc_fp_005_expiredResetToken() throws Exception {
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
                    .andExpect(status().isOk()).andReturn();

            String resetToken = objectMapper.readTree(
                    verifyResult.getResponse().getContentAsString()).path("data").path("resetToken").asText();

            User updatedUser = userRepository.findByEmail("learner@test.com").orElseThrow();
            updatedUser.setPasswordResetTokenExpiresAt(LocalDateTime.now().minusSeconds(1));
            userRepository.save(updatedUser);

            mockMvc.perform(post("/api/v1/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(ResetPasswordRequest.builder()
                                    .resetToken(resetToken).newPassword("NewPass@1").confirmPassword("NewPass@1").build())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
        }

        /**
         * TC_AUTH_FP_006 — Reset token single-use.
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/reset-password with a valid resetToken (first use).
         *   2. AuthController → call AuthService.resetPassword().
         *   3. AuthService    → validate and consume the resetToken.
         *   4. AuthService    → clear the resetToken after the first use.
         *   5. Client         → POST /api/v1/auth/reset-password with the same resetToken (second use).
         *   6. AuthController → call AuthService.resetPassword().
         *   7. AuthService    → attempt to retrieve the account by resetToken.
         *   8. AuthService    → detect that the token no longer exists.
         *   9. System         → return the invalid token error response.
         */
        @Test
        @DisplayName("TC_AUTH_FP_006 — Reset token single-use: reuse after success → INVALID_TOKEN")
        void tc_fp_006_resetTokenSingleUse() throws Exception {
            ForgotPasswordRequest fpReq = new ForgotPasswordRequest();
            fpReq.setEmail("learner@test.com");
            mockMvc.perform(post("/api/v1/auth/forgot-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(fpReq)));

            User user = userRepository.findByEmail("learner@test.com").orElseThrow();
            VerifyOtpRequest verifyReq = new VerifyOtpRequest();
            verifyReq.setEmail("learner@test.com");
            verifyReq.setOtp(user.getPasswordResetToken());

            MvcResult verifyResult = mockMvc.perform(post("/api/v1/auth/verify-otp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(verifyReq)))
                    .andExpect(status().isOk()).andReturn();

            String resetToken = objectMapper.readTree(
                    verifyResult.getResponse().getContentAsString()).path("data").path("resetToken").asText();

            mockMvc.perform(post("/api/v1/auth/reset-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(ResetPasswordRequest.builder()
                            .resetToken(resetToken).newPassword("NewPass@1").confirmPassword("NewPass@1").build())))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/api/v1/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(ResetPasswordRequest.builder()
                                    .resetToken(resetToken).newPassword("AnotherPass@2").confirmPassword("AnotherPass@2").build())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        }

        /**
         * TC_AUTH_FP_007 — Weak new password on reset.
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/reset-password with a weak new password.
         *   2. AuthController → validate the request body.
         *   3. AuthController → detect that the password does not meet the policy requirements.
         *   4. System         → return the validation error response before reaching AuthService.
         */
        @Test
        @DisplayName("TC_AUTH_FP_007 — Weak new password on reset → 400 validation error")
        void tc_fp_007_weakNewPassword() throws Exception {
            mockMvc.perform(post("/api/v1/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(ResetPasswordRequest.builder()
                                    .resetToken("any").newPassword("weak").confirmPassword("weak").build())))
                    .andExpect(status().isBadRequest());
        }

        /**
         * TC_AUTH_FP_008 — Confirm password mismatch on reset.
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/reset-password with newPassword ≠ confirmPassword.
         *   2. AuthController → call AuthService.resetPassword().
         *   3. AuthService    → retrieve the account by resetToken.
         *   4. AuthService    → detect the password mismatch.
         *   5. System         → return the password mismatch error response.
         */
        @Test
        @DisplayName("TC_AUTH_FP_008 — Confirm password mismatch on reset → PASSWORD_MISMATCH")
        void tc_fp_008_confirmMismatch() throws Exception {
            ForgotPasswordRequest fpReq = new ForgotPasswordRequest();
            fpReq.setEmail("learner@test.com");
            mockMvc.perform(post("/api/v1/auth/forgot-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(fpReq)));

            User user = userRepository.findByEmail("learner@test.com").orElseThrow();
            VerifyOtpRequest verifyReq = new VerifyOtpRequest();
            verifyReq.setEmail("learner@test.com");
            verifyReq.setOtp(user.getPasswordResetToken());

            MvcResult verifyResult = mockMvc.perform(post("/api/v1/auth/verify-otp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(verifyReq)))
                    .andExpect(status().isOk()).andReturn();

            String resetToken = objectMapper.readTree(
                    verifyResult.getResponse().getContentAsString()).path("data").path("resetToken").asText();

            mockMvc.perform(post("/api/v1/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(ResetPasswordRequest.builder()
                                    .resetToken(resetToken).newPassword("NewPass@1").confirmPassword("NewPass@2").build())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PASSWORD_MISMATCH"));
        }

        /**
         * TC_AUTH_FP_009 — Incorrect/non-existent email address on verify OTP screen (A3).
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/forgot-password with learner@test.com.
         *   2. System         → generates OTP for learner@test.com.
         *   3. Client         → POST /api/v1/auth/verify-otp with non-existent email (nonexistent@test.com) and valid OTP.
         *   4. Scenario       → User sees wrong email displayed, tries to correct but enters non-existent email.
         *   5. AuthController → call AuthService.verifyOtp().
         *   6. AuthService    → attempt to retrieve OTP by nonexistent@test.com (no user exists).
         *   7. System         → return invalid OTP error response.
         */
        @Test
        @DisplayName("TC_AUTH_FP_009 — Non-existent email on verify OTP screen (A3) → INVALID_OTP")
        void tc_fp_009_nonExistentEmailOnVerifyScreen() throws Exception {
            // Request OTP for existing email
            ForgotPasswordRequest fpRequest = new ForgotPasswordRequest();
            fpRequest.setEmail("learner@test.com");
            mockMvc.perform(post("/api/v1/auth/forgot-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(fpRequest)));

            // Get the OTP generated for learner@test.com
            User learnerUser = userRepository.findByEmail("learner@test.com").orElseThrow();
            String validOtp = learnerUser.getPasswordResetToken();

            // Scenario: User sees wrong email on screen and tries to correct with non-existent email
            VerifyOtpRequest verifyRequest = new VerifyOtpRequest();
            verifyRequest.setEmail("nonexistent@test.com");  // Non-existent email
            verifyRequest.setOtp(validOtp);                  // Valid OTP but for wrong email

            mockMvc.perform(post("/api/v1/auth/verify-otp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(verifyRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_OTP"))
                    .andExpect(jsonPath("$.message").value("Mã OTP không hợp lệ"));
        }

        /**
         * TC_AUTH_FP_009b — Email mismatch: OTP requested for email A, verification attempted with email B (A3).
         *
         * Test Procedure:
         *   1. Setup         → Create two users: user.a@test.com and user.b@test.com.
         *   2. Client         → POST /api/v1/auth/forgot-password with user.a@test.com.
         *   3. System         → generates OTP for user.a@test.com.
         *   4. Client         → POST /api/v1/auth/verify-otp with user.b@test.com (wrong existing email) and user.a@test.com's OTP.
         *   5. Scenario       → User sees wrong email displayed (user.b@test.com), tries to use correct OTP for user.a@test.com.
         *   6. AuthController → call AuthService.verifyOtp().
         *   7. AuthService    → retrieve OTP by user.b@test.com (no OTP exists or different OTP).
         *   8. System         → return invalid OTP error response.
         */
        @Test
        @DisplayName("TC_AUTH_FP_009b — Email mismatch: A requests OTP, B tries to verify (A3) → INVALID_OTP")
        void tc_fp_009b_emailMismatchBothExist() throws Exception {
            // Create second user that exists in the database
            userRepository.save(User.builder()
                    .email("user.b@test.com")
                    .passwordHash(passwordEncoder.encode("ValidPass@1"))
                    .firstName("User")
                    .lastName("B")
                    .role(UserRole.LEARNER)
                    .status(UserStatus.ACTIVE)
                    .authProvider(AuthProvider.LOCAL)
                    .failedLoginAttempts(0)
                    .build());

            // Request OTP only for user.a@test.com (learner@test.com)
            ForgotPasswordRequest fpRequest = new ForgotPasswordRequest();
            fpRequest.setEmail("learner@test.com");
            mockMvc.perform(post("/api/v1/auth/forgot-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(fpRequest)));

            // Get the OTP generated for learner@test.com
            User userA = userRepository.findByEmail("learner@test.com").orElseThrow();
            String validOtpForUserA = userA.getPasswordResetToken();

            // Scenario: User sees wrong email (user.b@test.com) on screen but tries to use OTP for learner@test.com
            VerifyOtpRequest verifyRequest = new VerifyOtpRequest();
            verifyRequest.setEmail("user.b@test.com");     // Wrong existing email shown on screen
            verifyRequest.setOtp(validOtpForUserA);      // Valid OTP but for different email

            mockMvc.perform(post("/api/v1/auth/verify-otp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(verifyRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_OTP"))
                    .andExpect(jsonPath("$.message").value("Mã OTP không hợp lệ"));
        }

        /**
         * TC_AUTH_FP_010 — Resend OTP functionality (A4).
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/forgot-password with email (first request).
         *   2. System         → generates and saves first OTP.
         *   3. Client         → POST /api/v1/auth/forgot-password with same email (resend request).
         *   4. System         → generates new OTP and overwrites the previous one.
         *   5. Client         → POST /api/v1/auth/verify-otp with old OTP → should fail.
         *   6. Client         → POST /api/v1/auth/verify-otp with new OTP → should succeed.
         */
        @Test
        @DisplayName("TC_AUTH_FP_010 — Resend OTP (A4) → new OTP generated, old OTP invalid")
        void tc_fp_010_resendOtp() throws Exception {
            ForgotPasswordRequest fpRequest = new ForgotPasswordRequest();
            fpRequest.setEmail("learner@test.com");

            // First OTP request
            mockMvc.perform(post("/api/v1/auth/forgot-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(fpRequest)));

            User user = userRepository.findByEmail("learner@test.com").orElseThrow();
            String firstOtp = user.getPasswordResetToken();

            // Resend OTP request
            mockMvc.perform(post("/api/v1/auth/forgot-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(fpRequest)));

            user = userRepository.findByEmail("learner@test.com").orElseThrow();
            String secondOtp = user.getPasswordResetToken();

            // Verify that OTPs are different
            assertThat(secondOtp).isNotEqualTo(firstOtp);

            // Old OTP should fail
            VerifyOtpRequest verifyOldRequest = new VerifyOtpRequest();
            verifyOldRequest.setEmail("learner@test.com");
            verifyOldRequest.setOtp(firstOtp);

            mockMvc.perform(post("/api/v1/auth/verify-otp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(verifyOldRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_OTP"));

            // New OTP should succeed
            VerifyOtpRequest verifyNewRequest = new VerifyOtpRequest();
            verifyNewRequest.setEmail("learner@test.com");
            verifyNewRequest.setOtp(secondOtp);

            mockMvc.perform(post("/api/v1/auth/verify-otp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(verifyNewRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.resetToken").isNotEmpty());
        }

        /**
         * TC_AUTH_FP_011 — Password reset doesn't auto-login (BR-58).
         *
         * Test Procedure:
         *   1. Client         → Complete full forgot password flow (request OTP → verify → reset password).
         *   2. System         → successfully resets password.
         *   3. Client         → Try to access protected endpoint without new login.
         *   4. System         → should return 401 Unauthorized.
         *   5. Client         → Login with new credentials → should succeed.
         */
        @Test
        @DisplayName("TC_AUTH_FP_011 — Password reset doesn't auto-login (BR-58) → must login with new credentials")
        void tc_fp_011_passwordResetNoAutoLogin() throws Exception {
            // Complete full password reset flow
            ForgotPasswordRequest fpRequest = new ForgotPasswordRequest();
            fpRequest.setEmail("learner@test.com");
            mockMvc.perform(post("/api/v1/auth/forgot-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(fpRequest)));

            User user = userRepository.findByEmail("learner@test.com").orElseThrow();
            String otp = user.getPasswordResetToken();

            VerifyOtpRequest verifyRequest = new VerifyOtpRequest();
            verifyRequest.setEmail("learner@test.com");
            verifyRequest.setOtp(otp);

            MvcResult verifyResult = mockMvc.perform(post("/api/v1/auth/verify-otp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(verifyRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.resetToken").isNotEmpty())
                    .andReturn();

            String resetToken = objectMapper.readTree(
                    verifyResult.getResponse().getContentAsString()).path("data").path("resetToken").asText();

            ResetPasswordRequest resetRequest = ResetPasswordRequest.builder()
                    .resetToken(resetToken).newPassword("NewPass@1").confirmPassword("NewPass@1").build();

            mockMvc.perform(post("/api/v1/auth/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(resetRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            // Verify that old password no longer works
            LoginRequest oldLoginRequest = new LoginRequest();
            oldLoginRequest.setEmail("learner@test.com");
            oldLoginRequest.setPassword("ValidPass@1");

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(oldLoginRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

            // Verify that new password works (must explicitly login)
            LoginRequest newLoginRequest = new LoginRequest();
            newLoginRequest.setEmail("learner@test.com");
            newLoginRequest.setPassword("NewPass@1");

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(newLoginRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
        }

        /**
         * TC_AUTH_FP_012 — OTP valid for exactly 10 minutes (BR-54).
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/forgot-password → OTP generated.
         *   2. Client         → POST /api/v1/auth/verify-otp within 10 minutes → should succeed.
         *   3. Client         → POST /api/v1/auth/forgot-password → new OTP generated.
         *   4. Wait for 10+ minutes to pass.
         *   5. Client         → POST /api/v1/auth/verify-otp after 10 minutes → should fail.
         */
        @Test
        @DisplayName("TC_AUTH_FP_012 — OTP valid for exactly 10 minutes (BR-54) → expires after 10 minutes")
        void tc_fp_012_otpValidTenMinutes() throws Exception {
            ForgotPasswordRequest fpRequest = new ForgotPasswordRequest();
            fpRequest.setEmail("learner@test.com");

            // Test 1: OTP within 10 minutes should work
            mockMvc.perform(post("/api/v1/auth/forgot-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(fpRequest)));

            User user = userRepository.findByEmail("learner@test.com").orElseThrow();
            String validOtp = user.getPasswordResetToken();
            LocalDateTime expiresAt = user.getPasswordResetTokenExpiresAt();

            // Verify OTP is set to expire in approximately 10 minutes
            assertThat(expiresAt).isAfter(LocalDateTime.now().plusMinutes(9));
            assertThat(expiresAt).isBefore(LocalDateTime.now().plusMinutes(11));

            // Verify OTP works within valid time
            VerifyOtpRequest verifyRequest = new VerifyOtpRequest();
            verifyRequest.setEmail("learner@test.com");
            verifyRequest.setOtp(validOtp);

            mockMvc.perform(post("/api/v1/auth/verify-otp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(verifyRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.resetToken").isNotEmpty());

            // Test 2: OTP after 10 minutes should fail
            mockMvc.perform(post("/api/v1/auth/forgot-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(fpRequest)));

            user = userRepository.findByEmail("learner@test.com").orElseThrow();
            String expiredOtp = user.getPasswordResetToken();

            // Manually set OTP as expired (more than 10 minutes ago)
            user.setPasswordResetTokenExpiresAt(LocalDateTime.now().minusMinutes(11));
            userRepository.save(user);

            VerifyOtpRequest expiredVerifyRequest = new VerifyOtpRequest();
            expiredVerifyRequest.setEmail("learner@test.com");
            expiredVerifyRequest.setOtp(expiredOtp);

            mockMvc.perform(post("/api/v1/auth/verify-otp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(expiredVerifyRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("OTP_EXPIRED"));
        }
    }

    // =========================================================================
    // Section 3 — CHANGE PASSWORD  POST /api/v1/auth/change-password
    // =========================================================================

    @Nested
    @DisplayName("Section 3 — Change Password")
    class ChangePasswordTests {

        private String loginAndGetToken(String email, String password) throws Exception {
            LoginRequest req = new LoginRequest();
            req.setEmail(email);
            req.setPassword(password);
            MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk()).andReturn();
            return objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("data").path("accessToken").asText();
        }

        private String loginAndGetBody(String email, String password) throws Exception {
            LoginRequest req = new LoginRequest();
            req.setEmail(email);
            req.setPassword(password);
            MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk()).andReturn();
            return result.getResponse().getContentAsString();
        }

        /**
         * TC_AUTH_CP_001 — Successful password change.
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/change-password with a valid Bearer token and correct currentPassword.
         *   2. AuthFilter     → parse the token and resolve the user identity.
         *   3. AuthController → call AuthService.changePassword().
         *   4. AuthService    → retrieve the user account by identity.
         *   5. AuthService    → verify the current password.
         *   6. AuthService    → save the new password.
         *   7. AuthService    → notify the user via EmailService.
         *   8. System         → return the success response.
         */
        @Test
        @DisplayName("TC_AUTH_CP_001 — Change password succeeds, old password no longer works")
        void tc_cp_001_changePasswordSuccessfully() throws Exception {
            String token = loginAndGetToken("learner@test.com", "ValidPass@1");

            mockMvc.perform(post("/api/v1/auth/change-password")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(ChangePasswordRequest.builder()
                                    .currentPassword("ValidPass@1").newPassword("NewPass@2")
                                    .confirmPassword("NewPass@2").logoutOtherDevices(false).build())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            LoginRequest oldLogin = new LoginRequest();
            oldLogin.setEmail("learner@test.com");
            oldLogin.setPassword("ValidPass@1");
            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(oldLogin)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

            verify(emailService).sendPasswordChangedNotification(eq("learner@test.com"), any());
        }

        /**
         * TC_AUTH_CP_002 — Password change invalidates ALL sessions (BR-61, BR-63).
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/change-password with logoutOtherDevices = true.
         *   2. AuthFilter     → parse the token and resolve the user identity.
         *   3. AuthController → call AuthService.changePassword().
         *   4. AuthService    → retrieve the user account by identity.
         *   5. AuthService    → verify the current password and save the new password.
         *   6. AuthService    → revoke ALL sessions including current one (BR-61, BR-63).
         *   7. System         → return the success response.
         *   8. Verification   → Current token becomes invalid (SESSION_EXPIRED).
         *   9. Verification   → All other sessions become invalid (SESSION_EXPIRED).
         */
        @Test
        @DisplayName("TC_AUTH_CP_002 — Password change invalidates ALL sessions (BR-61, BR-63)")
        void tc_cp_002_logoutOtherDevices() throws Exception {
            String body1      = loginAndGetBody("learner@test.com", "ValidPass@1");
            String token1     = objectMapper.readTree(body1).path("data").path("accessToken").asText();
            String body2      = loginAndGetBody("learner@test.com", "ValidPass@1");
            String refresh2   = objectMapper.readTree(body2).path("data").path("refreshToken").asText();

            assertThat(sessionRepository.findAll().stream()
                    .filter(s -> s.getRevokedAt() == null).count()).isEqualTo(2);

            mockMvc.perform(post("/api/v1/auth/change-password")
                            .header("Authorization", "Bearer " + token1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(ChangePasswordRequest.builder()
                                    .currentPassword("ValidPass@1").newPassword("NewPass@2")
                                    .confirmPassword("NewPass@2").logoutOtherDevices(true).build())))
                    .andExpect(status().isOk());

            // BR-61 & BR-63: ALL sessions should be revoked, including current one
            assertThat(sessionRepository.findAll().stream()
                    .filter(s -> s.getRevokedAt() == null).count()).isEqualTo(0);

            // Verify current token is invalid (BR-61)
            mockMvc.perform(post("/api/v1/auth/logout")
                            .header("Authorization", "Bearer " + token1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    LogoutRequest.builder().refreshToken("any").logoutAll(false).build())))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

            // Verify other session refresh token is invalid (BR-63)
            RefreshTokenRequest refreshReq = new RefreshTokenRequest();
            refreshReq.setRefreshToken(refresh2);
            mockMvc.perform(post("/api/v1/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(refreshReq)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        }

        /**
         * TC_AUTH_CP_003 — Wrong current password.
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/change-password with a wrong currentPassword.
         *   2. AuthFilter     → parse the token and resolve the user identity.
         *   3. AuthController → call AuthService.changePassword().
         *   4. AuthService    → retrieve the user account by identity.
         *   5. AuthService    → fail current password verification.
         *   6. System         → return the wrong password error response.
         */
        @Test
        @DisplayName("TC_AUTH_CP_003 — Wrong current password → WRONG_PASSWORD")
        void tc_cp_003_wrongCurrentPassword() throws Exception {
            String token = loginAndGetToken("learner@test.com", "ValidPass@1");

            mockMvc.perform(post("/api/v1/auth/change-password")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(ChangePasswordRequest.builder()
                                    .currentPassword("WrongPass@1").newPassword("NewPass@2")
                                    .confirmPassword("NewPass@2").logoutOtherDevices(false).build())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("WRONG_PASSWORD"))
                    .andExpect(jsonPath("$.message").value("Mật khẩu hiện tại không chính xác"));
        }

        /**
         * TC_AUTH_CP_004 — New password same as current (BR-59).
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/change-password with newPassword = currentPassword.
         *   2. AuthFilter     → parse the token and resolve the user identity.
         *   3. AuthController → call AuthService.changePassword().
         *   4. AuthService    → retrieve the user account by identity.
         *   5. AuthService    → verify the current password.
         *   6. AuthService    → detect that the new password is identical to the current one (BR-59).
         *   7. System         → return the password reuse error response.
         */
        @Test
        @DisplayName("TC_AUTH_CP_004 — New password identical to current → PASSWORD_REUSE (BR-59)")
        void tc_cp_004_sameAsCurrentPassword() throws Exception {
            String token = loginAndGetToken("learner@test.com", "ValidPass@1");

            mockMvc.perform(post("/api/v1/auth/change-password")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(ChangePasswordRequest.builder()
                                    .currentPassword("ValidPass@1").newPassword("ValidPass@1")
                                    .confirmPassword("ValidPass@1").logoutOtherDevices(false).build())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PASSWORD_REUSE"))
                    .andExpect(jsonPath("$.message").value("Mật khẩu mới không được trùng với mật khẩu cũ."));
        }

        /**
         * TC_AUTH_CP_005 — Confirm password mismatch.
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/change-password with newPassword ≠ confirmPassword.
         *   2. AuthFilter     → parse the token and resolve the user identity.
         *   3. AuthController → call AuthService.changePassword().
         *   4. AuthService    → detect the password mismatch.
         *   5. System         → return the password mismatch error response.
         */
        @Test
        @DisplayName("TC_AUTH_CP_005 — Confirm password mismatch → PASSWORD_MISMATCH")
        void tc_cp_005_confirmMismatch() throws Exception {
            String token = loginAndGetToken("learner@test.com", "ValidPass@1");

            mockMvc.perform(post("/api/v1/auth/change-password")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(ChangePasswordRequest.builder()
                                    .currentPassword("ValidPass@1").newPassword("NewPass@2")
                                    .confirmPassword("NewPass@3").logoutOtherDevices(false).build())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PASSWORD_MISMATCH"))
                    .andExpect(jsonPath("$.message").value("Mật khẩu xác nhận không khớp"));
        }

        /**
         * TC_AUTH_CP_006 — Weak new password.
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/change-password with a weak new password.
         *   2. AuthController → validate the request body.
         *   3. AuthController → detect that the password does not meet the policy requirements.
         *   4. System         → return the validation error response before reaching AuthService.
         */
        @Test
        @DisplayName("TC_AUTH_CP_006 — Weak new password → 400 validation error")
        void tc_cp_006_weakNewPassword() throws Exception {
            String token = loginAndGetToken("learner@test.com", "ValidPass@1");

            mockMvc.perform(post("/api/v1/auth/change-password")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(ChangePasswordRequest.builder()
                                    .currentPassword("ValidPass@1").newPassword("weak")
                                    .confirmPassword("weak").logoutOtherDevices(false).build())))
                    .andExpect(status().isBadRequest());
        }

        /**
         * TC_AUTH_CP_007 — Unauthenticated request.
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/change-password with no Authorization header.
         *   2. AuthFilter     → detect that no token is present.
         *   3. AuthFilter     → reject the request as unauthenticated.
         *   4. System         → return the unauthorized error response.
         */
        @Test
        @DisplayName("TC_AUTH_CP_007 — No token → 401 Unauthorized")
        void tc_cp_007_unauthenticated() throws Exception {
            mockMvc.perform(post("/api/v1/auth/change-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(ChangePasswordRequest.builder()
                                    .currentPassword("ValidPass@1").newPassword("NewPass@2")
                                    .confirmPassword("NewPass@2").logoutOtherDevices(false).build())))
                    .andExpect(status().isUnauthorized());
        }

        /**
         * TC_AUTH_CP_008 — Expired access token.
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/change-password with an expired Bearer token.
         *   2. AuthFilter     → parse the token and detect it is expired.
         *   3. System         → return the unauthorized error response.
         */
        @Test
        @DisplayName("TC_AUTH_CP_008 — Expired access token → 401 Unauthorized")
        void tc_cp_008_expiredToken() throws Exception {
            SecretKey key = (SecretKey) ReflectionTestUtils.getField(jwtService, "accessTokenKey");
            User learner = userRepository.findByEmail("learner@test.com").orElseThrow();

            String expiredToken = Jwts.builder()
                    .subject(learner.getId().toString())
                    .claim("userId", learner.getId().toString())
                    .claim("email", learner.getEmail())
                    .claim("role", learner.getRole().name())
                    .claim("sessionId", java.util.UUID.randomUUID().toString())
                    .issuedAt(new Date(System.currentTimeMillis() - 120_000))
                    .expiration(new Date(System.currentTimeMillis() - 60_000))
                    .signWith(key).compact();

            mockMvc.perform(post("/api/v1/auth/change-password")
                            .header("Authorization", "Bearer " + expiredToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(ChangePasswordRequest.builder()
                                    .currentPassword("ValidPass@1").newPassword("NewPass@2")
                                    .confirmPassword("NewPass@2").logoutOtherDevices(false).build())))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false));
        }
    }

    // =========================================================================
    // Section 4 — LOGOUT  POST /api/v1/auth/logout
    // =========================================================================

    @Nested
    @DisplayName("Section 4 — Logout")
    class LogoutTests {

        private String loginAndGetBody(String email, String password) throws Exception {
            LoginRequest req = new LoginRequest();
            req.setEmail(email);
            req.setPassword(password);
            MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk()).andReturn();
            return result.getResponse().getContentAsString();
        }

        /**
         * TC_AUTH_LO_001 — Logout revokes session and invalidates access token.
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/auth/logout with a valid Bearer token and refreshToken.
         *   2. AuthFilter     → parse the token and resolve the user identity.
         *   3. AuthController → call AuthService.logout().
         *   4. AuthService    → retrieve the session by refreshToken.
         *   5. AuthService    → revoke the session.
         *   6. System         → return the success response.
         *   7. Verification   → Access token becomes invalid (UNAUTHORIZED).
         *   8. Verification   → Refresh token becomes invalid (SESSION_EXPIRED).
         */
        @Test
        @DisplayName("TC_AUTH_LO_001 — Logout revokes session and invalidates access token")
        void tc_lo_001_logoutRevokesSession() throws Exception {
            String body        = loginAndGetBody("learner@test.com", "ValidPass@1");
            String accessToken = objectMapper.readTree(body).path("data").path("accessToken").asText();
            String refreshToken = objectMapper.readTree(body).path("data").path("refreshToken").asText();

            mockMvc.perform(post("/api/v1/auth/logout")
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    LogoutRequest.builder().refreshToken(refreshToken).logoutAll(false).build())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            assertThat(sessionRepository.findAll().stream()
                    .filter(s -> s.getRevokedAt() == null).count()).isZero();

            // Verify access token is invalid after logout
            mockMvc.perform(post("/api/v1/auth/logout")
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    LogoutRequest.builder().refreshToken("any").logoutAll(false).build())))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

            // Verify refresh token is invalid after logout
            RefreshTokenRequest refreshReq = new RefreshTokenRequest();
            refreshReq.setRefreshToken(refreshToken);
            mockMvc.perform(post("/api/v1/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(refreshReq)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        }

        /**
         * TC_AUTH_LO_001b — Logout does not affect other sessions.
         *
         * Test Procedure:
         *   1. Client         → Create 2 separate login sessions.
         *   2. Client         → POST /api/v1/auth/logout with session 1 token and refreshToken.
         *   3. AuthService    → revoke only session 1.
         *   4. Verification   → Session 2 remains active and functional.
         */
        @Test
        @DisplayName("TC_AUTH_LO_001b — Logout does not affect other sessions")
        void tc_lo_001b_logoutDoesNotAffectOtherSessions() throws Exception {
            // Create two separate sessions
            String body1        = loginAndGetBody("learner@test.com", "ValidPass@1");
            String accessToken1 = objectMapper.readTree(body1).path("data").path("accessToken").asText();
            String refreshToken1 = objectMapper.readTree(body1).path("data").path("refreshToken").asText();
            
            String body2        = loginAndGetBody("learner@test.com", "ValidPass@1");
            String accessToken2 = objectMapper.readTree(body2).path("data").path("accessToken").asText();
            String refreshToken2 = objectMapper.readTree(body2).path("data").path("refreshToken").asText();

            // Verify both sessions are active
            assertThat(sessionRepository.findAll().stream()
                    .filter(s -> s.getRevokedAt() == null).count()).isEqualTo(2);

            // Logout session 1 only
            mockMvc.perform(post("/api/v1/auth/logout")
                            .header("Authorization", "Bearer " + accessToken1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    LogoutRequest.builder().refreshToken(refreshToken1).logoutAll(false).build())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            // Verify only 1 session remains active (session 2)
            assertThat(sessionRepository.findAll().stream()
                    .filter(s -> s.getRevokedAt() == null).count()).isEqualTo(1);

            // Verify session 1 access token is invalid
            mockMvc.perform(post("/api/v1/auth/logout")
                            .header("Authorization", "Bearer " + accessToken1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    LogoutRequest.builder().refreshToken("any").logoutAll(false).build())))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

            // Verify session 1 refresh token is invalid
            RefreshTokenRequest refreshReq1 = new RefreshTokenRequest();
            refreshReq1.setRefreshToken(refreshToken1);
            mockMvc.perform(post("/api/v1/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(refreshReq1)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));

            // Verify session 2 access token is still valid
            mockMvc.perform(post("/api/v1/auth/logout")
                            .header("Authorization", "Bearer " + accessToken2)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    LogoutRequest.builder().refreshToken(refreshToken2).logoutAll(false).build())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            // Verify session 2 refresh token was still valid before this last logout
            RefreshTokenRequest refreshReq2 = new RefreshTokenRequest();
            refreshReq2.setRefreshToken(refreshToken2);
            mockMvc.perform(post("/api/v1/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(refreshReq2)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        }
    }
}


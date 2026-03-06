package com.fu.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.infrastructure.email.EmailService;
import com.fu.user.dto.CreateUserRequest;
import com.fu.user.dto.RestoreUserRequest;
import com.fu.user.dto.UpdateUserRequest;
import com.fu.user.dto.auth.LoginRequest;
import com.fu.user.entity.*;
import com.fu.user.repository.UserRepository;
import com.fu.user.repository.UserSessionRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for UserController — User Management use cases.
 *
 * Runs from the start module so all modules are on the classpath —
 * no cross-module entity issues.
 *
 * Requires: docker exec satori-postgres psql -U satori_user -d satori_learning
 *   -c "CREATE DATABASE satori_test OWNER satori_user;"
 *
 * Coverage:
 * Section 1 — View User List      TC_UM_VUL_001–004
 * Section 2 — Create User         TC_UM_CU_001–009
 * Section 3 — View User Detail    TC_UM_VUI_001–002
 * Section 4 — Update User         TC_UM_UU_001–002
 * Section 5 — Delete User         TC_UM_DU_001–004
 * Section 6 — Restore User        TC_UM_RU_001–005
 */
@SpringBootTest(classes = com.fu.StartApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("UserController — Integration Tests (PostgreSQL satori_test)")
class UserControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository sessionRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockBean
    private EmailService emailService;

    // ── Setup / Teardown ──────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        doNothing().when(emailService).sendWelcomeEmailWithCredentials(any(), any(), any(), any());
        doNothing().when(emailService).sendPasswordResetOtp(any(), any(), any(), anyInt());
        doNothing().when(emailService).sendPasswordChangedNotification(any(), any());
    }

    @AfterEach
    void tearDown() {
        sessionRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private User buildAndSaveUser(String email, UserRole role, UserStatus status) {
        return userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode("ValidPass@1"))
                .firstName("Test")
                .lastName(role.name())
                .role(role)
                .status(status)
                .authProvider(AuthProvider.LOCAL)
                .failedLoginAttempts(0)
                .build());
    }

    private User buildAndSaveDeletedUser(String email, UserRole role) {
        User u = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode("ValidPass@1"))
                .firstName("Deleted")
                .lastName(role.name())
                .role(role)
                .status(UserStatus.DELETED)
                .authProvider(AuthProvider.LOCAL)
                .failedLoginAttempts(0)
                .build();
        u = userRepository.save(u);
        u.setDeletedAt(LocalDateTime.now().minusDays(1));
        u.setDeletedBy(UUID.randomUUID());
        return userRepository.save(u);
    }

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

    // =========================================================================
    // Section 1 — VIEW USER LIST  GET /api/v1/users
    // =========================================================================

    @Nested
    @DisplayName("Section 1 — View User List")
    class ViewUserListTests {

        /**
         * TC_UM_VUL_001 — MANAGER views user list.
         *
         * Test Procedure:
         *   1. Client         → GET /api/v1/users with a valid MANAGER Bearer token.
         *   2. AuthFilter     → parse the token and resolve the user identity.
         *   3. AuthController → call UserService.getUsers().
         *   4. UserService    → retrieve the user list by MANAGER scope (TEACHER and LEARNER only).
         *   5. UserService    → filter out ADMIN, MANAGER, and soft-deleted users.
         *   6. System         → return the paginated user list response.
         */
        @Test
        @DisplayName("TC_UM_VUL_001 — MANAGER views list → returns only TEACHER and LEARNER, no ADMIN/MANAGER/deleted")
        void tc_vul_001_managerSeesOnlyLearnerAndTeacher() throws Exception {
            buildAndSaveUser("manager@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            buildAndSaveUser("teacher@test.com", UserRole.TEACHER, UserStatus.ACTIVE);
            buildAndSaveUser("learner@test.com", UserRole.LEARNER, UserStatus.ACTIVE);
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            buildAndSaveDeletedUser("deleted.learner@test.com", UserRole.LEARNER);

            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            MvcResult result = mockMvc.perform(get("/api/v1/users")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andReturn();

            var content = objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("data").path("content");

            assertThat(content.isArray()).isTrue();
            assertThat(content.size()).isGreaterThanOrEqualTo(2);

            var emails = new java.util.ArrayList<String>();
            for (var node : content) {
                assertThat(node.path("role").asText()).isIn("TEACHER", "LEARNER");
                assertThat(node.path("email").asText()).isNotEqualTo("deleted.learner@test.com");
                emails.add(node.path("email").asText());
            }
            assertThat(emails).doesNotContain("manager@test.com", "admin@test.com");
        }

        /**
         * TC_UM_VUL_002 — ADMIN views user list.
         *
         * Test Procedure:
         *   1. Client         → GET /api/v1/users with a valid ADMIN Bearer token.
         *   2. AuthFilter     → parse the token and resolve the user identity.
         *   3. AuthController → call UserService.getUsers().
         *   4. UserService    → retrieve the user list by ADMIN scope (all roles, including deleted).
         *   5. System         → return the paginated user list response.
         */
        @Test
        @DisplayName("TC_UM_VUL_002 — ADMIN views list → sees all users including MANAGER, ADMIN, and deleted")
        void tc_vul_002_adminSeesAllUsers() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            buildAndSaveUser("manager2@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            buildAndSaveUser("teacher@test.com", UserRole.TEACHER, UserStatus.ACTIVE);
            buildAndSaveUser("learner@test.com", UserRole.LEARNER, UserStatus.ACTIVE);
            buildAndSaveDeletedUser("deleted.learner@test.com", UserRole.LEARNER);

            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

            MvcResult result = mockMvc.perform(get("/api/v1/users")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andReturn();

            String body = result.getResponse().getContentAsString();
            long totalElements = objectMapper.readTree(body).path("data").path("totalElements").asLong();
            assertThat(totalElements).isGreaterThanOrEqualTo(5);

            var roles = new java.util.ArrayList<String>();
            objectMapper.readTree(body).path("data").path("content")
                    .forEach(n -> roles.add(n.path("role").asText()));
            assertThat(roles).contains("MANAGER", "ADMIN");
        }

        /**
         * TC_UM_VUL_003 — MANAGER filters by role.
         *
         * Test Procedure:
         *   1. Client         → GET /api/v1/users?role=TEACHER with a valid MANAGER Bearer token.
         *   2. AuthFilter     → parse the token and resolve the user identity.
         *   3. AuthController → call UserService.getUsers() with role filter.
         *   4. UserService    → retrieve the user list filtered by role=TEACHER within MANAGER scope.
         *   5. System         → return the filtered paginated user list response.
         */
        @Test
        @DisplayName("TC_UM_VUL_003 — MANAGER filters role=TEACHER → all items are TEACHER, totalElements=2")
        void tc_vul_003_managerFiltersTeacher() throws Exception {
            buildAndSaveUser("manager@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            buildAndSaveUser("teacher1@test.com", UserRole.TEACHER, UserStatus.ACTIVE);
            buildAndSaveUser("teacher2@test.com", UserRole.TEACHER, UserStatus.ACTIVE);
            buildAndSaveUser("learner1@test.com", UserRole.LEARNER, UserStatus.ACTIVE);
            buildAndSaveUser("learner2@test.com", UserRole.LEARNER, UserStatus.ACTIVE);

            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            MvcResult result = mockMvc.perform(get("/api/v1/users")
                            .param("role", "TEACHER")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn();

            String body = result.getResponse().getContentAsString();
            var data = objectMapper.readTree(body).path("data");

            assertThat(data.path("totalElements").asLong()).isEqualTo(2);
            data.path("content").forEach(node ->
                    assertThat(node.path("role").asText()).isEqualTo("TEACHER"));
        }

        /**
         * TC_UM_VUL_004 — MANAGER searches by keyword.
         *
         * Test Procedure:
         *   1. Client         → GET /api/v1/users?keyword=nguyen with a valid MANAGER Bearer token.
         *   2. AuthFilter     → parse the token and resolve the user identity.
         *   3. AuthController → call UserService.getUsers() with keyword filter.
         *   4. UserService    → retrieve the user list filtered by keyword within MANAGER scope.
         *   5. System         → return the filtered paginated user list response.
         */
        @Test
        @DisplayName("TC_UM_VUL_004 — MANAGER searches keyword=nguyen → returns matching LEARNER")
        void tc_vul_004_managerSearchByKeyword() throws Exception {
            buildAndSaveUser("manager@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            userRepository.save(User.builder()
                    .email("nguyen@test.com")
                    .passwordHash(passwordEncoder.encode("ValidPass@1"))
                    .firstName("Nguyen")
                    .lastName("Van A")
                    .role(UserRole.LEARNER)
                    .status(UserStatus.ACTIVE)
                    .authProvider(AuthProvider.LOCAL)
                    .failedLoginAttempts(0)
                    .build());
            buildAndSaveUser("other.learner@test.com", UserRole.LEARNER, UserStatus.ACTIVE);

            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            MvcResult result = mockMvc.perform(get("/api/v1/users")
                            .param("keyword", "nguyen")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn();

            var content = objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("data").path("content");

            boolean found = false;
            for (var node : content) {
                if ("nguyen@test.com".equals(node.path("email").asText())) found = true;
                assertThat(node.path("role").asText()).isIn("TEACHER", "LEARNER");
            }
            assertThat(found).as("Expected to find nguyen@test.com in results").isTrue();
        }
    }

    // =========================================================================
    // Section 2 — CREATE USER  POST /api/v1/users
    // =========================================================================

    @Nested
    @DisplayName("Section 2 — Create User")
    class CreateUserTests {

        /**
         * TC_UM_CU_001 — MANAGER creates LEARNER.
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/users with a valid MANAGER Bearer token and user data.
         *   2. AuthFilter     → parse the token and resolve the user identity.
         *   3. AuthController → call UserService.createUser().
         *   4. UserService    → validate the role permission (MANAGER can create LEARNER/TEACHER).
         *   5. UserService    → check that the email does not already exist.
         *   6. UserService    → generate a temporary password and save the new user.
         *   7. UserService    → send a welcome email via EmailService.
         *   8. System         → return the created user response.
         */
        @Test
        @DisplayName("TC_UM_CU_001 — MANAGER creates LEARNER → 201, status=ACTIVE, email sent once")
        void tc_cu_001_managerCreatesLearner() throws Exception {
            buildAndSaveUser("manager@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            CreateUserRequest req = CreateUserRequest.builder()
                    .email("newlearner@test.com")
                    .firstName("New")
                    .lastName("Learner")
                    .role(UserRole.LEARNER)
                    .build();

            mockMvc.perform(post("/api/v1/users")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.email").value("newlearner@test.com"))
                    .andExpect(jsonPath("$.data.role").value("LEARNER"))
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.id").isNotEmpty());

            User saved = userRepository.findByEmail("newlearner@test.com").orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(saved.getDeletedAt()).isNull();
            assertThat(saved.getPasswordHash()).isNotBlank();

            verify(emailService, times(1))
                    .sendWelcomeEmailWithCredentials(eq("newlearner@test.com"), any(), any(), any());
        }

        /**
         * TC_UM_CU_003 — ADMIN creates MANAGER.
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/users with a valid ADMIN Bearer token and user data.
         *   2. AuthFilter     → parse the token and resolve the user identity.
         *   3. AuthController → call UserService.createUser().
         *   4. UserService    → validate the role permission (only ADMIN can create MANAGER).
         *   5. UserService    → check that the email does not already exist.
         *   6. UserService    → generate a temporary password and save the new user.
         *   7. UserService    → send a welcome email via EmailService.
         *   8. System         → return the created user response.
         */
        @Test
        @DisplayName("TC_UM_CU_003 — ADMIN creates MANAGER → 201, status=ACTIVE")
        void tc_cu_003_adminCreatesManager() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

            CreateUserRequest req = CreateUserRequest.builder()
                    .email("newmanager@test.com")
                    .firstName("New")
                    .lastName("Manager")
                    .role(UserRole.MANAGER)
                    .build();

            mockMvc.perform(post("/api/v1/users")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.role").value("MANAGER"))
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        }

        /**
         * TC_UM_CU_004 — Duplicate active email.
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/users with an email that already belongs to an active user.
         *   2. AuthFilter     → parse the token and resolve the user identity.
         *   3. AuthController → call UserService.createUser().
         *   4. UserService    → check that the email does not already exist.
         *   5. UserService    → detect that the email is already in use by an active account.
         *   6. System         → return the email exists error response.
         */
        @Test
        @DisplayName("TC_UM_CU_004 — Duplicate active email → 400 EMAIL_EXISTS")
        void tc_cu_004_duplicateActiveEmail() throws Exception {
            buildAndSaveUser("manager@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            buildAndSaveUser("exists@test.com", UserRole.LEARNER, UserStatus.ACTIVE);
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            mockMvc.perform(post("/api/v1/users")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(CreateUserRequest.builder()
                                    .email("exists@test.com").firstName("New")
                                    .lastName("User").role(UserRole.LEARNER).build())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("EMAIL_EXISTS"));
        }

        /**
         * TC_UM_CU_005 — Email belongs to a soft-deleted user.
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/users with an email that belongs to a soft-deleted user.
         *   2. AuthFilter     → parse the token and resolve the user identity.
         *   3. AuthController → call UserService.createUser().
         *   4. UserService    → check that the email does not already exist.
         *   5. UserService    → detect that a deleted account exists with the same email.
         *   6. System         → return the deleted user conflict response with the existing userId.
         */
        @Test
        @DisplayName("TC_UM_CU_005 — Email belongs to deleted user → 409 DELETED_USER_EXISTS with userId")
        void tc_cu_005_deletedEmailConflict() throws Exception {
            buildAndSaveUser("manager@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            buildAndSaveDeletedUser("deleted@test.com", UserRole.LEARNER);
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            mockMvc.perform(post("/api/v1/users")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(CreateUserRequest.builder()
                                    .email("deleted@test.com").firstName("New")
                                    .lastName("User").role(UserRole.LEARNER).build())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DELETED_USER_EXISTS"))
                    .andExpect(jsonPath("$.data.userId").isNotEmpty())
                    .andExpect(jsonPath("$.data.email").value("deleted@test.com"))
                    .andExpect(jsonPath("$.data.status").value("DELETED"));
        }

        /**
         * TC_UM_CU_006 — MANAGER creates MANAGER role (BR-11).
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/users with role=MANAGER using a MANAGER Bearer token.
         *   2. AuthFilter     → parse the token and resolve the user identity.
         *   3. AuthController → call UserService.createUser().
         *   4. UserService    → validate the role permission.
         *   5. UserService    → detect that MANAGER is not allowed to create MANAGER accounts (BR-11).
         *   6. System         → return the invalid role error response.
         */
        @Test
        @DisplayName("TC_UM_CU_006 — MANAGER creates MANAGER role → 400 INVALID_ROLE (BR-11)")
        void tc_cu_006_managerCannotCreateManager() throws Exception {
            buildAndSaveUser("manager@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            mockMvc.perform(post("/api/v1/users")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(CreateUserRequest.builder()
                                    .email("mgr@test.com").firstName("New")
                                    .lastName("Mgr").role(UserRole.MANAGER).build())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ROLE"));
        }

        /**
         * TC_UM_CU_007 — Blank firstName.
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/users with firstName = "".
         *   2. AuthFilter     → parse the token and resolve the user identity.
         *   3. AuthController → validate the request body.
         *   4. AuthController → detect that firstName is blank.
         *   5. System         → return the validation error response before reaching UserService.
         */
        @Test
        @DisplayName("TC_UM_CU_007 — Blank firstName → 400 VALIDATION_ERROR with firstName error")
        void tc_cu_007_blankFirstName() throws Exception {
            buildAndSaveUser("manager@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            mockMvc.perform(post("/api/v1/users")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"valid@test.com\",\"firstName\":\"\",\"lastName\":\"User\",\"role\":\"LEARNER\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.data.firstName").isNotEmpty());
        }

        /**
         * TC_UM_CU_008 — Invalid email format.
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/users with an invalid email format.
         *   2. AuthFilter     → parse the token and resolve the user identity.
         *   3. AuthController → validate the request body.
         *   4. AuthController → detect that the email format is invalid.
         *   5. System         → return the validation error response before reaching UserService.
         */
        @Test
        @DisplayName("TC_UM_CU_008 — Invalid email format → 400 VALIDATION_ERROR with email error")
        void tc_cu_008_invalidEmailFormat() throws Exception {
            buildAndSaveUser("manager@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            mockMvc.perform(post("/api/v1/users")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"not-an-email\",\"firstName\":\"New\",\"lastName\":\"User\",\"role\":\"LEARNER\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.data.email").value("Invalid email format"));
        }

        /**
         * TC_UM_CU_009 — Unauthenticated request.
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/users with no Authorization header.
         *   2. AuthFilter     → detect that no token is present.
         *   3. AuthFilter     → reject the request as unauthenticated.
         *   4. System         → return the unauthorized error response.
         */
        @Test
        @DisplayName("TC_UM_CU_009 — No Authorization header → 401 Unauthorized")
        void tc_cu_009_unauthenticated() throws Exception {
            mockMvc.perform(post("/api/v1/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"x@test.com\",\"firstName\":\"X\",\"lastName\":\"Y\",\"role\":\"LEARNER\"}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // =========================================================================
    // Section 3 — VIEW USER INFORMATION  GET /api/v1/users/{id}/details
    // =========================================================================

    @Nested
    @DisplayName("Section 3 — View User Information")
    class ViewUserInformationTests {

        /**
         * TC_UM_VUI_001 — MANAGER views LEARNER detail.
         *
         * Test Procedure:
         *   1. Client         → GET /api/v1/users/{id}/details with a valid MANAGER Bearer token.
         *   2. AuthFilter     → parse the token and resolve the user identity.
         *   3. AuthController → call UserService.getUserDetail().
         *   4. UserService    → validate that the target user is within MANAGER scope.
         *   5. UserService    → retrieve the user profile and enrollment data.
         *   6. System         → return the user detail response.
         */
        @Test
        @DisplayName("TC_UM_VUI_001 — MANAGER views LEARNER detail → 200, profile + enrollment arrays")
        void tc_vui_001_managerViewsLearnerDetail() throws Exception {
            buildAndSaveUser("manager@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            User learner = buildAndSaveUser("learner@test.com", UserRole.LEARNER, UserStatus.ACTIVE);
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            mockMvc.perform(get("/api/v1/users/{id}/details", learner.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.userInfo.role").value("LEARNER"))
                    .andExpect(jsonPath("$.data.userInfo.email").value("learner@test.com"))
                    .andExpect(jsonPath("$.data.userInfo.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.courseEnrollments").isArray())
                    .andExpect(jsonPath("$.data.classEnrollments").isArray());
        }

        /**
         * TC_UM_VUI_002 — MANAGER views MANAGER detail (outside scope).
         *
         * Test Procedure:
         *   1. Client         → GET /api/v1/users/{id}/details with a MANAGER token targeting another MANAGER.
         *   2. AuthFilter     → parse the token and resolve the user identity.
         *   3. AuthController → call UserService.getUserDetail().
         *   4. UserService    → validate that the target user is within MANAGER scope.
         *   5. UserService    → detect that MANAGER is outside scope (target is also MANAGER).
         *   6. System         → return the forbidden error response.
         */
        @Test
        @DisplayName("TC_UM_VUI_002 — MANAGER views MANAGER detail → 400 FORBIDDEN (outside scope)")
        void tc_vui_002_managerViewsManagerDetail_forbidden() throws Exception {
            buildAndSaveUser("manager1@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            User manager2 = buildAndSaveUser("manager2@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            String token = loginAndGetToken("manager1@test.com", "ValidPass@1");

            mockMvc.perform(get("/api/v1/users/{id}/details", manager2.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    // =========================================================================
    // Section 4 — UPDATE USER  PUT /api/v1/users/{id}
    // =========================================================================

    @Nested
    @DisplayName("Section 4 — Update User Information")
    class UpdateUserTests {

        /**
         * TC_UM_UU_001 — MANAGER updates LEARNER's firstName.
         *
         * Test Procedure:
         *   1. Client         → PUT /api/v1/users/{id} with a valid MANAGER Bearer token and updated fields.
         *   2. AuthFilter     → parse the token and resolve the user identity.
         *   3. AuthController → call UserService.updateUser().
         *   4. UserService    → validate that the target user is within MANAGER scope.
         *   5. UserService    → apply the updates and save to the database.
         *   6. System         → return the updated user response.
         */
        @Test
        @DisplayName("TC_UM_UU_001 — MANAGER updates LEARNER firstName → 200, updated in response and DB")
        void tc_uu_001_managerUpdatesLearnerName() throws Exception {
            buildAndSaveUser("manager@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            User learner = userRepository.save(User.builder()
                    .email("learner@test.com")
                    .passwordHash(passwordEncoder.encode("ValidPass@1"))
                    .firstName("Old").lastName("Name")
                    .role(UserRole.LEARNER).status(UserStatus.ACTIVE)
                    .authProvider(AuthProvider.LOCAL).failedLoginAttempts(0)
                    .build());

            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            LocalDateTime beforeUpdate = LocalDateTime.now().minusSeconds(1);

            mockMvc.perform(put("/api/v1/users/{id}", learner.getId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(UpdateUserRequest.builder()
                                    .firstName("Updated").email("learner@test.com").build())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.firstName").value("Updated"));

            User updated = userRepository.findById(learner.getId()).orElseThrow();
            assertThat(updated.getFirstName()).isEqualTo("Updated");
            assertThat(updated.getUpdatedAt()).isAfter(beforeUpdate);
        }

        /**
         * TC_UM_UU_002 — Update with status=DELETED (BR-invalid).
         *
         * Test Procedure:
         *   1. Client         → PUT /api/v1/users/{id} with status=DELETED in the request body.
         *   2. AuthFilter     → parse the token and resolve the user identity.
         *   3. AuthController → call UserService.updateUser().
         *   4. UserService    → validate that the requested status change is allowed.
         *   5. UserService    → detect that status=DELETED is not a valid update value.
         *   6. System         → return the invalid status error response.
         */
        @Test
        @DisplayName("TC_UM_UU_002 — Update status=DELETED → 400 INVALID_STATUS, DB status unchanged")
        void tc_uu_002_updateDeletedStatus_returns400() throws Exception {
            buildAndSaveUser("manager@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            User learner = buildAndSaveUser("learner@test.com", UserRole.LEARNER, UserStatus.ACTIVE);
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            mockMvc.perform(put("/api/v1/users/{id}", learner.getId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(UpdateUserRequest.builder()
                                    .status(UserStatus.DELETED).email("learner@test.com").build())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_STATUS"));

            assertThat(userRepository.findById(learner.getId()).orElseThrow().getStatus())
                    .isEqualTo(UserStatus.ACTIVE);
        }
    }

    // =========================================================================
    // Section 5 — DELETE USER  DELETE /api/v1/users/{id}
    // =========================================================================

    @Nested
    @DisplayName("Section 5 — Delete User")
    class DeleteUserTests {

        /**
         * TC_UM_DU_001 — MANAGER soft-deletes LEARNER.
         *
         * Test Procedure:
         *   1. Client         → DELETE /api/v1/users/{id} with a valid MANAGER Bearer token.
         *   2. AuthFilter     → parse the token and resolve the user identity.
         *   3. AuthController → call UserService.deleteUser().
         *   4. UserService    → validate that the target user is within MANAGER scope.
         *   5. UserService    → set deletedAt and deletedBy on the user account.
         *   6. System         → return the success response.
         */
        @Test
        @DisplayName("TC_UM_DU_001 — MANAGER soft-deletes LEARNER → 200, deletedAt set, not visible in list")
        void tc_du_001_managerSoftDeletesLearner() throws Exception {
            User manager = buildAndSaveUser("manager@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            User learner = buildAndSaveUser("learner@test.com", UserRole.LEARNER, UserStatus.ACTIVE);
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            mockMvc.perform(delete("/api/v1/users/{id}", learner.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Xóa người dùng thành công"))
                    .andExpect(jsonPath("$.data").doesNotExist());

            User deleted = userRepository.findById(learner.getId()).orElseThrow();
            assertThat(deleted.getDeletedAt()).isNotNull();
            assertThat(deleted.getDeletedBy()).isEqualTo(manager.getId());
            assertThat(deleted.getStatus()).isEqualTo(UserStatus.DELETED);
            assertThat(deleted.getEmail()).isEqualTo("learner@test.com");

            MvcResult listResult = mockMvc.perform(get("/api/v1/users")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andReturn();

            var emails = new java.util.ArrayList<String>();
            objectMapper.readTree(listResult.getResponse().getContentAsString())
                    .path("data").path("content")
                    .forEach(n -> emails.add(n.path("email").asText()));
            assertThat(emails).doesNotContain("learner@test.com");
        }

        /**
         * TC_UM_DU_002 — ADMIN soft-deletes MANAGER.
         *
         * Test Procedure:
         *   1. Client         → DELETE /api/v1/users/{id} with a valid ADMIN Bearer token.
         *   2. AuthFilter     → parse the token and resolve the user identity.
         *   3. AuthController → call UserService.deleteUser().
         *   4. UserService    → validate that the target user is within ADMIN scope.
         *   5. UserService    → set deletedAt and deletedBy on the user account.
         *   6. System         → return the success response.
         */
        @Test
        @DisplayName("TC_UM_DU_002 — ADMIN soft-deletes MANAGER → 200, deletedAt set, deletedBy=adminId")
        void tc_du_002_adminSoftDeletesManager() throws Exception {
            User admin = buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            User manager = buildAndSaveUser("targetmanager@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

            mockMvc.perform(delete("/api/v1/users/{id}", manager.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            User deletedManager = userRepository.findById(manager.getId()).orElseThrow();
            assertThat(deletedManager.getDeletedAt()).isNotNull();
            assertThat(deletedManager.getDeletedBy()).isEqualTo(admin.getId());
        }

        /**
         * TC_UM_DU_003 — MANAGER deletes MANAGER account (BR-33).
         *
         * Test Procedure:
         *   1. Client         → DELETE /api/v1/users/{id} with a MANAGER token targeting another MANAGER.
         *   2. AuthFilter     → parse the token and resolve the user identity.
         *   3. AuthController → call UserService.deleteUser().
         *   4. UserService    → validate that the target user is within MANAGER scope.
         *   5. UserService    → detect that MANAGER cannot delete MANAGER accounts (BR-33).
         *   6. System         → return the forbidden error response.
         */
        @Test
        @DisplayName("TC_UM_DU_003 — MANAGER deletes MANAGER account → 400 FORBIDDEN (BR-33)")
        void tc_du_003_managerCannotDeleteManager() throws Exception {
            buildAndSaveUser("manager1@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            User manager2 = buildAndSaveUser("manager2@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            String token = loginAndGetToken("manager1@test.com", "ValidPass@1");

            mockMvc.perform(delete("/api/v1/users/{id}", manager2.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }

        /**
         * TC_UM_DU_004 — Delete already-deleted user (BR-34).
         *
         * Test Procedure:
         *   1. Client         → DELETE /api/v1/users/{id} where the target user is already soft-deleted.
         *   2. AuthFilter     → parse the token and resolve the user identity.
         *   3. AuthController → call UserService.deleteUser().
         *   4. UserService    → attempt to retrieve the user by id.
         *   5. UserService    → detect that the user does not exist (soft-deleted treated as not found).
         *   6. System         → return the not found error response.
         */
        @Test
        @DisplayName("TC_UM_DU_004 — Delete already-deleted user → 404 NOT_FOUND (BR-34)")
        void tc_du_004_deleteAlreadyDeletedUser() throws Exception {
            buildAndSaveUser("manager@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            User alreadyDeleted = buildAndSaveDeletedUser("already.deleted@test.com", UserRole.LEARNER);
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            mockMvc.perform(delete("/api/v1/users/{id}", alreadyDeleted.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    // =========================================================================
    // Section 6 — RESTORE USER  POST /api/v1/users/restore
    // =========================================================================

    @Nested
    @DisplayName("Section 6 — Restore User Account")
    class RestoreUserTests {

        /**
         * TC_UM_RU_001 — MANAGER restores deleted LEARNER.
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/users/restore with a valid MANAGER Bearer token and restore data.
         *   2. AuthFilter     → parse the token and resolve the user identity.
         *   3. AuthController → call UserService.restoreUser().
         *   4. UserService    → validate that the target user is within MANAGER scope.
         *   5. UserService    → validate that the user is currently soft-deleted.
         *   6. UserService    → clear deletedAt and deletedBy, set status to ACTIVE.
         *   7. UserService    → generate a new temporary password and save.
         *   8. UserService    → send a welcome email via EmailService.
         *   9. System         → return the restored user response.
         */
        @Test
        @DisplayName("TC_UM_RU_001 — MANAGER restores deleted LEARNER → 200, ACTIVE, deletedAt=null, email sent")
        void tc_ru_001_managerRestoresLearner() throws Exception {
            buildAndSaveUser("manager@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            User deleted = buildAndSaveDeletedUser("restore@test.com", UserRole.LEARNER);
            String oldPasswordHash = deleted.getPasswordHash();
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            mockMvc.perform(post("/api/v1/users/restore")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(RestoreUserRequest.builder()
                                    .userId(deleted.getId()).email("restore@test.com")
                                    .firstName("Restored").lastName("Learner").role(UserRole.LEARNER).build())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.email").value("restore@test.com"))
                    .andExpect(jsonPath("$.data.firstName").value("Restored"));

            User restored = userRepository.findById(deleted.getId()).orElseThrow();
            assertThat(restored.getDeletedAt()).isNull();
            assertThat(restored.getDeletedBy()).isNull();
            assertThat(restored.getStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(restored.getPasswordHash()).isNotEqualTo(oldPasswordHash);

            verify(emailService, times(1))
                    .sendWelcomeEmailWithCredentials(eq("restore@test.com"), any(), any(), any());
        }

        /**
         * TC_UM_RU_002 — ADMIN restores deleted MANAGER.
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/users/restore with a valid ADMIN Bearer token and restore data.
         *   2. AuthFilter     → parse the token and resolve the user identity.
         *   3. AuthController → call UserService.restoreUser().
         *   4. UserService    → validate that the target user is within ADMIN scope.
         *   5. UserService    → validate that the user is currently soft-deleted.
         *   6. UserService    → clear deletedAt and deletedBy, set status to ACTIVE.
         *   7. UserService    → generate a new temporary password and save.
         *   8. UserService    → send a welcome email via EmailService.
         *   9. System         → return the restored user response.
         */
        @Test
        @DisplayName("TC_UM_RU_002 — ADMIN restores deleted MANAGER → 200, role=MANAGER, status=ACTIVE")
        void tc_ru_002_adminRestoresManager() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            User deletedMgr = buildAndSaveDeletedUser("mgr@test.com", UserRole.MANAGER);
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

            mockMvc.perform(post("/api/v1/users/restore")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(RestoreUserRequest.builder()
                                    .userId(deletedMgr.getId()).email("mgr@test.com")
                                    .firstName("Restored").lastName("Mgr").role(UserRole.MANAGER).build())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.role").value("MANAGER"))
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        }

        /**
         * TC_UM_RU_004 — Restore non-deleted user (BR-42).
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/users/restore with the id of an active (non-deleted) user.
         *   2. AuthFilter     → parse the token and resolve the user identity.
         *   3. AuthController → call UserService.restoreUser().
         *   4. UserService    → validate that the user is currently soft-deleted.
         *   5. UserService    → detect that the user is not deleted (BR-42).
         *   6. System         → return the user not deleted error response.
         */
        @Test
        @DisplayName("TC_UM_RU_004 — Restore active (non-deleted) user → 400 USER_NOT_DELETED (BR-42)")
        void tc_ru_004_restoreNonDeletedUser() throws Exception {
            buildAndSaveUser("manager@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            User activeLearner = buildAndSaveUser("active@test.com", UserRole.LEARNER, UserStatus.ACTIVE);
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            mockMvc.perform(post("/api/v1/users/restore")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(RestoreUserRequest.builder()
                                    .userId(activeLearner.getId()).email("active@test.com")
                                    .firstName("Active").lastName("Learner").role(UserRole.LEARNER).build())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("USER_NOT_DELETED"));
        }

        /**
         * TC_UM_RU_005 — MANAGER restores deleted MANAGER (BR-40).
         *
         * Test Procedure:
         *   1. Client         → POST /api/v1/users/restore with a MANAGER token targeting a deleted MANAGER.
         *   2. AuthFilter     → parse the token and resolve the user identity.
         *   3. AuthController → call UserService.restoreUser().
         *   4. UserService    → validate that the target user is within MANAGER scope.
         *   5. UserService    → detect that MANAGER cannot restore MANAGER accounts (BR-40).
         *   6. System         → return the forbidden error response.
         */
        @Test
        @DisplayName("TC_UM_RU_005 — MANAGER restores deleted MANAGER → 400 FORBIDDEN (BR-40)")
        void tc_ru_005_managerCannotRestoreManager() throws Exception {
            buildAndSaveUser("manager@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            User deletedMgr = buildAndSaveDeletedUser("deletedmgr@test.com", UserRole.MANAGER);
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            mockMvc.perform(post("/api/v1/users/restore")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(RestoreUserRequest.builder()
                                    .userId(deletedMgr.getId()).email("deletedmgr@test.com")
                                    .firstName("Mgr").lastName("Test").role(UserRole.MANAGER).build())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }
}


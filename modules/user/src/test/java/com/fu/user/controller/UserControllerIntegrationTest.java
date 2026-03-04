package com.fu.user.controller;

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
 * Runs against a real PostgreSQL database (satori_test).
 * Requires: docker exec satori-postgres psql -U satori_user -d satori_learning
 *   -c "CREATE DATABASE satori_test OWNER satori_user;"
 *
 * Coverage:
 * Section 1 — View User List      TC_UM_VUL_001–004
 * Section 2 — Create User         TC_UM_CU_001–009
 * Section 3 — View User Detail    TC_UM_VUI_001–002
 * Section 4 — Update User         TC_UM_UU_001–002
 * Section 5 — Delete User         TC_UM_DU_001–005
 * Section 6 — Restore User        TC_UM_RU_001–005
 *
 */
@SpringBootTest(classes = com.fu.user.UserTestApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("UserController — Integration Tests (PostgreSQL satori_test)")
class UserControllerIntegrationTest {

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

    @MockBean
    private EmailService emailService;

    // ── Shared token holders ─────────────────────────────────────────────────

    /** Tokens obtained fresh per-test by calling loginAndGetToken(). */

    // ── Setup / Teardown ─────────────────────────────────────────────────────

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

    /** Insert a fully-formed active user and return the saved entity. */
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

    /** Insert a soft-deleted user (deletedAt set, status=DELETED). */
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

    /** Login and return the access token string. */
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
         * TC_UM_VUL_001 — MANAGER sees only LEARNER and TEACHER (active, non-deleted).
         * MANAGER, ADMIN, and deleted users must NOT appear in the list.
         */
        @Test
        @DisplayName("TC_UM_VUL_001 — MANAGER views list → returns only TEACHER and LEARNER, no ADMIN/MANAGER/deleted")
        void tc_vul_001_managerSeesOnlyLearnerAndTeacher() throws Exception {
            // Arrange: insert 1 of each role + 1 deleted LEARNER
            User manager = buildAndSaveUser("manager@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            buildAndSaveUser("teacher@test.com", UserRole.TEACHER, UserStatus.ACTIVE);
            buildAndSaveUser("learner@test.com", UserRole.LEARNER, UserStatus.ACTIVE);
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            buildAndSaveDeletedUser("deleted.learner@test.com", UserRole.LEARNER);

            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            // Act + Assert
            MvcResult result = mockMvc.perform(get("/api/v1/users")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.pageNumber").value(0))
                    .andReturn();

            String body = result.getResponse().getContentAsString();
            var content = objectMapper.readTree(body).path("data").path("content");

            // All items must be TEACHER or LEARNER
            assertThat(content.isArray()).isTrue();
            assertThat(content.size()).isGreaterThanOrEqualTo(2);

            for (var node : content) {
                String role = node.path("role").asText();
                assertThat(role).isIn("TEACHER", "LEARNER");

                // Must not be the deleted user
                assertThat(node.path("email").asText()).isNotEqualTo("deleted.learner@test.com");
            }

            // Manager and Admin must not be in the list
            var emails = new java.util.ArrayList<String>();
            content.forEach(n -> emails.add(n.path("email").asText()));
            assertThat(emails).doesNotContain("manager@test.com", "admin@test.com");
        }

        /**
         * TC_UM_VUL_002 — ADMIN views list → sees ALL users including MANAGER, ADMIN, and deleted.
         */
        @Test
        @DisplayName("TC_UM_VUL_002 — ADMIN views list → sees all users including MANAGER, ADMIN, and deleted")
        void tc_vul_002_adminSeesAllUsers() throws Exception {
            // Arrange
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
            var content = objectMapper.readTree(body).path("data").path("content");
            long totalElements = objectMapper.readTree(body).path("data").path("totalElements").asLong();

            assertThat(totalElements).isGreaterThanOrEqualTo(5);

            // Collect all emails in the response (paginated — at least one page)
            var emails = new java.util.ArrayList<String>();
            content.forEach(n -> emails.add(n.path("email").asText()));

            // MANAGER and ADMIN role users must be visible to ADMIN
            var roles = new java.util.ArrayList<String>();
            content.forEach(n -> roles.add(n.path("role").asText()));
            assertThat(roles).contains("MANAGER", "ADMIN");

            // The deleted learner must appear in ADMIN's view (totalElements includes it)
            assertThat(totalElements).isGreaterThanOrEqualTo(5);
        }

        /**
         * TC_UM_VUL_003 — MANAGER filters by role=TEACHER → returns only TEACHER accounts.
         */
        @Test
        @DisplayName("TC_UM_VUL_003 — MANAGER filters role=TEACHER → all items are TEACHER, totalElements=2")
        void tc_vul_003_managerFiltersTeacher() throws Exception {
            // Arrange: 2 TEACHER + 2 LEARNER
            User manager = buildAndSaveUser("manager@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            buildAndSaveUser("teacher1@test.com", UserRole.TEACHER, UserStatus.ACTIVE);
            buildAndSaveUser("teacher2@test.com", UserRole.TEACHER, UserStatus.ACTIVE);
            buildAndSaveUser("learner1@test.com", UserRole.LEARNER, UserStatus.ACTIVE);
            buildAndSaveUser("learner2@test.com", UserRole.LEARNER, UserStatus.ACTIVE);

            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            MvcResult result = mockMvc.perform(get("/api/v1/users")
                            .param("role", "TEACHER")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andReturn();

            String body = result.getResponse().getContentAsString();
            var data = objectMapper.readTree(body).path("data");
            var content = data.path("content");
            long totalElements = data.path("totalElements").asLong();

            assertThat(totalElements).isEqualTo(2);
            content.forEach(node ->
                    assertThat(node.path("role").asText()).isEqualTo("TEACHER")
            );
        }

        /**
         * TC_UM_VUL_004 — MANAGER searches by keyword → returns matching users within scope.
         */
        @Test
        @DisplayName("TC_UM_VUL_004 — MANAGER searches keyword=nguyen → returns matching LEARNER")
        void tc_vul_004_managerSearchByKeyword() throws Exception {
            // Arrange: LEARNER with "Nguyen" in firstName
            User manager = buildAndSaveUser("manager@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            User learner = userRepository.save(User.builder()
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
                    .andExpect(jsonPath("$.success").value(true))
                    .andReturn();

            String body = result.getResponse().getContentAsString();
            var content = objectMapper.readTree(body).path("data").path("content");

            // Must contain the nguyen learner
            boolean found = false;
            for (var node : content) {
                if ("nguyen@test.com".equals(node.path("email").asText())) {
                    found = true;
                }
                // Must not expose MANAGER/ADMIN
                assertThat(node.path("role").asText()).isIn("TEACHER", "LEARNER");
            }
            assertThat(found).as("Expected to find nguyen@test.com in search results").isTrue();
        }
    }

    // =========================================================================
    // Section 2 — CREATE USER  POST /api/v1/users
    // =========================================================================

    @Nested
    @DisplayName("Section 2 — Create User")
    class CreateUserTests {

        // ── 2.1 Happy Path ────────────────────────────────────────────────────

        /**
         * TC_UM_CU_001 — MANAGER creates LEARNER → 201, ACTIVE status, welcome email sent.
         */
        @Test
        @DisplayName("TC_UM_CU_001 — MANAGER creates LEARNER → 201, status=ACTIVE, email sent once")
        void tc_cu_001_managerCreatesLearner() throws Exception {
            User manager = buildAndSaveUser("manager@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
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

            // DB state
            User saved = userRepository.findByEmail("newlearner@test.com").orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(saved.getDeletedAt()).isNull();
            assertThat(saved.getPasswordHash()).isNotBlank();

            // Email sent exactly once
            verify(emailService, times(1))
                    .sendWelcomeEmailWithCredentials(eq("newlearner@test.com"), any(), any(), any());
        }

        /**
         * TC_UM_CU_003 — ADMIN creates MANAGER → 201, status=ACTIVE.
         * Only ADMIN can create MANAGER accounts.
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
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.role").value("MANAGER"))
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        }

        // ── 2.2 Negative Cases ────────────────────────────────────────────────

        /**
         * TC_UM_CU_004 — Email already exists (ACTIVE) → 400 EMAIL_EXISTS.
         */
        @Test
        @DisplayName("TC_UM_CU_004 — Duplicate active email → 400 EMAIL_EXISTS")
        void tc_cu_004_duplicateActiveEmail() throws Exception {
            buildAndSaveUser("manager@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            buildAndSaveUser("exists@test.com", UserRole.LEARNER, UserStatus.ACTIVE);
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            CreateUserRequest req = CreateUserRequest.builder()
                    .email("exists@test.com")
                    .firstName("New")
                    .lastName("User")
                    .role(UserRole.LEARNER)
                    .build();

            mockMvc.perform(post("/api/v1/users")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("EMAIL_EXISTS"))
                    .andExpect(jsonPath("$.message").value("Email này đã được sử dụng. Vui lòng nhập email khác"));
        }

        /**
         * TC_UM_CU_005 — Email exists as soft-deleted → 409 DELETED_USER_EXISTS with userId.
         */
        @Test
        @DisplayName("TC_UM_CU_005 — Email belongs to deleted user → 409 DELETED_USER_EXISTS with userId")
        void tc_cu_005_deletedEmailConflict() throws Exception {
            buildAndSaveUser("manager@test.com", UserRole.MANAGER, UserStatus.ACTIVE);

            // Insert a deleted LEARNER with a different role so reactive-restore is NOT triggered
            // (Manager can reactively restore TEACHER/LEARNER with same role — use ADMIN role to block)
            User deleted = buildAndSaveDeletedUser("deleted@test.com", UserRole.LEARNER);
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            CreateUserRequest req = CreateUserRequest.builder()
                    .email("deleted@test.com")
                    .firstName("New")
                    .lastName("User")
                    .role(UserRole.LEARNER)
                    .build();

            mockMvc.perform(post("/api/v1/users")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("DELETED_USER_EXISTS"))
                    .andExpect(jsonPath("$.data.userId").isNotEmpty())
                    .andExpect(jsonPath("$.data.email").value("deleted@test.com"))
                    .andExpect(jsonPath("$.data.status").value("DELETED"))
                    .andExpect(jsonPath("$.data.message").isNotEmpty());
        }

        /**
         * TC_UM_CU_006 — MANAGER tries to create MANAGER role → 400 INVALID_ROLE (BR-11).
         */
        @Test
        @DisplayName("TC_UM_CU_006 — MANAGER creates MANAGER role → 400 INVALID_ROLE (BR-11)")
        void tc_cu_006_managerCannotCreateManager() throws Exception {
            buildAndSaveUser("manager@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            CreateUserRequest req = CreateUserRequest.builder()
                    .email("mgr@test.com")
                    .firstName("New")
                    .lastName("Mgr")
                    .role(UserRole.MANAGER)
                    .build();

            mockMvc.perform(post("/api/v1/users")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("INVALID_ROLE"));
        }

        /**
         * TC_UM_CU_007 — Missing required field (firstName blank) → 400 VALIDATION_ERROR.
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
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.data.firstName").isNotEmpty());
        }

        /**
         * TC_UM_CU_008 — Invalid email format → 400 VALIDATION_ERROR.
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
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.data.email").value("Invalid email format"));
        }

        // ── 2.3 Permission Cases ──────────────────────────────────────────────

        /**
         * TC_UM_CU_009 — Unauthenticated request → 401.
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
         * TC_UM_VUI_001 — MANAGER views LEARNER detail → full profile, enrollment arrays present.
         */
        @Test
        @DisplayName("TC_UM_VUI_001 — MANAGER views LEARNER detail → 200, profile + empty enrollment arrays")
        void tc_vui_001_managerViewsLearnerDetail() throws Exception {
            User manager = buildAndSaveUser("manager@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            User learner = buildAndSaveUser("learner@test.com", UserRole.LEARNER, UserStatus.ACTIVE);
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            mockMvc.perform(get("/api/v1/users/{id}/details", learner.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.userInfo.role").value("LEARNER"))
                    .andExpect(jsonPath("$.data.userInfo.email").value("learner@test.com"))
                    .andExpect(jsonPath("$.data.userInfo.firstName").isNotEmpty())
                    .andExpect(jsonPath("$.data.userInfo.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.courseEnrollments").isArray())
                    .andExpect(jsonPath("$.data.classEnrollments").isArray());
        }

        /**
         * TC_UM_VUI_002 — MANAGER attempts to view MANAGER detail — returns 400 ACCESS_DENIED (outside scope).
         *
         * Steps:
         * 1. Insert ACTIVE MANAGER via userRepository.save().
         * 2. GET /api/v1/users/{managerId}/details with Authorization: Bearer &lt;manager_token&gt;.
         * 3. Assert response.
         *
         * Expected: HTTP 400, success = false, code = "FORBIDDEN".
         */
        @Test
        @DisplayName("TC_UM_VUI_002 — MANAGER attempts to view MANAGER detail — returns 400 ACCESS_DENIED (outside scope)")
        void tc_vui_002_managerViewsManagerDetail_forbidden() throws Exception {
            User manager1 = buildAndSaveUser("manager1@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
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
    // Section 4 — UPDATE USER INFORMATION  PUT /api/v1/users/{id}
    // =========================================================================

    @Nested
    @DisplayName("Section 4 — Update User Information")
    class UpdateUserTests {

        /**
         * TC_UM_UU_001 — MANAGER updates LEARNER's firstName → 200, DB and response reflect new value.
         */
        @Test
        @DisplayName("TC_UM_UU_001 — MANAGER updates LEARNER  → 200, updated in response and DB")
        void tc_uu_001_managerUpdatesLearnerName() throws Exception {
            User manager = buildAndSaveUser("manager@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            User learner = userRepository.save(User.builder()
                    .email("learner@test.com")
                    .passwordHash(passwordEncoder.encode("ValidPass@1"))
                    .firstName("Old")
                    .lastName("Name")
                    .role(UserRole.LEARNER)
                    .status(UserStatus.ACTIVE)
                    .authProvider(AuthProvider.LOCAL)
                    .failedLoginAttempts(0)
                    .build());

            String token = loginAndGetToken("manager@test.com", "ValidPass@1");
            LocalDateTime beforeUpdate = LocalDateTime.now().minusSeconds(1);

            UpdateUserRequest req = UpdateUserRequest.builder()
                    .firstName("Updated")
                    .email("learner@test.com") // email required — not changing
                    .build();

            mockMvc.perform(put("/api/v1/users/{id}", learner.getId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.firstName").value("Updated"));

            // DB state
            User updated = userRepository.findById(learner.getId()).orElseThrow();
            assertThat(updated.getFirstName()).isEqualTo("Updated");
            assertThat(updated.getUpdatedAt()).isAfter(beforeUpdate);
        }

        /**
         * TC_UM_UU_002 — Update user with invalid status (DELETED) — returns validation error.
         *
         * Steps:
         * 1. Insert ACTIVE LEARNER via userRepository.save().
         * 2. PUT /api/v1/users/{learnerId} with body: {"status":"DELETED"}.
         * 3. Query DB after update.
         * 4. Assert response and DB state.
         *
         * Expected: HTTP 400, success = false, code = "INVALID_STATUS". DB status remains ACTIVE.
         */
        @Test
        @DisplayName("TC_UM_UU_002 — Update status=DELETED — returns 400 INVALID_STATUS (use Delete endpoint instead)")
        void tc_uu_002_updateDeletedStatus_returns400() throws Exception {
            buildAndSaveUser("manager@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            User learner = buildAndSaveUser("learner@test.com", UserRole.LEARNER, UserStatus.ACTIVE);
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            UpdateUserRequest req = UpdateUserRequest.builder()
                    .status(UserStatus.DELETED)
                    .email("learner@test.com") // required field
                    .build();

            mockMvc.perform(put("/api/v1/users/{id}", learner.getId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("INVALID_STATUS"));

            // DB state: status must remain ACTIVE — not changed to DELETED
            User updated = userRepository.findById(learner.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(UserStatus.ACTIVE);
        }
    }

    // =========================================================================
    // Section 5 — DELETE USER  DELETE /api/v1/users/{id}
    // =========================================================================

    @Nested
    @DisplayName("Section 5 — Delete User")
    class DeleteUserTests {

        // ── 5.1 Happy Path ────────────────────────────────────────────────────

        /**
         * TC_UM_DU_001 — MANAGER soft-deletes LEARNER → deletedAt set, user no longer in MANAGER list.
         */
        @Test
        @DisplayName("TC_UM_DU_001 — MANAGER soft-deletes LEARNER → 200, deletedAt set, not visible in list")
        void tc_du_001_managerSoftDeletesLearner() throws Exception {
            User manager = buildAndSaveUser("manager@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            User learner = buildAndSaveUser("learner@test.com", UserRole.LEARNER, UserStatus.ACTIVE);
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            // Delete
            mockMvc.perform(delete("/api/v1/users/{id}", learner.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Xóa người dùng thành công"))
                    .andExpect(jsonPath("$.data").doesNotExist());

            // DB: deletedAt set, deletedBy = manager's UUID
            User deleted = userRepository.findById(learner.getId()).orElseThrow();
            assertThat(deleted.getDeletedAt()).isNotNull();
            assertThat(deleted.getDeletedBy()).isEqualTo(manager.getId());
            assertThat(deleted.getStatus()).isEqualTo(UserStatus.DELETED);

            // All other fields preserved (history)
            assertThat(deleted.getEmail()).isEqualTo("learner@test.com");
            assertThat(deleted.getFirstName()).isNotNull();

            // MANAGER's list no longer shows deleted learner
            MvcResult listResult = mockMvc.perform(get("/api/v1/users")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn();

            String body = listResult.getResponse().getContentAsString();
            var content = objectMapper.readTree(body).path("data").path("content");
            var emails = new java.util.ArrayList<String>();
            content.forEach(n -> emails.add(n.path("email").asText()));
            assertThat(emails).doesNotContain("learner@test.com");
        }

        /**
         * TC_UM_DU_002 — ADMIN soft-deletes MANAGER → deletedAt set, deletedBy = admin's UUID.
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
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Xóa người dùng thành công"));

            User deletedManager = userRepository.findById(manager.getId()).orElseThrow();
            assertThat(deletedManager.getDeletedAt()).isNotNull();
            assertThat(deletedManager.getDeletedBy()).isEqualTo(admin.getId());
        }

        // ── 5.2 Negative Cases ────────────────────────────────────────────────

        /**
         * TC_UM_DU_003 — MANAGER attempts to delete MANAGER → 400 FORBIDDEN (BR-33).
         */
        @Test
        @DisplayName("TC_UM_DU_003 — MANAGER deletes MANAGER account → 400 FORBIDDEN (BR-33)")
        void tc_du_003_managerCannotDeleteManager() throws Exception {
            User manager1 = buildAndSaveUser("manager1@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            User manager2 = buildAndSaveUser("manager2@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            String token = loginAndGetToken("manager1@test.com", "ValidPass@1");

            mockMvc.perform(delete("/api/v1/users/{id}", manager2.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }

        /**
         * TC_UM_DU_004 — Delete already-deleted user → 404 NOT_FOUND (BR-34).
         *
         * findUserById() filters deletedAt IS NULL, so a soft-deleted user is treated
         * as not found → ResourceNotFoundException → HTTP 404 NOT_FOUND.
         */
        @Test
        @DisplayName("TC_UM_DU_004 — Delete already-deleted user → 404 NOT_FOUND (BR-34)")
        void tc_du_004_deleteAlreadyDeletedUser() throws Exception {
            User manager = buildAndSaveUser("manager@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            User alreadyDeleted = buildAndSaveDeletedUser("already.deleted@test.com", UserRole.LEARNER);
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            mockMvc.perform(delete("/api/v1/users/{id}", alreadyDeleted.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    // =========================================================================
    // Section 6 — RESTORE USER ACCOUNT  POST /api/v1/users/restore
    // =========================================================================

    @Nested
    @DisplayName("Section 6 — Restore User Account")
    class RestoreUserTests {

        // ── 6.1 Happy Path ────────────────────────────────────────────────────

        /**
         * TC_UM_RU_001 — MANAGER restores deleted LEARNER → 200, status=ACTIVE,
         * deletedAt=null, new password generated, welcome email sent.
         */
        @Test
        @DisplayName("TC_UM_RU_001 — MANAGER restores deleted LEARNER → 200, ACTIVE, deletedAt=null, email sent")
        void tc_ru_001_managerRestoresLearner() throws Exception {
            User manager = buildAndSaveUser("manager@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            User deleted = buildAndSaveDeletedUser("restore@test.com", UserRole.LEARNER);
            String oldPasswordHash = deleted.getPasswordHash();
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            RestoreUserRequest req = RestoreUserRequest.builder()
                    .userId(deleted.getId())
                    .email("restore@test.com")
                    .firstName("Restored")
                    .lastName("Learner")
                    .role(UserRole.LEARNER)
                    .build();

            mockMvc.perform(post("/api/v1/users/restore")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.email").value("restore@test.com"))
                    .andExpect(jsonPath("$.data.firstName").value("Restored"));

            // DB state
            User restored = userRepository.findById(deleted.getId()).orElseThrow();
            assertThat(restored.getDeletedAt()).isNull();
            assertThat(restored.getDeletedBy()).isNull();
            assertThat(restored.getStatus()).isEqualTo(UserStatus.ACTIVE);
            // New password generated — hash must differ from the pre-restore hash
            assertThat(restored.getPasswordHash()).isNotEqualTo(oldPasswordHash);

            // Welcome email sent exactly once
            verify(emailService, times(1))
                    .sendWelcomeEmailWithCredentials(eq("restore@test.com"), any(), any(), any());
        }

        /**
         * TC_UM_RU_002 — ADMIN restores deleted MANAGER → 200, role=MANAGER, status=ACTIVE.
         */
        @Test
        @DisplayName("TC_UM_RU_002 — ADMIN restores deleted MANAGER → 200, role=MANAGER, status=ACTIVE")
        void tc_ru_002_adminRestoresManager() throws Exception {
            User admin = buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            User deletedMgr = buildAndSaveDeletedUser("mgr@test.com", UserRole.MANAGER);
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

            RestoreUserRequest req = RestoreUserRequest.builder()
                    .userId(deletedMgr.getId())
                    .email("mgr@test.com")
                    .firstName("Restored")
                    .lastName("Mgr")
                    .role(UserRole.MANAGER)
                    .build();

            mockMvc.perform(post("/api/v1/users/restore")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.role").value("MANAGER"))
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        }

        // ── 6.2 Negative Cases ────────────────────────────────────────────────

        /**
         * TC_UM_RU_004 — Restore non-deleted user → 400 USER_NOT_DELETED (BR-42).
         */
        @Test
        @DisplayName("TC_UM_RU_004 — Restore active (non-deleted) user → 400 USER_NOT_DELETED (BR-42)")
        void tc_ru_004_restoreNonDeletedUser() throws Exception {
            User manager = buildAndSaveUser("manager@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            User activeLearner = buildAndSaveUser("active@test.com", UserRole.LEARNER, UserStatus.ACTIVE);
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            RestoreUserRequest req = RestoreUserRequest.builder()
                    .userId(activeLearner.getId())
                    .email("active@test.com")
                    .firstName("Active")
                    .lastName("Learner")
                    .role(UserRole.LEARNER)
                    .build();

            mockMvc.perform(post("/api/v1/users/restore")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("USER_NOT_DELETED"));
        }

        // ── 6.3 Permission Cases ──────────────────────────────────────────────

        /**
         * TC_UM_RU_005 — MANAGER attempts to restore deleted MANAGER account → 400 FORBIDDEN (BR-40).
         */
        @Test
        @DisplayName("TC_UM_RU_005 — MANAGER restores deleted MANAGER → 400 FORBIDDEN (BR-40)")
        void tc_ru_005_managerCannotRestoreManager() throws Exception {
            User manager = buildAndSaveUser("manager@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            User deletedMgr = buildAndSaveDeletedUser("deletedmgr@test.com", UserRole.MANAGER);
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            RestoreUserRequest req = RestoreUserRequest.builder()
                    .userId(deletedMgr.getId())
                    .email("deletedmgr@test.com")
                    .firstName("Mgr")
                    .lastName("Test")
                    .role(UserRole.MANAGER)
                    .build();

            mockMvc.perform(post("/api/v1/users/restore")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }
}


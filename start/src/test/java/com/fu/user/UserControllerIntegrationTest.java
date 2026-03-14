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
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

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
@Transactional
@Sql(
        statements = "TRUNCATE TABLE user_management.users, user_management.user_sessions CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS
)
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
        User deleter = userRepository.save(User.builder()
                .email("deleter-" + UUID.randomUUID() + "@test.com")
                .passwordHash(passwordEncoder.encode("ValidPass@1"))
                .firstName("System")
                .lastName("Deleter")
                .role(UserRole.ADMIN)
                .status(UserStatus.ACTIVE)
                .authProvider(AuthProvider.LOCAL)
                .failedLoginAttempts(0)
                .build());

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
        u.setDeletedBy(deleter.getId());
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
    @DisplayName("Section 1 — View Account List")
    class ViewUserListTests {

        /**
         * TC_UM_VUL_001 — ADMIN views account list.
         *
         * Test Procedure:
         *   1. Client         → GET /api/v1/users with a valid ADMIN Bearer token.
         *   2. AuthFilter     → parse the token and resolve the user identity.
         *   3. AuthController → call UserService.getUsers().
         *   4. UserService    → retrieve the user list by ADMIN scope (all roles, all statuses).
         *   5. System         → return the paginated account list response.
         */
        @Test
        @DisplayName("TC_UM_VUL_001 — ADMIN views list → sees all roles and deleted")
        void tc_vul_001_adminSeesAllAccounts() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            buildAndSaveUser("manager@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
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
            assertThat(roles).contains("MANAGER", "ADMIN", "TEACHER", "LEARNER");
        }

        /**
         * TC_UM_VUL_002 — ADMIN filters by role.
         *
         * Test Procedure:
         *   1. Client         → GET /api/v1/users?role=TEACHER with a valid ADMIN Bearer token.
         *   2. AuthFilter     → parse the token and resolve the user identity.
         *   3. AuthController → call UserService.getUsers() with role filter.
         *   4. UserService    → retrieve the user list filtered by role.
         *   5. System         → return the filtered paginated account list response.
         */
        @Test
        @DisplayName("TC_UM_VUL_002 — ADMIN filters role=TEACHER → all items are TEACHER, totalElements=2")
        void tc_vul_002_adminFiltersTeacher() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            buildAndSaveUser("teacher1@test.com", UserRole.TEACHER, UserStatus.ACTIVE);
            buildAndSaveUser("teacher2@test.com", UserRole.TEACHER, UserStatus.ACTIVE);
            buildAndSaveUser("learner1@test.com", UserRole.LEARNER, UserStatus.ACTIVE);
            buildAndSaveUser("learner2@test.com", UserRole.LEARNER, UserStatus.ACTIVE);

            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

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
         * TC_UM_VUL_003 — ADMIN searches by keyword.
         *
         * Test Procedure:
         *   1. Client         → GET /api/v1/users?keyword=nguyen with a valid ADMIN Bearer token.
         *   2. AuthFilter     → parse the token and resolve the user identity.
         *   3. AuthController → call UserService.getUsers() with keyword filter.
         *   4. UserService    → retrieve the user list filtered by keyword.
         *   5. System         → return the filtered paginated account list response.
         */
        @Test
        @DisplayName("TC_UM_VUL_003 — ADMIN searches keyword=nguyen → returns matching LEARNER")
        void tc_vul_003_adminSearchByKeyword() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
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

            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

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
            }
            assertThat(found).as("Expected to find nguyen@test.com in results").isTrue();
        }
    }

    // =========================================================================
    // Section 2 — CREATE USER  POST /api/v1/users
    // =========================================================================

    @Nested
    @DisplayName("Section 2 — Create Account")
    class CreateUserTests {

        @Test
        @DisplayName("TC_UM_CU_001 — ADMIN creates LEARNER → 201, status=ACTIVE, email sent once")
        void tc_cu_001_adminCreatesLearner() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

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

        @Test
        @DisplayName("TC_UM_CU_004 — Duplicate active email → 400 EMAIL_EXISTS")
        void tc_cu_004_duplicateActiveEmail() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            buildAndSaveUser("exists@test.com", UserRole.LEARNER, UserStatus.ACTIVE);
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

            mockMvc.perform(post("/api/v1/users")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(CreateUserRequest.builder()
                                    .email("exists@test.com").firstName("New")
                                    .lastName("User").role(UserRole.LEARNER).build())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("EMAIL_EXISTS"));
        }

        @Test
        @DisplayName("TC_UM_CU_005 — Email belongs to deleted user → 409 DELETED_USER_EXISTS with userId")
        void tc_cu_005_deletedEmailConflict() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            buildAndSaveDeletedUser("deleted@test.com", UserRole.LEARNER);
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

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

        @Test
        @DisplayName("TC_UM_CU_006 — ADMIN creates MANAGER role → 201, status=ACTIVE")
        void tc_cu_006_adminCreatesManagerRole() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

            mockMvc.perform(post("/api/v1/users")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(CreateUserRequest.builder()
                                    .email("mgr@test.com").firstName("New")
                                    .lastName("Mgr").role(UserRole.MANAGER).build())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.role").value("MANAGER"))
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        }

        @Test
        @DisplayName("TC_UM_CU_007 — Blank firstName → 400 VALIDATION_ERROR with firstName error")
        void tc_cu_007_blankFirstName() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

            mockMvc.perform(post("/api/v1/users")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"valid@test.com\",\"firstName\":\"\",\"lastName\":\"User\",\"role\":\"LEARNER\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.data.firstName").isNotEmpty());
        }

        @Test
        @DisplayName("TC_UM_CU_008 — Invalid email format → 400 VALIDATION_ERROR with email error")
        void tc_cu_008_invalidEmailFormat() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

            mockMvc.perform(post("/api/v1/users")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"not-an-email\",\"firstName\":\"New\",\"lastName\":\"User\",\"role\":\"LEARNER\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.data.email").value("Invalid email format"));
        }

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
    @DisplayName("Section 3 — View Account Information")
    class ViewUserInformationTests {

        @Test
        @DisplayName("TC_UM_VUI_001 — ADMIN views LEARNER detail → 200, profile + enrollment arrays")
        void tc_vui_001_adminViewsLearnerDetail() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            User learner = buildAndSaveUser("learner@test.com", UserRole.LEARNER, UserStatus.ACTIVE);
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

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

        @Test
        @DisplayName("TC_UM_VUI_002 — ADMIN views MANAGER detail → 200")
        void tc_vui_002_adminViewsManagerDetail() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            User manager = buildAndSaveUser("manager2@test.com", UserRole.MANAGER, UserStatus.ACTIVE);
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

            mockMvc.perform(get("/api/v1/users/{id}/details", manager.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.userInfo.role").value("MANAGER"));
        }
    }

    // =========================================================================
    // Section 4 — UPDATE USER  PUT /api/v1/users/{id}
    // =========================================================================

    @Nested
    @DisplayName("Section 4 — Update Account Information")
    class UpdateUserTests {

        @Test
        @DisplayName("TC_UM_UU_001 — ADMIN updates LEARNER firstName → 200, updated in response and DB")
        void tc_uu_001_adminUpdatesLearnerName() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            User learner = userRepository.save(User.builder()
                    .email("learner@test.com")
                    .passwordHash(passwordEncoder.encode("ValidPass@1"))
                    .firstName("Old").lastName("Name")
                    .role(UserRole.LEARNER).status(UserStatus.ACTIVE)
                    .authProvider(AuthProvider.LOCAL).failedLoginAttempts(0)
                    .build());

            String token = loginAndGetToken("admin@test.com", "ValidPass@1");
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

        @Test
        @DisplayName("TC_UM_UU_002 — Update status=DELETED → 400 INVALID_STATUS, DB status unchanged")
        void tc_uu_002_updateDeletedStatus_returns400() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            User learner = buildAndSaveUser("learner@test.com", UserRole.LEARNER, UserStatus.ACTIVE);
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

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
    @DisplayName("Section 5 — Delete Account")
    class DeleteUserTests {

        @Test
        @DisplayName("TC_UM_DU_001 — ADMIN soft-deletes LEARNER → 200, deletedAt set, visible in list as DELETED")
        void tc_du_001_adminSoftDeletesLearner() throws Exception {
            User admin = buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            User learner = buildAndSaveUser("learner@test.com", UserRole.LEARNER, UserStatus.ACTIVE);
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

            mockMvc.perform(delete("/api/v1/users/{id}", learner.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Xóa người dùng thành công"))
                    .andExpect(jsonPath("$.data").doesNotExist());

            User deleted = userRepository.findById(learner.getId()).orElseThrow();
            assertThat(deleted.getDeletedAt()).isNotNull();
            assertThat(deleted.getDeletedBy()).isEqualTo(admin.getId());
            assertThat(deleted.getStatus()).isEqualTo(UserStatus.DELETED);
            assertThat(deleted.getEmail()).isEqualTo("learner@test.com");

            MvcResult listResult = mockMvc.perform(get("/api/v1/users")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andReturn();

            boolean foundDeleted = false;
            for (var node : objectMapper.readTree(listResult.getResponse().getContentAsString())
                    .path("data").path("content")) {
                if ("learner@test.com".equals(node.path("email").asText())) {
                    foundDeleted = true;
                    assertThat(node.path("status").asText()).isEqualTo("DELETED");
                }
            }
            assertThat(foundDeleted).as("Expected deleted account to remain visible in list").isTrue();
        }

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

        @Test
        @DisplayName("TC_UM_DU_003 — Delete already-deleted user → 404 NOT_FOUND (BR-34)")
        void tc_du_003_deleteAlreadyDeletedUser() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            User alreadyDeleted = buildAndSaveDeletedUser("already.deleted@test.com", UserRole.LEARNER);
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

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
    @DisplayName("Section 6 — Restore Account")
    class RestoreUserTests {

        @Test
        @DisplayName("TC_UM_RU_001 — ADMIN restores deleted LEARNER → 200, ACTIVE, deletedAt=null, credentials unchanged")
        void tc_ru_001_adminRestoresLearner() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            User deleted = buildAndSaveDeletedUser("restore@test.com", UserRole.LEARNER);
            String oldPasswordHash = deleted.getPasswordHash();
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

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
            assertThat(restored.getPasswordHash()).isEqualTo(oldPasswordHash);

            verify(emailService, times(0))
                    .sendWelcomeEmailWithCredentials(eq("restore@test.com"), any(), any(), any());
        }

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

        @Test
        @DisplayName("TC_UM_RU_004 — Restore active (non-deleted) user → 400 USER_NOT_DELETED (BR-42)")
        void tc_ru_004_restoreNonDeletedUser() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            User activeLearner = buildAndSaveUser("active@test.com", UserRole.LEARNER, UserStatus.ACTIVE);
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

            mockMvc.perform(post("/api/v1/users/restore")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(RestoreUserRequest.builder()
                                    .userId(activeLearner.getId()).email("active@test.com")
                                    .firstName("Active").lastName("Learner").role(UserRole.LEARNER).build())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("USER_NOT_DELETED"));
        }
    }
}


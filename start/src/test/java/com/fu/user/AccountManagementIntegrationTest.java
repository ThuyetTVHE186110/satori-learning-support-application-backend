package com.fu.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.infrastructure.email.EmailService;
import com.fu.user.dto.*;
import com.fu.user.dto.auth.LoginRequest;
import com.fu.user.entity.User;
import com.fu.user.entity.AuthProvider;
import com.fu.user.entity.UserStatus;
import com.fu.user.entity.UserRole;
import com.fu.user.repository.UserRepository;
import com.fu.user.repository.UserSessionRepository;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
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
import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = com.fu.StartApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Sql(
        statements = "TRUNCATE TABLE user_management.users, user_management.user_sessions CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS
)
@DisplayName("Account Management — Integration Tests (PostgreSQL satori_test)")
class AccountManagementIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionRepository sessionRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockBean
    private EmailService emailService;

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

    // ========================================
    // SECTION 1: VIEW ACCOUNT LIST TESTS
    // ========================================

    @Nested
    @DisplayName("Section 1 — View Account List")
    class ViewAccountListTests {

        @Test
        @DisplayName("TC_AM_VAL_001 — Admin view account list success (Manager, Teacher, Learner) (default pagination)")
        void tc_am_val_001_adminViewAccountListSuccess() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            buildAndSaveUser("manager@test.com", UserRole.CONTENT_MANAGER, UserStatus.ACTIVE);
            buildAndSaveUser("teacher@test.com", UserRole.TEACHER, UserStatus.ACTIVE);
            buildAndSaveUser("learner@test.com", UserRole.LEARNER, UserStatus.ACTIVE);
            buildAndSaveDeletedUser("deleted.learner@test.com", UserRole.LEARNER);

            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

            MvcResult result = mockMvc.perform(get("/api/v1/users")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.pageNumber").value(0))
                    .andExpect(jsonPath("$.data.pageSize").value(10))
                    .andExpect(jsonPath("$.data.first").value(true))
                    .andReturn();

            String body = result.getResponse().getContentAsString();
            var data = objectMapper.readTree(body).path("data");
            var content = data.path("content");

            assertThat(data.path("totalElements").asInt()).isGreaterThanOrEqualTo(4);

            var roles = new ArrayList<String>();
            boolean deletedFound = false;
            for (var node : content) {
                roles.add(node.path("role").asText());
                if ("deleted.learner@test.com".equals(node.path("email").asText())) {
                    deletedFound = true;
                    assertThat(node.path("status").asText()).isEqualTo("DELETED");
                }
            }
            assertThat(deletedFound).isTrue();
            assertThat(roles).contains("CONTENT_MANAGER", "TEACHER", "LEARNER", "ADMIN");
        }

        @Test
        @DisplayName("TC_AM_VAL_002 — Admin filter list by role and status")
        void tc_am_val_002_adminFilterListByRoleAndStatus() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            buildAndSaveUser("manager1@test.com", UserRole.CONTENT_MANAGER, UserStatus.ACTIVE);
            buildAndSaveUser("manager2@test.com", UserRole.CONTENT_MANAGER, UserStatus.SUSPENDED);
            buildAndSaveUser("teacher1@test.com", UserRole.TEACHER, UserStatus.ACTIVE);
            buildAndSaveUser("teacher2@test.com", UserRole.TEACHER, UserStatus.INACTIVE);

            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

            MvcResult result = mockMvc.perform(get("/api/v1/users")
                            .param("role", "CONTENT_MANAGER")
                            .param("status", "ACTIVE")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.pageNumber").value(0))
                    .andExpect(jsonPath("$.data.pageSize").value(10))
                    .andExpect(jsonPath("$.data.first").value(true))
                    .andExpect(jsonPath("$.data.last").value(true))
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andReturn();

            var data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
            assertThat(data.path("content").size()).isEqualTo(1);
            data.path("content").forEach(node -> {
                assertThat(node.path("role").asText()).isEqualTo("CONTENT_MANAGER");
                assertThat(node.path("status").asText()).isEqualTo("ACTIVE");
            });
        }

        @Test
        @DisplayName("TC_AM_VAL_003 — Admin search by keyword")
        void tc_am_val_003_adminSearchByKeyword() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            userRepository.save(User.builder()
                    .email("john.doe@test.com")
                    .passwordHash(passwordEncoder.encode("ValidPass@1"))
                    .firstName("John")
                    .lastName("Doe")
                    .role(UserRole.LEARNER)
                    .status(UserStatus.ACTIVE)
                    .authProvider(AuthProvider.LOCAL)
                    .failedLoginAttempts(0)
                    .build());
            userRepository.save(User.builder()
                    .email("jane.smith@test.com")
                    .passwordHash(passwordEncoder.encode("ValidPass@1"))
                    .firstName("Jane")
                    .lastName("Smith")
                    .role(UserRole.TEACHER)
                    .status(UserStatus.ACTIVE)
                    .authProvider(AuthProvider.LOCAL)
                    .failedLoginAttempts(0)
                    .build());
            buildAndSaveUser("other.user@test.com", UserRole.LEARNER, UserStatus.ACTIVE);

            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

            MvcResult result = mockMvc.perform(get("/api/v1/users")
                            .param("keyword", "john")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.pageNumber").value(0))
                    .andExpect(jsonPath("$.data.pageSize").value(10))
                    .andExpect(jsonPath("$.data.first").value(true))
                    .andExpect(jsonPath("$.data.last").value(true))
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andReturn();

            var content = objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("data").path("content");
            boolean found = false;
            for (var node : content) {
                if ("john.doe@test.com".equals(node.path("email").asText())) found = true;
            }
            assertThat(found).isTrue();
        }

        @Test
        @DisplayName("TC_AM_VAL_004 — Manager cannot view account list")
        void tc_am_val_004_managerCannotViewAccountList() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            buildAndSaveUser("manager@test.com", UserRole.CONTENT_MANAGER, UserStatus.ACTIVE);
            buildAndSaveUser("learner@test.com", UserRole.LEARNER, UserStatus.ACTIVE);
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            mockMvc.perform(get("/api/v1/users")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }
    }

    // =========================================================================
    // Section 2 — CREATE ACCOUNT  POST /api/v1/users
    // =========================================================================

    @Nested
    @DisplayName("Section 2 — Create Account")
    class CreateAccountTests {

        /**
         * TC_AM_CREATE_001a — Admin create LEARNER account → status = INACTIVE
         * 
         * Learners are created in INACTIVE status by default.
         * They need to activate their account via email verification.
         */
        @Test
        @DisplayName("TC_AM_CREATE_001a — Admin create LEARNER account → INACTIVE status")
        void tc_am_create_001a_adminCreatesLearnerAccountInactive() throws Exception {
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
                    .andExpect(jsonPath("$.data.firstName").value("New"))
                    .andExpect(jsonPath("$.data.lastName").value("Learner"))
                    .andExpect(jsonPath("$.data.role").value("LEARNER"))
                    .andExpect(jsonPath("$.data.status").value("INACTIVE"))
                    .andExpect(jsonPath("$.data.id").isNotEmpty())
                    .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                    .andExpect(jsonPath("$.data.updatedAt").isNotEmpty())
                    .andExpect(jsonPath("$.data.passwordHash").doesNotExist());

            User saved = userRepository.findByEmail("newlearner@test.com").orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(UserStatus.INACTIVE);

            verify(emailService, times(1))
                    .sendWelcomeEmailWithCredentials(org.mockito.ArgumentMatchers.eq("newlearner@test.com"),
                            any(), any(), any());
        }

        /**
         * TC_AM_CREATE_001b — Admin create non-LEARNER account (TEACHER) → status = ACTIVE
         * 
         * Non-learner roles (TEACHER, CONTENT_MANAGER, OPERATION_MANAGER, ADMIN) 
         * are created in ACTIVE status immediately.
         */
        @Test
        @DisplayName("TC_AM_CREATE_002 — Admin create TEACHER account → ACTIVE status")
        void tc_am_create_002_adminCreatesTeacherAccountActive() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

            CreateUserRequest req = CreateUserRequest.builder()
                    .email("newteacher@test.com")
                    .firstName("New")
                    .lastName("Teacher")
                    .role(UserRole.TEACHER)
                    .build();

            mockMvc.perform(post("/api/v1/users")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.email").value("newteacher@test.com"))
                    .andExpect(jsonPath("$.data.firstName").value("New"))
                    .andExpect(jsonPath("$.data.lastName").value("Teacher"))
                    .andExpect(jsonPath("$.data.role").value("TEACHER"))
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.id").isNotEmpty())
                    .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                    .andExpect(jsonPath("$.data.updatedAt").isNotEmpty())
                    .andExpect(jsonPath("$.data.passwordHash").doesNotExist());

            User saved = userRepository.findByEmail("newteacher@test.com").orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);

            verify(emailService, times(1))
                    .sendWelcomeEmailWithCredentials(org.mockito.ArgumentMatchers.eq("newteacher@test.com"),
                            any(), any(), any());
        }



        @Test
        @DisplayName("TC_AM_CREATE_003 — Email already exist")
        void tc_am_create_003_emailAlreadyExists() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            buildAndSaveUser("exists@test.com", UserRole.LEARNER, UserStatus.ACTIVE);
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

            mockMvc.perform(post("/api/v1/users")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(CreateUserRequest.builder()
                                    .email("exists@test.com")
                                    .firstName("New")
                                    .lastName("Account")
                                    .role(UserRole.LEARNER)
                                    .build())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("EMAIL_EXISTS"))
                    .andExpect(jsonPath("$.message").value("Email này đã được sử dụng. Vui lòng nhập email khác"));
        }

        @Test
        @DisplayName("TC_AM_CREATE_004 — Validation error")
        void tc_am_create_004_validationError() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

            mockMvc.perform(post("/api/v1/users")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"\",\"firstName\":\"\",\"lastName\":\"\",\"role\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("TC_AM_CREATE_005 — Email service failure")
        void tc_am_create_005_emailServiceFailure() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

            // Mock EmailService to throw exception
            Mockito.doThrow(new RuntimeException("Email service unavailable"))
                    .when(emailService).sendWelcomeEmailWithCredentials(any(), any(), any(), any());

            CreateUserRequest req = CreateUserRequest.builder()
                    .email("newaccount@test.com")
                    .firstName("New")
                    .lastName("Account")
                    .role(UserRole.LEARNER)
                    .build();

            mockMvc.perform(post("/api/v1/users")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

            // Verify account was still created despite email failure
            User saved = userRepository.findByEmail("newaccount@test.com").orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(UserStatus.INACTIVE);
        }

        @Test
        @DisplayName("TC_AM_CREATE_006 — Manager cannot create new account")
        void tc_am_create_006_managerCannotCreate() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            buildAndSaveUser("manager@test.com", UserRole.CONTENT_MANAGER, UserStatus.ACTIVE);
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            mockMvc.perform(post("/api/v1/users")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(CreateUserRequest.builder()
                                    .email("mnew@test.com").firstName("A").lastName("B")
                                    .role(UserRole.LEARNER).build())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }
    }

    // =========================================================================
    // Section 3 — VIEW ACCOUNT INFORMATION  GET /api/v1/users/{id}/details
    // =========================================================================

    @Nested
    @DisplayName("Section 3 — View Account Information")
    class ViewAccountInformationTests {

        @Test
        @DisplayName("TC_AM_VIEW_001 — Admin view success")
        void tc_am_view_001_adminViewsAccount() throws Exception {
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
                    .andExpect(jsonPath("$.data.userInfo.createdAt").isNotEmpty())
                    .andExpect(jsonPath("$.data.userInfo.updatedAt").isNotEmpty())
                    .andExpect(jsonPath("$.data.userInfo.firstName").isNotEmpty())
                    .andExpect(jsonPath("$.data.userInfo.lastName").isNotEmpty())
                    .andExpect(jsonPath("$.data.courseEnrollments").isArray())
                    .andExpect(jsonPath("$.data.classEnrollments").isArray());
        }

        @Test
        @DisplayName("TC_AM_VIEW_002 — Manager cannot view account information")
        void tc_am_view_002_managerCannotView() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            buildAndSaveUser("manager@test.com", UserRole.CONTENT_MANAGER, UserStatus.ACTIVE);
            User learner = buildAndSaveUser("learner@test.com", UserRole.LEARNER, UserStatus.ACTIVE);
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            mockMvc.perform(get("/api/v1/users/{id}/details", learner.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }
    }

    // =========================================================================
    // Section 4 — UPDATE ACCOUNT  PUT /api/v1/users/{id}
    // =========================================================================

    @Nested
    @DisplayName("Section 4 — Update Account Information")
    class UpdateAccountTests {

        @Test
        @DisplayName("TC_AM_UPDATE_001 — Admin update success")
        void tc_am_update_001_adminUpdatesAccount() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
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

            String token = loginAndGetToken("admin@test.com", "ValidPass@1");
            LocalDateTime beforeUpdate = LocalDateTime.now().minusSeconds(1);

            mockMvc.perform(put("/api/v1/users/{id}", learner.getId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(UpdateUserRequest.builder()
                                    .firstName("Updated")
                                            .lastName("Name")
                                            .role(UserRole.LEARNER)
                                    .email("learner@test.com")
                                    .build())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.firstName").value("Updated"))
                    .andExpect(jsonPath("$.data.lastName").value("Name"))
                    .andExpect(jsonPath("$.data.email").value("learner@test.com"))
                    .andExpect(jsonPath("$.data.role").value("LEARNER"))
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.updatedAt").isNotEmpty());

            User updated = userRepository.findById(learner.getId()).orElseThrow();
            assertThat(updated.getFirstName()).isEqualTo("Updated");
            assertThat(updated.getUpdatedAt()).isAfter(beforeUpdate);
        }

        @Test
        @DisplayName("TC_AM_UPDATE_002 — Validation error")
        void tc_am_update_002_validationError() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            User learner = buildAndSaveUser("learner@test.com", UserRole.LEARNER, UserStatus.ACTIVE);
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

            mockMvc.perform(put("/api/v1/users/{id}", learner.getId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"learner@test.com\",\"firstName\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("TC_AM_UPDATE_003 — Admin cannot change account status to deleted")
        void tc_am_update_003_cannotUpdateStatusToDeleted() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            User learner = buildAndSaveUser("learner@test.com", UserRole.LEARNER, UserStatus.ACTIVE);
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

            mockMvc.perform(put("/api/v1/users/{id}", learner.getId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(UpdateUserRequest.builder()
                                    .status(UserStatus.DELETED)
                                            .firstName("Updated")
                                            .lastName("Name")
                                    .role(UserRole.LEARNER)
                                    .email("learner@test.com")
                                    .build())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_STATUS"))
                    .andExpect(jsonPath("$.message").value("Không thể cập nhật trạng thái thành DELETED. Vui lòng sử dụng chức năng xóa để xóa tài khoản"));

            assertThat(userRepository.findById(learner.getId()).orElseThrow().getStatus())
                    .isEqualTo(UserStatus.ACTIVE);
        }

        @Test
        @DisplayName("TC_AM_UPDATE_004 — Manager cannot update account")
        void tc_am_update_004_managerCannotUpdate() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            buildAndSaveUser("manager@test.com", UserRole.CONTENT_MANAGER, UserStatus.ACTIVE);
            User learner = buildAndSaveUser("learner@test.com", UserRole.LEARNER, UserStatus.ACTIVE);
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            mockMvc.perform(put("/api/v1/users/{id}", learner.getId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(UpdateUserRequest.builder()
                                    .firstName("Updated")
                                    .email("learner@test.com")
                                    .build())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }

        @Test
        @DisplayName("TC_AM_UPDATE_005 — Admin cannot update deleted account")
        void tc_am_update_005_adminCannotUpdateDeletedAccount() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            User deleted = buildAndSaveDeletedUser("deleted@test.com", UserRole.LEARNER);
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

            mockMvc.perform(put("/api/v1/users/{id}", deleted.getId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(UpdateUserRequest.builder()
                                    .firstName("Updated")
                                            .lastName("Name")
                                            .role(UserRole.LEARNER)
                                    .email("deleted@test.com")
                                    .build())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("USER_DELETED"))
                    .andExpect(jsonPath("$.message").value("Không thể chỉnh sửa tài khoản đã bị xóa"));
        }

        @Test
        @DisplayName("TC_AM_UPDATE_006 — Email is read-only, not be updated")
        void tc_am_update_006_emailIsReadOnly() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            User learner = buildAndSaveUser("learner@test.com", UserRole.LEARNER, UserStatus.ACTIVE);
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

            mockMvc.perform(put("/api/v1/users/{id}", learner.getId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(UpdateUserRequest.builder()
                                    .firstName("Updated")
                                            .lastName("Name")
                                            .role(UserRole.LEARNER)
                                    .email("different@test.com")
                                    .build())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.firstName").value("Updated"))
                    .andExpect(jsonPath("$.data.email").value("learner@test.com"));

            // Verify email was not changed in database
            User updated = userRepository.findById(learner.getId()).orElseThrow();
            assertThat(updated.getFirstName()).isEqualTo("Updated");
            assertThat(updated.getEmail()).isEqualTo("learner@test.com");
        }

        @Test
        @DisplayName("TC_AM_UPDATE_007 — First Name, Last Name, Role is not blank and not null")
        void tc_am_update_007_validFieldsUpdate() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            User learner = buildAndSaveUser("learner@test.com", UserRole.LEARNER, UserStatus.ACTIVE);
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");
            LocalDateTime beforeUpdate = LocalDateTime.now().minusSeconds(1);

            mockMvc.perform(put("/api/v1/users/{id}", learner.getId())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(UpdateUserRequest.builder()
                                    .firstName("ValidFirstName")
                                    .lastName("ValidLastName")
                                    .role(UserRole.TEACHER)
                                    .email("learner@test.com")
                                    .build())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.firstName").value("ValidFirstName"))
                    .andExpect(jsonPath("$.data.lastName").value("ValidLastName"))
                    .andExpect(jsonPath("$.data.email").value("learner@test.com"))
                    .andExpect(jsonPath("$.data.role").value("TEACHER"))
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.updatedAt").isNotEmpty());

            User updated = userRepository.findById(learner.getId()).orElseThrow();
            assertThat(updated.getFirstName()).isEqualTo("ValidFirstName");
            assertThat(updated.getLastName()).isEqualTo("ValidLastName");
            assertThat(updated.getRole()).isEqualTo(UserRole.TEACHER);
            assertThat(updated.getUpdatedAt()).isAfter(beforeUpdate);
        }
    }

    // =========================================================================
    // Section 5 — DELETE ACCOUNT  DELETE /api/v1/users/{id}
    // =========================================================================

    @Nested
    @DisplayName("Section 5 — Delete Account")
    class DeleteAccountTests {

        @Test
        @DisplayName("TC_AM_DELETE_001 — Admin delete account success (soft-delete)")
        void tc_am_delete_001_adminSoftDeletesAccount() throws Exception {
            User admin = buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            User learner = buildAndSaveUser("learner@test.com", UserRole.LEARNER, UserStatus.ACTIVE);
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

            mockMvc.perform(delete("/api/v1/users/{id}", learner.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Xóa người dùng thành công"));

            User deleted = userRepository.findById(learner.getId()).orElseThrow();
            assertThat(deleted.getDeletedAt()).isNotNull();
            assertThat(deleted.getDeletedBy()).isEqualTo(admin.getId());
            assertThat(deleted.getStatus()).isEqualTo(UserStatus.DELETED);

            // Session verification: All sessions for deleted account are terminated
            assertThat(sessionRepository.countActiveSessionsByUserId(learner.getId(), LocalDateTime.now()))
                    .isEqualTo(0);
        }

        @Test
        @DisplayName("TC_AM_DELETE_002 — cannot delete deleted account")
        void tc_am_delete_002_cannotDeleteDeletedAccount() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            User alreadyDeleted = buildAndSaveDeletedUser("already.deleted@test.com", UserRole.LEARNER);
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

            mockMvc.perform(delete("/api/v1/users/{id}", alreadyDeleted.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        @DisplayName("TC_AM_DELETE_003 — manager cannot delete account")
        void tc_am_delete_003_managerCannotDelete() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            buildAndSaveUser("manager@test.com", UserRole.CONTENT_MANAGER, UserStatus.ACTIVE);
            User learner = buildAndSaveUser("learner@test.com", UserRole.LEARNER, UserStatus.ACTIVE);
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            mockMvc.perform(delete("/api/v1/users/{id}", learner.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }
    }

    // =========================================================================
    // Section 6 — RESTORE ACCOUNT  POST /api/v1/users/restore
    // =========================================================================

    @Nested
    @DisplayName("Section 6 — Restore Account")
    class RestoreAccountTests {

        @Test
        @DisplayName("TC_AM_RESTORE_001 — Admin restore account success")
        void tc_am_restore_001_adminRestoresAccount() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            User deleted = buildAndSaveDeletedUser("deleted@test.com", UserRole.LEARNER);
            String oldPasswordHash = deleted.getPasswordHash();
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

            RestoreUserRequest req = RestoreUserRequest.builder()
                    .userId(deleted.getId())
                    .email("deleted@test.com")
                    .firstName("Restored")
                    .lastName("Account")
                    .role(UserRole.LEARNER)
                    .build();

            mockMvc.perform(post("/api/v1/users/restore")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.data.email").value("deleted@test.com"))
                    .andExpect(jsonPath("$.data.firstName").value("Restored"))
                    .andExpect(jsonPath("$.data.lastName").value("Account"))
                    .andExpect(jsonPath("$.data.role").value("LEARNER"))
                    .andExpect(jsonPath("$.data.id").value(deleted.getId().toString()))
                    .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                    .andExpect(jsonPath("$.data.updatedAt").isNotEmpty())
                    .andExpect(jsonPath("$.data.passwordHash").doesNotExist());

            User restored = userRepository.findById(deleted.getId()).orElseThrow();
            assertThat(restored.getStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(restored.getDeletedAt()).isNull();
            assertThat(restored.getDeletedBy()).isNull();
            assertThat(restored.getPasswordHash()).isNotEqualTo(oldPasswordHash);

            verify(emailService, times(1))
                    .sendWelcomeEmailWithCredentials(org.mockito.ArgumentMatchers.eq("deleted@test.com"),
                            any(), any(), any());
        }

        @Test
        @DisplayName("TC_AM_RESTORE_002 — cannot restore non-deleted account")
        void tc_am_restore_002_cannotRestoreNonDeleted() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            User active = buildAndSaveUser("active@test.com", UserRole.LEARNER, UserStatus.ACTIVE);
            String token = loginAndGetToken("admin@test.com", "ValidPass@1");

            mockMvc.perform(post("/api/v1/users/restore")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(RestoreUserRequest.builder()
                                    .userId(active.getId())
                                    .email("active@test.com")
                                    .firstName("Restored")
                                    .lastName("Account")
                                    .role(UserRole.LEARNER)
                                    .build())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("USER_NOT_DELETED"))
                    .andExpect(jsonPath("$.message").value("Tài khoản này chưa bị xóa"));
        }

        @Test
        @DisplayName("TC_AM_RESTORE_003 — manager cannot restore account")
        void tc_am_restore_003_managerCannotRestore() throws Exception {
            buildAndSaveUser("admin@test.com", UserRole.ADMIN, UserStatus.ACTIVE);
            buildAndSaveUser("manager@test.com", UserRole.CONTENT_MANAGER, UserStatus.ACTIVE);
            User deleted = buildAndSaveDeletedUser("restore2@test.com", UserRole.LEARNER);
            String token = loginAndGetToken("manager@test.com", "ValidPass@1");

            mockMvc.perform(post("/api/v1/users/restore")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(RestoreUserRequest.builder()
                                    .userId(deleted.getId())
                                    .email("restore2@test.com")
                                    .firstName("Restored")
                                    .lastName("Account")
                                    .role(UserRole.LEARNER)
                                    .build())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }
    }
}

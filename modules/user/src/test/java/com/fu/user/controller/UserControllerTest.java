package com.fu.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.common.response.PageResponse;
import com.fu.user.config.UserModuleTestConfig;
import com.fu.user.dto.*;
import com.fu.user.entity.UserRole;
import com.fu.user.entity.UserStatus;
import com.fu.user.security.CustomUserDetails;
import com.fu.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller Unit Tests using MockMvc.
 * Updated for firstName/lastName structure and CustomUserDetails.
 * 
 * @WebMvcTest loads only the web layer (no full Spring context).
 * Tests HTTP request/response, validation, security, and JSON serialization.
 */
@WebMvcTest(controllers = UserController.class)
@ContextConfiguration(classes = UserModuleTestConfig.class)
@AutoConfigureMockMvc
@DisplayName("UserController Unit Tests - Updated")
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserService userService;

    // Test data
    private UUID testUserId;
    private UUID managerId;
    private UUID adminId;
    private UserResponse testUserResponse;
    private CreateUserRequest createRequest;
    private UpdateUserRequest updateRequest;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID();
        managerId = UUID.randomUUID();
        adminId = UUID.randomUUID();

        testUserResponse = UserResponse.builder()
                .id(testUserId)
                .email("test@example.com")
                .firstName("John")
                .lastName("Doe")
                .fullName("John Doe")
                .role(UserRole.LEARNER)
                .status(UserStatus.ACTIVE)
                .build();

        createRequest = CreateUserRequest.builder()
                .email("newuser@example.com")
                .firstName("Jane")
                .lastName("Smith")
                .role(UserRole.LEARNER)
                .phoneNumber("0123456789")
                .dateOfBirth(LocalDate.of(2000, 1, 15))
                .build();

        updateRequest = UpdateUserRequest.builder()
                .firstName("Updated")
                .lastName("Name")
                .build();
    }

    // ========== UC-02: Create User API Tests ==========
    @Nested
    @DisplayName("POST /api/v1/users - Create User")
    class CreateUserApiTests {

        @Test
        @DisplayName("Should create user and return 201 when Manager authenticated")
        @WithMockUser(username = "manager@example.com", roles = "MANAGER")
        void createUser_authenticatedManager_returns201() throws Exception {
            // Given
            given(userService.createUser(any(CreateUserRequest.class), any(UUID.class), eq(UserRole.MANAGER)))
                    .willReturn(testUserResponse);

            // When
            ResultActions result = mockMvc.perform(post("/api/v1/users")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(createRequest)));

            // Then
            result.andDo(print())
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Tạo người dùng thành công"))
                    .andExpect(jsonPath("$.data.id").value(testUserId.toString()))
                    .andExpect(jsonPath("$.data.email").value("test@example.com"))
                    .andExpect(jsonPath("$.data.firstName").value("John"))
                    .andExpect(jsonPath("$.data.lastName").value("Doe"));
        }

        @Test
        @DisplayName("Should return 403 when non-Manager tries to create user")
        @WithMockUser(roles = "LEARNER")
        void createUser_nonManagerRole_returns403() throws Exception {
            // When
            ResultActions result = mockMvc.perform(post("/api/v1/users")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(createRequest)));

            // Then
            result.andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Should return 403 when unauthenticated")
        void createUser_unauthenticated_returns403() throws Exception {
            // When
            ResultActions result = mockMvc.perform(post("/api/v1/users")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(createRequest)));

            // Then
            result.andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Should return 400 when email is invalid")
        @WithMockUser(roles = "MANAGER")
        void createUser_invalidEmail_returns400() throws Exception {
            // Given
            createRequest.setEmail("invalid-email"); // Invalid email format

            // When
            ResultActions result = mockMvc.perform(post("/api/v1/users")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(createRequest)));

            // Then
            result.andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 400 when firstName is blank")
        @WithMockUser(roles = "MANAGER")
        void createUser_blankFirstName_returns400() throws Exception {
            // Given
            createRequest.setFirstName(""); // Blank firstName

            // When
            ResultActions result = mockMvc.perform(post("/api/v1/users")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(createRequest)));

            // Then
            result.andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 400 when lastName is blank")
        @WithMockUser(roles = "MANAGER")
        void createUser_blankLastName_returns400() throws Exception {
            // Given
            createRequest.setLastName(""); // Blank lastName

            // When
            ResultActions result = mockMvc.perform(post("/api/v1/users")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(createRequest)));

            // Then
            result.andExpect(status().isBadRequest());
        }
    }

    // ========== UC-01: Get User API Tests ==========
    @Nested
    @DisplayName("GET /api/v1/users/{id} - Get User by ID")
    class GetUserByIdApiTests {

        @Test
        @DisplayName("Should return user when found")
        @WithMockUser(roles = "MANAGER")
        void getUserById_existingUser_returns200() throws Exception {
            // Given
            given(userService.getUserById(testUserId)).willReturn(testUserResponse);

            // When
            ResultActions result = mockMvc.perform(get("/api/v1/users/{id}", testUserId));

            // Then
            result.andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(testUserId.toString()))
                    .andExpect(jsonPath("$.data.email").value("test@example.com"))
                    .andExpect(jsonPath("$.data.firstName").value("John"))
                    .andExpect(jsonPath("$.data.lastName").value("Doe"))
                    .andExpect(jsonPath("$.data.role").value("LEARNER"));
        }

        @Test
        @DisplayName("Should return 403 when Learner tries to access")
        @WithMockUser(roles = "LEARNER")
        void getUserById_learnerRole_returns403() throws Exception {
            // When
            ResultActions result = mockMvc.perform(get("/api/v1/users/{id}", testUserId));

            // Then
            result.andExpect(status().isForbidden());
        }
    }

    // ========== UC-03: Get User Detail with Enrollment History ==========
    @Nested
    @DisplayName("GET /api/v1/users/{id}/details - Get User Detail")
    class GetUserDetailApiTests {

        @Test
        @DisplayName("Should return user details with enrollment history")
        @WithMockUser(roles = "MANAGER")
        void getUserDetail_existingUser_returns200() throws Exception {
            // Given
            UserDetailResponse detailResponse = UserDetailResponse.builder()
                    .userInfo(testUserResponse)
                    .courseEnrollments(List.of())
                    .classEnrollments(List.of())
                    .taughtClasses(List.of())
                    .totalCourses(0)
                    .totalClasses(0)
                    .build();
            
            given(userService.getUserDetailById(testUserId)).willReturn(detailResponse);

            // When
            ResultActions result = mockMvc.perform(get("/api/v1/users/{id}/details", testUserId));

            // Then
            result.andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.userInfo.id").value(testUserId.toString()))
                    .andExpect(jsonPath("$.data.totalCourses").value(0))
                    .andExpect(jsonPath("$.data.totalClasses").value(0));
        }
    }

    // ========== UC-04: Update User API Tests ==========
    @Nested
    @DisplayName("PUT /api/v1/users/{id} - Update User")
    class UpdateUserApiTests {

        @Test
        @DisplayName("Should update user and return 200")
        @WithMockUser(roles = "MANAGER")
        void updateUser_validRequest_returns200() throws Exception {
            // Given
            UserResponse updatedResponse = UserResponse.builder()
                    .id(testUserId)
                    .email("test@example.com")
                    .firstName("Updated")
                    .lastName("Name")
                    .fullName("Updated Name")
                    .role(UserRole.LEARNER)
                    .status(UserStatus.ACTIVE)
                    .build();
            
            given(userService.updateUser(eq(testUserId), any(UpdateUserRequest.class), any(UUID.class), any(UserRole.class)))
                    .willReturn(updatedResponse);

            // When
            ResultActions result = mockMvc.perform(put("/api/v1/users/{id}", testUserId)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(updateRequest)));

            // Then
            result.andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Cập nhật thông tin thành công"))
                    .andExpect(jsonPath("$.data.firstName").value("Updated"))
                    .andExpect(jsonPath("$.data.lastName").value("Name"));
        }
    }

    // ========== UC-05: Delete User API Tests ==========
    @Nested
    @DisplayName("DELETE /api/v1/users/{id} - Delete User")
    class DeleteUserApiTests {

        @Test
        @DisplayName("Should soft delete user and return 200")
        @WithMockUser(roles = "MANAGER")
        void deleteUser_existingUser_returns200() throws Exception {
            // Given
            doNothing().when(userService).deleteUser(eq(testUserId), any(UUID.class), any(UserRole.class));

            // When
            ResultActions result = mockMvc.perform(delete("/api/v1/users/{id}", testUserId)
                    .with(csrf()));

            // Then
            result.andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Xóa người dùng thành công"));
        }
    }

    // ========== UC-01: List Users API Tests ==========
    @Nested
    @DisplayName("GET /api/v1/users - List Users")
    class ListUsersApiTests {

        @Test
        @DisplayName("Should return paginated list with default parameters")
        @WithMockUser(roles = "MANAGER")
        void getAllUsers_defaultParams_returns200() throws Exception {
            // Given
            Page<UserResponse> page = new PageImpl<>(List.of(testUserResponse));
            given(userService.searchUsers(any(UserFilterRequest.class), any(), eq(UserRole.MANAGER)))
                    .willReturn(page);

            // When
            ResultActions result = mockMvc.perform(get("/api/v1/users"));

            // Then
            result.andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content[0].firstName").value("John"));
        }

        @Test
        @DisplayName("Should filter by role and status")
        @WithMockUser(roles = "MANAGER")
        void getAllUsers_withFilters_returns200() throws Exception {
            // Given
            Page<UserResponse> page = new PageImpl<>(List.of(testUserResponse));
            given(userService.searchUsers(any(UserFilterRequest.class), any(), eq(UserRole.MANAGER)))
                    .willReturn(page);

            // When
            ResultActions result = mockMvc.perform(get("/api/v1/users")
                    .param("role", "LEARNER")
                    .param("status", "ACTIVE")
                    .param("keyword", "john"));

            // Then
            result.andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("Admin should see all users including managers")
        @WithMockUser(roles = "ADMIN")
        void getAllUsers_asAdmin_seeAllUsers() throws Exception {
            // Given
            Page<UserResponse> page = new PageImpl<>(List.of(testUserResponse));
            given(userService.searchUsers(any(UserFilterRequest.class), any(), eq(UserRole.ADMIN)))
                    .willReturn(page);

            // When
            ResultActions result = mockMvc.perform(get("/api/v1/users"));

            // Then
            result.andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }
    }

    // ========== Admin Operations: Deleted Users ==========
    @Nested
    @DisplayName("GET /api/v1/users/deleted - View Deleted Users")
    class GetDeletedUsersApiTests {

        @Test
        @DisplayName("Should return deleted users list for admin")
        @WithMockUser(roles = "ADMIN")
        void getDeletedUsers_asAdmin_returns200() throws Exception {
            // Given
            Page<UserResponse> page = new PageImpl<>(List.of(testUserResponse));
            given(userService.getDeletedUsers(any())).willReturn(page);

            // When
            ResultActions result = mockMvc.perform(get("/api/v1/users/deleted"));

            // Then
            result.andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("Should return 403 when manager tries to view deleted users")
        @WithMockUser(roles = "MANAGER")
        void getDeletedUsers_asManager_returns403() throws Exception {
            // When
            ResultActions result = mockMvc.perform(get("/api/v1/users/deleted"));

            // Then
            result.andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/users/restore - Restore Deleted User")
    class RestoreUserApiTests {

        @Test
        @DisplayName("Should restore deleted user for admin")
        @WithMockUser(roles = "ADMIN")
        void restoreUser_asAdmin_returns200() throws Exception {
            // Given
            RestoreUserRequest restoreRequest = RestoreUserRequest.builder()
                    .userId(testUserId)
                    .email("test@example.com")
                    .firstName("Restored")
                    .lastName("User")
                    .role(UserRole.LEARNER)
                    .build();
            
            given(userService.restoreUser(any(RestoreUserRequest.class), any(UUID.class), eq(UserRole.ADMIN)))
                    .willReturn(testUserResponse);

            // When
            ResultActions result = mockMvc.perform(post("/api/v1/users/restore")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(restoreRequest)));

            // Then
            result.andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Khôi phục người dùng thành công"));
        }

        @Test
        @DisplayName("Should return 403 when manager tries to restore user")
        @WithMockUser(roles = "MANAGER")
        void restoreUser_asManager_returns403() throws Exception {
            // Given
            RestoreUserRequest restoreRequest = RestoreUserRequest.builder()
                    .userId(testUserId)
                    .email("test@example.com")
                    .firstName("Restored")
                    .lastName("User")
                    .role(UserRole.LEARNER)
                    .build();

            // When
            ResultActions result = mockMvc.perform(post("/api/v1/users/restore")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(restoreRequest)));

            // Then
            result.andExpect(status().isForbidden());
        }
    }
}

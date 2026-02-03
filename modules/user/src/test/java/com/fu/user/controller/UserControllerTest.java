package com.fu.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.user.config.UserModuleTestConfig;
import com.fu.user.dto.CreateUserRequest;
import com.fu.user.dto.UpdateUserRequest;
import com.fu.user.dto.UserResponse;
import com.fu.user.entity.UserRole;
import com.fu.user.entity.UserStatus;
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
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller Unit Tests using MockMvc.
 * 
 * @WebMvcTest loads only the web layer (no full Spring context).
 * Tests HTTP request/response, validation, security, and JSON serialization.
 */
@WebMvcTest(controllers = UserController.class)
@ContextConfiguration(classes = UserModuleTestConfig.class)
@AutoConfigureMockMvc
@DisplayName("UserController Unit Tests")
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserService userService;

    // Test data
    private UUID testUserId;
    private UserResponse testUserResponse;
    private CreateUserRequest createRequest;
    private UpdateUserRequest updateRequest;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID();

        testUserResponse = new UserResponse();
        testUserResponse.setId(testUserId);
        testUserResponse.setEmail("test@example.com");
        testUserResponse.setFirstName("John");
        testUserResponse.setLastName("Doe");
        testUserResponse.setRole(UserRole.LEARNER);
        testUserResponse.setStatus(UserStatus.ACTIVE);

        createRequest = new CreateUserRequest();
        createRequest.setEmail("newuser@example.com");
        createRequest.setFullName("Jane Smith");
        createRequest.setRole(UserRole.LEARNER);
        createRequest.setPhoneNumber("0123456789");
        createRequest.setDateOfBirth(LocalDate.of(2000, 1, 15));

        updateRequest = new UpdateUserRequest();
        updateRequest.setFirstName("Updated");
        updateRequest.setLastName("Name");
    }

    // ========== UC-02: Create User API Tests ==========
    @Nested
    @DisplayName("POST /api/v1/users - Create User")
    class CreateUserApiTests {

        @Test
        @DisplayName("Should create user and return 201 when Manager authenticated")
        @WithMockUser(roles = "MANAGER")
        void createUser_authenticatedManager_returns201() throws Exception {
            // Given
            given(userService.createUser(any(CreateUserRequest.class), any(UUID.class)))
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
                    .andExpect(jsonPath("$.data.email").value("test@example.com"));
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
        @DisplayName("Should return 400 when required fields are missing")
        @WithMockUser(roles = "MANAGER")
        void createUser_missingRequiredFields_returns400() throws Exception {
            // Given
            CreateUserRequest invalidRequest = new CreateUserRequest(); // Empty request

            // When
            ResultActions result = mockMvc.perform(post("/api/v1/users")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(invalidRequest)));

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

    // ========== UC-04: Update User API Tests ==========
    @Nested
    @DisplayName("PUT /api/v1/users/{id} - Update User")
    class UpdateUserApiTests {

        @Test
        @DisplayName("Should update user and return 200")
        @WithMockUser(roles = "MANAGER")
        void updateUser_validRequest_returns200() throws Exception {
            // Given
            given(userService.updateUser(eq(testUserId), any(UpdateUserRequest.class), any(UUID.class)))
                    .willReturn(testUserResponse);

            // When
            ResultActions result = mockMvc.perform(put("/api/v1/users/{id}", testUserId)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(updateRequest)));

            // Then
            result.andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Cập nhật thông tin thành công"));
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
            doNothing().when(userService).deleteUser(eq(testUserId), any(UUID.class));

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
            Page<UserResponse> emptyPage = Page.empty();
            given(userService.getAllUsers(any())).willReturn(emptyPage);

            // When
            ResultActions result = mockMvc.perform(get("/api/v1/users"));

            // Then
            result.andExpect(status().isOk());
        }

        @Test
        @DisplayName("Should filter by role and status")
        @WithMockUser(roles = "MANAGER")
        void getAllUsers_withFilters_returns200() throws Exception {
            // Given
            Page<UserResponse> emptyPage = Page.empty();
            given(userService.searchUsers(any(), any())).willReturn(emptyPage);

            // When
            ResultActions result = mockMvc.perform(get("/api/v1/users")
                    .param("role", "LEARNER")
                    .param("status", "ACTIVE")
                    .param("keyword", "john"));

            // Then
            result.andExpect(status().isOk());
        }
    }
}

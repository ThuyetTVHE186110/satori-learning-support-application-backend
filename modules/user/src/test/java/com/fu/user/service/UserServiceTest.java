package com.fu.user.service;

import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.user.dto.CreateUserRequest;
import com.fu.user.dto.UpdateUserRequest;
import com.fu.user.dto.UserResponse;
import com.fu.user.entity.User;
import com.fu.user.entity.UserRole;
import com.fu.user.entity.UserStatus;
import com.fu.user.mapper.UserMapper;
import com.fu.user.repository.UserRepository;
import com.fu.user.service.impl.UserServiceImpl;
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

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * Unit tests for UserServiceImpl.
 * 
 * Test Naming Convention: methodName_scenario_expectedBehavior
 * 
 * Uses:
 * - JUnit 5 (@Test, @Nested, @DisplayName)
 * - Mockito (@Mock, @InjectMocks, given/when/then)
 * - AssertJ (assertThat, assertThatThrownBy)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserService Unit Tests")
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserServiceImpl userService;

    @Captor
    private ArgumentCaptor<User> userCaptor;

    // Test fixtures
    private User testUser;
    private UserResponse testUserResponse;
    private CreateUserRequest createRequest;
    private UpdateUserRequest updateRequest;
    private UUID testUserId;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID();
        
        testUser = new User();
        testUser.setId(testUserId);
        testUser.setEmail("test@example.com");
        testUser.setFirstName("John");
        testUser.setLastName("Doe");
        testUser.setDisplayName("John Doe");
        testUser.setRole(UserRole.LEARNER);
        testUser.setStatus(UserStatus.ACTIVE);

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

    // ========== UC-02: Create User Tests ==========
    @Nested
    @DisplayName("UC-02: Create User")
    class CreateUserTests {

        @Test
        @DisplayName("Should create user successfully with valid request")
        void createUser_validRequest_returnsCreatedUser() {
            // Given (Arrange)
            User newUser = new User();
            newUser.setEmail(createRequest.getEmail());
            
            given(userRepository.existsActiveByEmail(createRequest.getEmail())).willReturn(false);
            given(userMapper.toEntity(createRequest)).willReturn(newUser);
            given(passwordEncoder.encode(anyString())).willReturn("hashedPassword");
            given(userRepository.save(any(User.class))).willAnswer(invocation -> {
                User savedUser = invocation.getArgument(0);
                savedUser.setId(UUID.randomUUID());
                return savedUser;
            });
            given(userMapper.toResponse(any(User.class))).willReturn(testUserResponse);

            // When (Act)
            UserResponse result = userService.createUser(createRequest);

            // Then (Assert)
            assertThat(result).isNotNull();
            assertThat(result.getEmail()).isEqualTo(testUserResponse.getEmail());
            
            // Verify interactions
            verify(userRepository).existsActiveByEmail(createRequest.getEmail());
            verify(userRepository).save(userCaptor.capture());
            
            User capturedUser = userCaptor.getValue();
            assertThat(capturedUser.getPasswordHash()).isEqualTo("hashedPassword");
            assertThat(capturedUser.getRole()).isEqualTo(UserRole.LEARNER);
            assertThat(capturedUser.getStatus()).isEqualTo(UserStatus.ACTIVE);
        }

        @Test
        @DisplayName("Should throw BusinessException when email already exists (BR-01)")
        void createUser_duplicateEmail_throwsBusinessException() {
            // Given
            given(userRepository.existsActiveByEmail(createRequest.getEmail())).willReturn(true);

            // When & Then
            assertThatThrownBy(() -> userService.createUser(createRequest))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "EMAIL_EXISTS")
                    .hasMessageContaining("Email này đã được sử dụng");

            // Verify save was never called
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should throw BusinessException when creating MANAGER role")
        void createUser_managerRole_throwsBusinessException() {
            // Given
            createRequest.setRole(UserRole.MANAGER);
            given(userRepository.existsActiveByEmail(anyString())).willReturn(false);

            // When & Then
            assertThatThrownBy(() -> userService.createUser(createRequest))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "INVALID_ROLE");
        }

        @Test
        @DisplayName("Should throw BusinessException when creating ADMIN role")
        void createUser_adminRole_throwsBusinessException() {
            // Given
            createRequest.setRole(UserRole.ADMIN);
            given(userRepository.existsActiveByEmail(anyString())).willReturn(false);

            // When & Then
            assertThatThrownBy(() -> userService.createUser(createRequest))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "INVALID_ROLE");
        }
    }

    // ========== UC-01: Get User Tests ==========
    @Nested
    @DisplayName("UC-01: Get User")
    class GetUserTests {

        @Test
        @DisplayName("Should return user when found by ID")
        void getUserById_existingUser_returnsUser() {
            // Given
            given(userRepository.findById(testUserId)).willReturn(Optional.of(testUser));
            given(userMapper.toResponse(testUser)).willReturn(testUserResponse);

            // When
            UserResponse result = userService.getUserById(testUserId);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(testUserId);
            assertThat(result.getEmail()).isEqualTo("test@example.com");
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when user not found")
        void getUserById_nonExistingUser_throwsException() {
            // Given
            UUID nonExistingId = UUID.randomUUID();
            given(userRepository.findById(nonExistingId)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> userService.getUserById(nonExistingId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("User");
        }

        @Test
        @DisplayName("Should throw exception when user is soft-deleted")
        void getUserById_softDeletedUser_throwsException() {
            // Given
            testUser.softDelete(UUID.randomUUID()); // Mark as deleted
            given(userRepository.findById(testUserId)).willReturn(Optional.of(testUser));

            // When & Then
            assertThatThrownBy(() -> userService.getUserById(testUserId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ========== UC-04: Update User Tests ==========
    @Nested
    @DisplayName("UC-04: Update User")
    class UpdateUserTests {

        @Test
        @DisplayName("Should update user successfully with valid request")
        void updateUser_validRequest_returnsUpdatedUser() {
            // Given
            given(userRepository.findById(testUserId)).willReturn(Optional.of(testUser));
            given(userRepository.save(any(User.class))).willReturn(testUser);
            given(userMapper.toResponse(any(User.class))).willReturn(testUserResponse);

            // When
            UserResponse result = userService.updateUser(testUserId, updateRequest);

            // Then
            assertThat(result).isNotNull();
            verify(userRepository).save(userCaptor.capture());
            
            User capturedUser = userCaptor.getValue();
            assertThat(capturedUser.getFirstName()).isEqualTo("Updated");
            assertThat(capturedUser.getLastName()).isEqualTo("Name");
        }

        @Test
        @DisplayName("Should throw BusinessException when updating role to MANAGER")
        void updateUser_roleToManager_throwsException() {
            // Given
            updateRequest.setRole(UserRole.MANAGER);
            given(userRepository.findById(testUserId)).willReturn(Optional.of(testUser));

            // When & Then
            assertThatThrownBy(() -> userService.updateUser(testUserId, updateRequest))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "INVALID_ROLE");
        }

        @Test
        @DisplayName("Should update only provided fields (partial update)")
        void updateUser_partialUpdate_updatesOnlyProvidedFields() {
            // Given
            UpdateUserRequest partialRequest = new UpdateUserRequest();
            partialRequest.setFirstName("OnlyFirstName"); // Only firstName
            
            String originalLastName = testUser.getLastName();
            
            given(userRepository.findById(testUserId)).willReturn(Optional.of(testUser));
            given(userRepository.save(any(User.class))).willReturn(testUser);
            given(userMapper.toResponse(any(User.class))).willReturn(testUserResponse);

            // When
            userService.updateUser(testUserId, partialRequest);

            // Then
            verify(userRepository).save(userCaptor.capture());
            User capturedUser = userCaptor.getValue();
            assertThat(capturedUser.getFirstName()).isEqualTo("OnlyFirstName");
            assertThat(capturedUser.getLastName()).isEqualTo(originalLastName); // Unchanged
        }
    }

    // ========== UC-05: Delete User (Soft Delete) Tests ==========
    @Nested
    @DisplayName("UC-05: Delete User (Soft Delete)")
    class DeleteUserTests {

        @Test
        @DisplayName("Should soft delete user successfully")
        void deleteUser_existingUser_softDeletes() {
            // Given
            UUID deletedBy = UUID.randomUUID();
            given(userRepository.findById(testUserId)).willReturn(Optional.of(testUser));
            given(userRepository.save(any(User.class))).willReturn(testUser);

            // When
            userService.deleteUser(testUserId, deletedBy);

            // Then
            verify(userRepository).save(userCaptor.capture());
            
            User capturedUser = userCaptor.getValue();
            assertThat(capturedUser.getStatus()).isEqualTo(UserStatus.INACTIVE);
            assertThat(capturedUser.getDeletedAt()).isNotNull();
            assertThat(capturedUser.getDeletedBy()).isEqualTo(deletedBy);
        }

        @Test
        @DisplayName("Should throw BusinessException when deleting MANAGER")
        void deleteUser_managerUser_throwsException() {
            // Given
            testUser.setRole(UserRole.MANAGER);
            given(userRepository.findById(testUserId)).willReturn(Optional.of(testUser));

            // When & Then
            assertThatThrownBy(() -> userService.deleteUser(testUserId, UUID.randomUUID()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "CANNOT_DELETE_MANAGER");
            
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should throw ResourceNotFoundException when user not found")
        void deleteUser_nonExistingUser_throwsException() {
            // Given
            UUID nonExistingId = UUID.randomUUID();
            given(userRepository.findById(nonExistingId)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> userService.deleteUser(nonExistingId, UUID.randomUUID()))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}

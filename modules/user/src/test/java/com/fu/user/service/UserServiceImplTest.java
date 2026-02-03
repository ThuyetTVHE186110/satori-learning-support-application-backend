package com.fu.user.service;

import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.infrastructure.email.EmailService;
import com.fu.user.dto.*;
import com.fu.user.entity.AuthProvider;
import com.fu.user.entity.Gender;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * Unit tests for UserServiceImpl.
 * Tests User Management use cases: UC-01 to UC-05.
 * 
 * <h2>Use Cases Covered:</h2>
 * <ul>
 *   <li>UC-01: View User List (search, filter, pagination)</li>
 *   <li>UC-02: Create User (with welcome email)</li>
 *   <li>UC-03: View User Information</li>
 *   <li>UC-04: Update User Information</li>
 *   <li>UC-05: Delete User (soft delete → INACTIVE)</li>
 * </ul>
 * 
 * <h2>User Self-Service:</h2>
 * <ul>
 *   <li>View own profile</li>
 *   <li>Update own profile (except email)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserService Unit Tests")
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private EmailService emailService;

    @InjectMocks
    private UserServiceImpl userService;

    @Captor
    private ArgumentCaptor<User> userCaptor;

    // Test fixtures
    private User testUser;
    private UserResponse testUserResponse;
    private CreateUserRequest createRequest;
    private UpdateUserRequest updateRequest;
    private UpdateProfileRequest updateProfileRequest;
    private UUID testUserId;
    private UUID managerId;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID();
        managerId = UUID.randomUUID();
        
        testUser = User.builder()
                .email("test@example.com")
                .passwordHash("hashedPassword")
                .firstName("John")
                .lastName("Doe")
                .displayName("John Doe")
                .role(UserRole.LEARNER)
                .status(UserStatus.ACTIVE)
                .authProvider(AuthProvider.LOCAL)
                .build();
        ReflectionTestUtils.setField(testUser, "id", testUserId);

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

        updateProfileRequest = new UpdateProfileRequest();
        updateProfileRequest.setDisplayName("New Display Name");
        updateProfileRequest.setPhoneNumber("0987654321");
    }

    // ==================== UC-02: Create User Tests ====================
    @Nested
    @DisplayName("UC-02: Create User")
    class CreateUserTests {

        @Test
        @DisplayName("Should create user and send welcome email with credentials")
        void createUser_validRequest_createsUserAndSendsEmail() {
            // Given
            User newUser = User.builder()
                    .email(createRequest.getEmail())
                    .firstName("Jane")
                    .lastName("Smith")
                    .displayName("Jane Smith")
                    .build();
            
            given(userRepository.findByEmail(createRequest.getEmail())).willReturn(Optional.empty());
            given(userMapper.toEntity(createRequest)).willReturn(newUser);
            given(passwordEncoder.encode(anyString())).willReturn("hashedPassword");
            given(userRepository.save(any(User.class))).willAnswer(invocation -> {
                User savedUser = invocation.getArgument(0);
                ReflectionTestUtils.setField(savedUser, "id", UUID.randomUUID());
                return savedUser;
            });
            given(userMapper.toResponse(any(User.class))).willReturn(testUserResponse);

            // When
            UserResponse result = userService.createUser(createRequest, managerId);

            // Then
            assertThat(result).isNotNull();
            
            verify(userRepository).save(userCaptor.capture());
            User capturedUser = userCaptor.getValue();
            assertThat(capturedUser.getStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(capturedUser.getRole()).isEqualTo(UserRole.LEARNER);
            assertThat(capturedUser.getAuthProvider()).isEqualTo(AuthProvider.LOCAL);
            assertThat(capturedUser.getCreatedBy()).isEqualTo(managerId);
            
            // Verify welcome email sent with credentials
            verify(emailService).sendWelcomeEmailWithCredentials(
                    eq(createRequest.getEmail()),
                    anyString(), // userName
                    eq(createRequest.getEmail()),
                    anyString() // generated password
            );
        }

        @Test
        @DisplayName("BR-01: Should throw exception when email already exists (non-deleted)")
        void createUser_duplicateEmail_throwsBusinessException() {
            // Given
            testUser.setStatus(UserStatus.ACTIVE);
            given(userRepository.findByEmail(createRequest.getEmail())).willReturn(Optional.of(testUser));

            // When/Then
            assertThatThrownBy(() -> userService.createUser(createRequest, managerId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "EMAIL_EXISTS")
                    .hasMessageContaining("Email này đã được sử dụng");

            verify(userRepository, never()).save(any());
            verify(emailService, never()).sendWelcomeEmailWithCredentials(anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("Should restore deleted account when email exists with DELETED status")
        void createUser_deletedEmail_restoresAccount() {
            // Given
            User deletedUser = User.builder()
                    .email(createRequest.getEmail())
                    .status(UserStatus.DELETED)
                    .build();
            ReflectionTestUtils.setField(deletedUser, "id", UUID.randomUUID());
            
            given(userRepository.findByEmail(createRequest.getEmail())).willReturn(Optional.of(deletedUser));
            given(passwordEncoder.encode(anyString())).willReturn("newHashedPassword");
            given(userRepository.save(any(User.class))).willReturn(deletedUser);
            given(userMapper.toResponse(any(User.class))).willReturn(testUserResponse);

            // When
            UserResponse result = userService.createUser(createRequest, managerId);

            // Then
            assertThat(result).isNotNull();
            
            verify(userRepository).save(userCaptor.capture());
            User restoredUser = userCaptor.getValue();
            assertThat(restoredUser.getStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(restoredUser.getDeletedAt()).isNull();
            assertThat(restoredUser.getDeletedBy()).isNull();
            
            verify(emailService).sendWelcomeEmailWithCredentials(anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("Should throw exception when creating MANAGER role")
        void createUser_managerRole_throwsBusinessException() {
            // Given
            createRequest.setRole(UserRole.MANAGER);

            // When/Then
            assertThatThrownBy(() -> userService.createUser(createRequest, managerId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "INVALID_ROLE");
        }

        @Test
        @DisplayName("Should throw exception when creating ADMIN role")
        void createUser_adminRole_throwsBusinessException() {
            // Given
            createRequest.setRole(UserRole.ADMIN);

            // When/Then
            assertThatThrownBy(() -> userService.createUser(createRequest, managerId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "INVALID_ROLE");
        }

        @Test
        @DisplayName("Should create TEACHER role successfully")
        void createUser_teacherRole_createsSuccessfully() {
            // Given
            createRequest.setRole(UserRole.TEACHER);
            User newUser = User.builder().email(createRequest.getEmail()).build();
            
            given(userRepository.findByEmail(createRequest.getEmail())).willReturn(Optional.empty());
            given(userMapper.toEntity(createRequest)).willReturn(newUser);
            given(passwordEncoder.encode(anyString())).willReturn("hashedPassword");
            given(userRepository.save(any(User.class))).willReturn(newUser);
            given(userMapper.toResponse(any(User.class))).willReturn(testUserResponse);

            // When
            userService.createUser(createRequest, managerId);

            // Then
            verify(userRepository).save(userCaptor.capture());
            assertThat(userCaptor.getValue().getRole()).isEqualTo(UserRole.TEACHER);
        }
    }

    // ==================== UC-01/UC-03: View User Tests ====================
    @Nested
    @DisplayName("UC-01/UC-03: View User")
    class ViewUserTests {

        @Test
        @DisplayName("UC-03: Should return user when found by ID")
        void getUserById_existingUser_returnsUser() {
            // Given
            given(userRepository.findById(testUserId)).willReturn(Optional.of(testUser));
            given(userMapper.toResponse(testUser)).willReturn(testUserResponse);

            // When
            UserResponse result = userService.getUserById(testUserId);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getEmail()).isEqualTo("test@example.com");
        }

        @Test
        @DisplayName("Should throw exception when user not found")
        void getUserById_nonExistentUser_throwsResourceNotFoundException() {
            // Given
            UUID nonExistentId = UUID.randomUUID();
            given(userRepository.findById(nonExistentId)).willReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> userService.getUserById(nonExistentId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("Should not return DELETED user")
        void getUserById_deletedUser_throwsResourceNotFoundException() {
            // Given
            testUser.setStatus(UserStatus.DELETED);
            given(userRepository.findById(testUserId)).willReturn(Optional.of(testUser));

            // When/Then
            assertThatThrownBy(() -> userService.getUserById(testUserId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("UC-01: Should return paginated user list")
        void getAllUsers_returnsPageOfUsers() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            Page<User> userPage = new PageImpl<>(List.of(testUser), pageable, 1);
            
            given(userRepository.findAllActive(pageable)).willReturn(userPage);
            given(userMapper.toResponse(testUser)).willReturn(testUserResponse);

            // When
            Page<UserResponse> result = userService.getAllUsers(pageable);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getTotalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("UC-01: Should search users with filters")
        void searchUsers_withFilters_returnsFilteredUsers() {
            // Given
            UserFilterRequest filter = new UserFilterRequest();
            filter.setKeyword("john");
            filter.setRole(UserRole.LEARNER);
            filter.setStatus(UserStatus.ACTIVE);
            
            Pageable pageable = PageRequest.of(0, 10);
            Page<User> userPage = new PageImpl<>(List.of(testUser), pageable, 1);
            
            given(userRepository.searchUsers("john", UserRole.LEARNER, UserStatus.ACTIVE, pageable))
                    .willReturn(userPage);
            given(userMapper.toResponse(testUser)).willReturn(testUserResponse);

            // When
            Page<UserResponse> result = userService.searchUsers(filter, pageable);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            verify(userRepository).searchUsers("john", UserRole.LEARNER, UserStatus.ACTIVE, pageable);
        }

        @Test
        @DisplayName("Should get user by email")
        void getUserByEmail_existingUser_returnsUser() {
            // Given
            given(userRepository.findActiveByEmail("test@example.com")).willReturn(Optional.of(testUser));
            given(userMapper.toResponse(testUser)).willReturn(testUserResponse);

            // When
            UserResponse result = userService.getUserByEmail("test@example.com");

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getEmail()).isEqualTo("test@example.com");
        }
    }

    // ==================== UC-04: Update User Tests ====================
    @Nested
    @DisplayName("UC-04: Update User Information")
    class UpdateUserTests {

        @Test
        @DisplayName("Should update user information successfully")
        void updateUser_validRequest_updatesUser() {
            // Given
            updateRequest.setFirstName("NewFirst");
            updateRequest.setLastName("NewLast");
            updateRequest.setPhoneNumber("0999999999");
            updateRequest.setRole(UserRole.TEACHER);
            updateRequest.setStatus(UserStatus.INACTIVE);
            
            given(userRepository.findById(testUserId)).willReturn(Optional.of(testUser));
            given(userRepository.save(any(User.class))).willReturn(testUser);
            given(userMapper.toResponse(any(User.class))).willReturn(testUserResponse);

            // When
            userService.updateUser(testUserId, updateRequest, managerId);

            // Then
            verify(userRepository).save(userCaptor.capture());
            User updatedUser = userCaptor.getValue();
            assertThat(updatedUser.getFirstName()).isEqualTo("NewFirst");
            assertThat(updatedUser.getLastName()).isEqualTo("NewLast");
            assertThat(updatedUser.getPhoneNumber()).isEqualTo("0999999999");
            assertThat(updatedUser.getRole()).isEqualTo(UserRole.TEACHER);
            assertThat(updatedUser.getStatus()).isEqualTo(UserStatus.INACTIVE);
            assertThat(updatedUser.getUpdatedBy()).isEqualTo(managerId);
        }

        @Test
        @DisplayName("Should throw exception when updating to MANAGER role")
        void updateUser_toManagerRole_throwsBusinessException() {
            // Given
            updateRequest.setRole(UserRole.MANAGER);
            given(userRepository.findById(testUserId)).willReturn(Optional.of(testUser));

            // When/Then
            assertThatThrownBy(() -> userService.updateUser(testUserId, updateRequest, managerId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "INVALID_ROLE");
        }

        @Test
        @DisplayName("Should throw exception when updating to ADMIN role")
        void updateUser_toAdminRole_throwsBusinessException() {
            // Given
            updateRequest.setRole(UserRole.ADMIN);
            given(userRepository.findById(testUserId)).willReturn(Optional.of(testUser));

            // When/Then
            assertThatThrownBy(() -> userService.updateUser(testUserId, updateRequest, managerId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "INVALID_ROLE");
        }

        @Test
        @DisplayName("Should only update provided fields (partial update)")
        void updateUser_partialUpdate_onlyUpdatesProvidedFields() {
            // Given
            UpdateUserRequest partialRequest = new UpdateUserRequest();
            partialRequest.setDisplayName("Only Display Name");
            
            given(userRepository.findById(testUserId)).willReturn(Optional.of(testUser));
            given(userRepository.save(any(User.class))).willReturn(testUser);
            given(userMapper.toResponse(any(User.class))).willReturn(testUserResponse);

            // When
            userService.updateUser(testUserId, partialRequest, managerId);

            // Then
            verify(userRepository).save(userCaptor.capture());
            User updatedUser = userCaptor.getValue();
            assertThat(updatedUser.getDisplayName()).isEqualTo("Only Display Name");
            assertThat(updatedUser.getFirstName()).isEqualTo("John"); // Unchanged
            assertThat(updatedUser.getLastName()).isEqualTo("Doe"); // Unchanged
        }
    }

    // ==================== UC-05: Delete User Tests ====================
    @Nested
    @DisplayName("UC-05: Delete User (Soft Delete)")
    class DeleteUserTests {

        @Test
        @DisplayName("Should soft delete user (set status to INACTIVE)")
        void deleteUser_validUser_setsStatusToInactive() {
            // Given
            given(userRepository.findById(testUserId)).willReturn(Optional.of(testUser));

            // When
            userService.deleteUser(testUserId, managerId);

            // Then
            verify(userRepository).save(userCaptor.capture());
            User deletedUser = userCaptor.getValue();
            assertThat(deletedUser.getStatus()).isEqualTo(UserStatus.INACTIVE);
            assertThat(deletedUser.getUpdatedBy()).isEqualTo(managerId);
        }

        @Test
        @DisplayName("Should throw exception when deleting MANAGER")
        void deleteUser_managerUser_throwsBusinessException() {
            // Given
            testUser.setRole(UserRole.MANAGER);
            given(userRepository.findById(testUserId)).willReturn(Optional.of(testUser));

            // When/Then
            assertThatThrownBy(() -> userService.deleteUser(testUserId, managerId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "CANNOT_DELETE_MANAGER");
        }

        @Test
        @DisplayName("Should throw exception when deleting ADMIN")
        void deleteUser_adminUser_throwsBusinessException() {
            // Given
            testUser.setRole(UserRole.ADMIN);
            given(userRepository.findById(testUserId)).willReturn(Optional.of(testUser));

            // When/Then
            assertThatThrownBy(() -> userService.deleteUser(testUserId, managerId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", "CANNOT_DELETE_MANAGER");
        }

        @Test
        @DisplayName("Should throw exception when user not found")
        void deleteUser_nonExistentUser_throwsResourceNotFoundException() {
            // Given
            UUID nonExistentId = UUID.randomUUID();
            given(userRepository.findById(nonExistentId)).willReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> userService.deleteUser(nonExistentId, managerId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ==================== User Self-Service: Profile Tests ====================
    @Nested
    @DisplayName("User Self-Service: Profile Management")
    class ProfileManagementTests {

        @Test
        @DisplayName("Should get own profile")
        void getMyProfile_existingUser_returnsProfile() {
            // Given
            given(userRepository.findById(testUserId)).willReturn(Optional.of(testUser));
            given(userMapper.toResponse(testUser)).willReturn(testUserResponse);

            // When
            UserResponse result = userService.getMyProfile(testUserId);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(testUserId);
        }

        @Test
        @DisplayName("Should update own profile (except email)")
        void updateMyProfile_validRequest_updatesProfile() {
            // Given
            updateProfileRequest.setFirstName("NewFirst");
            updateProfileRequest.setLastName("NewLast");
            updateProfileRequest.setDisplayName("New Display");
            updateProfileRequest.setPhoneNumber("0111222333");
            updateProfileRequest.setDateOfBirth(LocalDate.of(1995, 5, 15));
            updateProfileRequest.setGender(Gender.MALE);
            updateProfileRequest.setAvatarUrl("https://example.com/avatar.jpg");
            
            given(userRepository.findById(testUserId)).willReturn(Optional.of(testUser));
            given(userRepository.save(any(User.class))).willReturn(testUser);
            given(userMapper.toResponse(any(User.class))).willReturn(testUserResponse);

            // When
            userService.updateMyProfile(testUserId, updateProfileRequest);

            // Then
            verify(userRepository).save(userCaptor.capture());
            User updatedUser = userCaptor.getValue();
            assertThat(updatedUser.getFirstName()).isEqualTo("NewFirst");
            assertThat(updatedUser.getLastName()).isEqualTo("NewLast");
            assertThat(updatedUser.getDisplayName()).isEqualTo("New Display");
            assertThat(updatedUser.getPhoneNumber()).isEqualTo("0111222333");
            assertThat(updatedUser.getDateOfBirth()).isEqualTo(LocalDate.of(1995, 5, 15));
            assertThat(updatedUser.getGender()).isEqualTo(Gender.MALE);
            assertThat(updatedUser.getAvatarUrl()).isEqualTo("https://example.com/avatar.jpg");
            
            // Email should NOT be changed (not in request)
            assertThat(updatedUser.getEmail()).isEqualTo("test@example.com");
        }

        @Test
        @DisplayName("Should only update provided profile fields (partial update)")
        void updateMyProfile_partialUpdate_onlyUpdatesProvidedFields() {
            // Given
            UpdateProfileRequest partialRequest = new UpdateProfileRequest();
            partialRequest.setDisplayName("Only Display Name Changed");
            
            given(userRepository.findById(testUserId)).willReturn(Optional.of(testUser));
            given(userRepository.save(any(User.class))).willReturn(testUser);
            given(userMapper.toResponse(any(User.class))).willReturn(testUserResponse);

            // When
            userService.updateMyProfile(testUserId, partialRequest);

            // Then
            verify(userRepository).save(userCaptor.capture());
            User updatedUser = userCaptor.getValue();
            assertThat(updatedUser.getDisplayName()).isEqualTo("Only Display Name Changed");
            assertThat(updatedUser.getFirstName()).isEqualTo("John"); // Unchanged
            assertThat(updatedUser.getLastName()).isEqualTo("Doe"); // Unchanged
        }
    }
}

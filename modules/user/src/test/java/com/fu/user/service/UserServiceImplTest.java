package com.fu.user.service;

import com.fu.common.exception.BusinessException;
import com.fu.infrastructure.email.EmailService;
import com.fu.user.dto.*;
import com.fu.user.entity.*;
import com.fu.user.mapper.UserMapper;
import com.fu.user.repository.UserRepository;
import com.fu.user.service.impl.UserServiceImpl;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * Unit tests for UserServiceImpl.
 * Tests updated User Management use cases with new features:
 * - firstName/lastName instead of fullName
 * - Role-based permissions (ADMIN only)
 * - DeletedUserExistsException for restore flow
 * - Password generation with strict requirements
 * - Admin can view/restore deleted users
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserService Unit Tests - Updated")
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
    private User testLearner;
    private User testTeacher;
    private User testContentManager;
    private User testOperationManager;
    private User testAdmin;
    private UserResponse learnerResponse;
    private CreateUserRequest createRequest;
    private UUID adminId;
    private UUID contentManagerId;
    private UUID operationManagerId;

    @BeforeEach
    void setUp() {
        adminId = UUID.randomUUID();
        contentManagerId = UUID.randomUUID();
        operationManagerId = UUID.randomUUID();
        // Test learner
        testLearner = User.builder()
                .email("learner@example.com")
                .passwordHash("hashedPassword")
                .firstName("John")
                .lastName("Doe")
                .displayName("Johnny")
                .role(UserRole.LEARNER)
                .status(UserStatus.ACTIVE)

                .failedLoginAttempts(0)
                .build();
        ReflectionTestUtils.setField(testLearner, "id", UUID.randomUUID());

        // Test teacher
        testTeacher = User.builder()
                .email("teacher@example.com")
                .passwordHash("hashedPassword")
                .firstName("Jane")
                .lastName("Smith")
                .role(UserRole.TEACHER)
                .status(UserStatus.ACTIVE)

                .build();
        ReflectionTestUtils.setField(testTeacher, "id", UUID.randomUUID());

        // Test content manager
        testContentManager = User.builder()
                .email("manager@example.com")
                .passwordHash("hashedPassword")
                .firstName("Manager")
                .lastName("User")
                .role(UserRole.CONTENT_MANAGER)
                .status(UserStatus.ACTIVE)

                .build();
        ReflectionTestUtils.setField(testContentManager, "id", contentManagerId);
        // Test operation manager
        testOperationManager = User.builder()
                .email("manager@example.com")
                .passwordHash("hashedPassword")
                .firstName("Manager")
                .lastName("User")
                .role(UserRole.OPERATION_MANAGER)
                .status(UserStatus.ACTIVE)

                .build();
        ReflectionTestUtils.setField(testOperationManager, "id", operationManagerId);
        // Test admin
        testAdmin = User.builder()
                .email("admin@example.com")
                .passwordHash("hashedPassword")
                .firstName("Admin")
                .lastName("User")
                .role(UserRole.ADMIN)
                .status(UserStatus.ACTIVE)

                .build();
        ReflectionTestUtils.setField(testAdmin, "id", adminId);

        // Create request with firstName/lastName
        createRequest = CreateUserRequest.builder()
                .email("new@example.com")
                .firstName("New")
                .lastName("User")
                .role(UserRole.LEARNER)
                .phoneNumber("0123456789")
                .dateOfBirth(LocalDate.of(2000, 1, 1))
                .build();

        learnerResponse = UserResponse.builder()
                .id((UUID) ReflectionTestUtils.getField(testLearner, "id"))
                .email(testLearner.getEmail())
                .fullName("John Doe")
                .firstName("John")
                .lastName("Doe")
                .role(UserRole.LEARNER)
                .status(UserStatus.ACTIVE)
                .build();
    }

    @Nested
    @DisplayName("UC-02: Create User Tests")
    class CreateUserTests {

        @Test
        @DisplayName("Should create learner successfully by admin")
        void shouldCreateLearnerByAdmin() {
            // Given
            User newUser = User.builder()
                    .email(createRequest.getEmail())
                    .firstName(createRequest.getFirstName())
                    .lastName(createRequest.getLastName())
                    .role(UserRole.LEARNER)
                    .build();

            given(userRepository.findByEmail(createRequest.getEmail())).willReturn(Optional.empty());
            given(userMapper.toEntity(createRequest)).willReturn(newUser);
            given(passwordEncoder.encode(anyString())).willReturn("hashedPassword");
            given(userRepository.save(any(User.class))).willReturn(testLearner);
            given(userMapper.toResponse(testLearner)).willReturn(learnerResponse);
            doNothing().when(emailService).sendWelcomeEmailWithCredentials(anyString(), anyString(), anyString(), anyString());

            // When
            UserResponse result = userService.createUser(createRequest, adminId, UserRole.ADMIN);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getEmail()).isEqualTo(testLearner.getEmail());
            assertThat(result.getFirstName()).isEqualTo("John");
            assertThat(result.getLastName()).isEqualTo("Doe");

            verify(userRepository).findByEmail(createRequest.getEmail());
            verify(userRepository).save(userCaptor.capture());
            verify(emailService).sendWelcomeEmailWithCredentials(anyString(), anyString(), anyString(), anyString());

            User savedUser = userCaptor.getValue();
            assertThat(savedUser.getStatus()).isEqualTo(UserStatus.INACTIVE);
            assertThat(savedUser.getPasswordHash()).isNotNull();
            assertThat(savedUser.getEmailVerifiedAt()).isNotNull();
        }

        @Test
        @DisplayName("Should allow admin to create content manager")
        void shouldAllowAdminToCreateManager() {
            // Given
            CreateUserRequest managerRequest = CreateUserRequest.builder()
                    .email("newmanager@example.com")
                    .firstName("New")
                    .lastName("Manager")
                    .role(UserRole.CONTENT_MANAGER)
                    .build();

            User newManager = User.builder()
                    .email(managerRequest.getEmail())
                    .firstName(managerRequest.getFirstName())
                    .lastName(managerRequest.getLastName())
                    .role(UserRole.CONTENT_MANAGER)
                    .build();

            given(userRepository.findByEmail(managerRequest.getEmail())).willReturn(Optional.empty());
            given(userMapper.toEntity(managerRequest)).willReturn(newManager);
            given(passwordEncoder.encode(anyString())).willReturn("hashedPassword");
            given(userRepository.save(any(User.class))).willReturn(testContentManager);
            given(userMapper.toResponse(testContentManager)).willReturn(any());

            // When
            assertThatNoException().isThrownBy(() ->
                    userService.createUser(managerRequest, adminId, UserRole.ADMIN)
            );

            // Then
            verify(userRepository).save(any(User.class));
        }

        @Test
        @DisplayName("Should reject operation manager trying to create user")
        void shouldRejectManagerCreatingUser() {
            // Given
            CreateUserRequest learnerRequest = CreateUserRequest.builder()
                    .email("newlearner@example.com")
                    .firstName("New")
                    .lastName("Learner")
                    .role(UserRole.LEARNER)
                    .build();

            // When/Then
            assertThatThrownBy(() ->
                    userService.createUser(learnerRequest, operationManagerId, UserRole.OPERATION_MANAGER)
            )
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Bạn không có quyền tạo tài khoản");
        }

        @Test
        @DisplayName("Should reject creating admin role")
        void shouldRejectCreatingAdmin() {
            // Given
            CreateUserRequest adminRequest = CreateUserRequest.builder()
                    .email("newadmin@example.com")
                    .firstName("New")
                    .lastName("Admin")
                    .role(UserRole.ADMIN)
                    .build();

            // When/Then
            assertThatThrownBy(() ->
                    userService.createUser(adminRequest, adminId, UserRole.ADMIN)
            )
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Không thể tạo tài khoản ADMIN");
        }

        @Test
        @DisplayName("Should throw EMAIL_EXISTS when creating user with duplicate email")
        void shouldThrowEmailExistsWhenEmailAlreadyRegistered() {
            // Given — email "new@example.com" is already taken by an active learner
            given(userRepository.findByEmail(createRequest.getEmail()))
                    .willReturn(Optional.of(testLearner));

            // When / Then
            assertThatThrownBy(() ->
                    userService.createUser(createRequest, adminId, UserRole.ADMIN)
            )
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Email này đã được sử dụng");

            // Must NOT proceed to save or send welcome email
            verify(userRepository, never()).save(any(User.class));
            verify(emailService, never()).sendWelcomeEmailWithCredentials(
                    anyString(), anyString(), anyString(), anyString());
        }
        

        @Nested
        @DisplayName("UC-01: Search Users with Role-based Visibility")
        class SearchUsersTests {

            @Test
            @DisplayName("Content Manager should not see users")
            void managerShouldNotSeeUsers() {
                // Given
                UserFilterRequest filter = UserFilterRequest.builder().build();
                Pageable pageable = PageRequest.of(0, 10);

                // When/Then
                assertThatThrownBy(() ->
                        userService.searchUsers(filter, pageable, UserRole.CONTENT_MANAGER)
                )
                        .isInstanceOf(BusinessException.class)
                        .hasMessageContaining("Bạn không có quyền thực hiện thao tác này");
            }

            @Test
            @DisplayName("Admin should see all users including managers")
            void adminShouldSeeAllUsers() {
                // Given
                UserFilterRequest filter = UserFilterRequest.builder().build();
                Pageable pageable = PageRequest.of(0, 10);
                Page<User> page = new PageImpl<>(Arrays.asList(testLearner, testTeacher, testAdmin));

                given(userRepository.searchUsers(
                        eq(null),
                        eq(null),
                        eq(null),
                        eq(pageable)
                )).willReturn(page);
                given(userMapper.toResponse(any(User.class))).willReturn(learnerResponse);

                // When
                Page<UserResponse> result = userService.searchUsers(filter, pageable, UserRole.ADMIN);

                // Then
                assertThat(result).hasSize(3);
                verify(userRepository).searchUsers(
                        eq(null),
                        eq(null),
                        eq(null),
                        eq(pageable)
                );
            }
        }

        @Nested
        @DisplayName("UC-04: Update User with Role-based Permissions")
        class UpdateUserTests {

            @Test
            @DisplayName("Admin should update learner successfully")
            void adminShouldUpdateLearner() {
                // Given
                UUID learnerId = (UUID) ReflectionTestUtils.getField(testLearner, "id");
                UpdateUserRequest request = UpdateUserRequest.builder()
                        .firstName("Updated")
                        .lastName("Name")
                        .phoneNumber("0987654321")
                        .build();

                given(userRepository.findById(learnerId)).willReturn(Optional.of(testLearner));
                given(userRepository.save(any(User.class))).willReturn(testLearner);
                given(userMapper.toResponse(testLearner)).willReturn(learnerResponse);

                // When
                UserResponse result = userService.updateUser(learnerId, request, adminId, UserRole.ADMIN);

                // Then
                assertThat(result).isNotNull();
                verify(userRepository).save(userCaptor.capture());
                User updated = userCaptor.getValue();
                assertThat(updated.getFirstName()).isEqualTo("Updated");
                assertThat(updated.getLastName()).isEqualTo("Name");
            }

            @Test
            @DisplayName("Admin should update manager successfully")
            void adminShouldUpdateManager() {
                // Given
                UUID managerId2 = (UUID) ReflectionTestUtils.getField(testContentManager, "id");
                UpdateUserRequest request = UpdateUserRequest.builder()
                        .firstName("Updated Manager")
                        .build();

                given(userRepository.findById(managerId2)).willReturn(Optional.of(testContentManager));
                given(userRepository.save(any(User.class))).willReturn(testContentManager);
                given(userMapper.toResponse(testContentManager)).willReturn(any());

                // When
                assertThatNoException().isThrownBy(() ->
                        userService.updateUser(managerId2, request, adminId, UserRole.ADMIN)
                );

                // Then
                verify(userRepository).save(any(User.class));
            }

            @Test
            @DisplayName("Should reject updating status to DELETED")
            void shouldRejectUpdatingStatusToDeleted() {
                UUID learnerId = (UUID) ReflectionTestUtils.getField(testLearner, "id");
                UpdateUserRequest request = UpdateUserRequest.builder()
                        .status(UserStatus.DELETED)
                        .build();

                given(userRepository.findById(learnerId)).willReturn(Optional.of(testLearner));

                assertThatThrownBy(() ->
                        userService.updateUser(learnerId, request, adminId, UserRole.ADMIN)
                ).isInstanceOf(BusinessException.class)
                        .hasMessageContaining("Không thể cập nhật trạng thái thành DELETED");

                verify(userRepository, never()).save(any(User.class));
            }

            @Test
            @DisplayName("Should update user with valid new email")
            void shouldUpdateUserWithValidNewEmail() {
                UUID learnerId = (UUID) ReflectionTestUtils.getField(testLearner, "id");
                String newEmail = "newemail@example.com";
                UpdateUserRequest request = UpdateUserRequest.builder()
                        .firstName("Updated")
                        .email(newEmail)
                        .build();

                given(userRepository.findById(learnerId)).willReturn(Optional.of(testLearner));
                given(userRepository.findByEmail(newEmail)).willReturn(Optional.empty());
                given(userRepository.save(any(User.class))).willReturn(testLearner);
                given(userMapper.toResponse(testLearner)).willReturn(learnerResponse);

                // When
                UserResponse result = userService.updateUser(learnerId, request, adminId, UserRole.ADMIN);

                // Then
                assertThat(result).isNotNull();
                verify(userRepository).save(userCaptor.capture());
                User updated = userCaptor.getValue();
                assertThat(updated.getEmail()).isEqualTo(newEmail);
            }

            @Test
            @DisplayName("Should skip email validation when email is not changed")
            void shouldSkipEmailValidationWhenEmailNotChanged() {
                UUID learnerId = (UUID) ReflectionTestUtils.getField(testLearner, "id");
                UpdateUserRequest request = UpdateUserRequest.builder()
                        .firstName("Updated")
                        .lastName("Name")
                        .build(); // No email in request

                given(userRepository.findById(learnerId)).willReturn(Optional.of(testLearner));
                given(userRepository.save(any(User.class))).willReturn(testLearner);
                given(userMapper.toResponse(testLearner)).willReturn(learnerResponse);

                // When
                UserResponse result = userService.updateUser(learnerId, request, adminId, UserRole.ADMIN);

                // Then
                assertThat(result).isNotNull();
                verify(userRepository, never()).findByEmail(any(String.class));
                verify(userRepository).save(userCaptor.capture());
                User updated = userCaptor.getValue();
                assertThat(updated.getFirstName()).isEqualTo("Updated");
            }

            @Test
            @DisplayName("Should reject duplicate email when updating user email")
            void shouldRejectDuplicateEmailWhenUpdating() {
                UUID learnerId = (UUID) ReflectionTestUtils.getField(testLearner, "id");
                String duplicateEmail = "teacher@example.com";
                UpdateUserRequest request = UpdateUserRequest.builder()
                        .email(duplicateEmail)
                        .build();

                User existingUserWithEmail = testTeacher; // Different user with same email

                given(userRepository.findById(learnerId)).willReturn(Optional.of(testLearner));
                given(userRepository.findByEmail(duplicateEmail)).willReturn(Optional.of(existingUserWithEmail));

                // When/Then
                assertThatThrownBy(() ->
                        userService.updateUser(learnerId, request, adminId, UserRole.ADMIN)
                ).isInstanceOf(BusinessException.class)
                        .hasMessageContaining("Email này đã được sử dụng");

                verify(userRepository, never()).save(any(User.class));
            }

            @Test
            @DisplayName("Should handle email with whitespace and case-insensitive comparison")
            void shouldTrimAndCompareEmailCaseInsensitive() {
                UUID learnerId = (UUID) ReflectionTestUtils.getField(testLearner, "id");
                // testLearner has "learner@example.com" (from setUp)
                // Request has same email with different case and whitespace
                UpdateUserRequest request = UpdateUserRequest.builder()
                        .email("  LEARNER@EXAMPLE.COM  ")
                        .build();

                given(userRepository.findById(learnerId)).willReturn(Optional.of(testLearner));
                given(userRepository.save(any(User.class))).willReturn(testLearner);
                given(userMapper.toResponse(testLearner)).willReturn(learnerResponse);

                // When - should NOT call findByEmail because email is same (case-insensitive)
                UserResponse result = userService.updateUser(learnerId, request, adminId, UserRole.ADMIN);

                // Then - email validation should be skipped
                assertThat(result).isNotNull();
                verify(userRepository, never()).findByEmail(any(String.class));
                verify(userRepository).save(userCaptor.capture());
            }

            @Test
            @DisplayName("Should not update email when email is null in request")
            void shouldNotUpdateEmailWhenNull() {
                UUID learnerId = (UUID) ReflectionTestUtils.getField(testLearner, "id");
                UpdateUserRequest request = UpdateUserRequest.builder()
                        .firstName("Updated")
                        .email(null)
                        .build();

                given(userRepository.findById(learnerId)).willReturn(Optional.of(testLearner));
                given(userRepository.save(any(User.class))).willReturn(testLearner);
                given(userMapper.toResponse(testLearner)).willReturn(learnerResponse);

                // When
                UserResponse result = userService.updateUser(learnerId, request, adminId, UserRole.ADMIN);

                // Then
                assertThat(result).isNotNull();
                verify(userRepository, never()).findByEmail(any(String.class));
                verify(userRepository).save(userCaptor.capture());
                User updated = userCaptor.getValue();
                // Email should remain unchanged
                assertThat(updated.getEmail()).isEqualTo(testLearner.getEmail());
            }

            @Test
            @DisplayName("Should not update email when email is blank (only whitespace)")
            void shouldNotUpdateEmailWhenBlank() {
                UUID learnerId = (UUID) ReflectionTestUtils.getField(testLearner, "id");
                UpdateUserRequest request = UpdateUserRequest.builder()
                        .firstName("Updated")
                        .email("     ") // Only whitespace
                        .build();

                given(userRepository.findById(learnerId)).willReturn(Optional.of(testLearner));
                given(userRepository.save(any(User.class))).willReturn(testLearner);
                given(userMapper.toResponse(testLearner)).willReturn(learnerResponse);

                // When
                UserResponse result = userService.updateUser(learnerId, request, adminId, UserRole.ADMIN);

                // Then - blank email should be treated like null, no email check/update
                assertThat(result).isNotNull();
                verify(userRepository, never()).findByEmail(any(String.class));
                verify(userRepository).save(userCaptor.capture());
                User updated = userCaptor.getValue();
                // Email should remain unchanged
                assertThat(updated.getEmail()).isEqualTo(testLearner.getEmail());
            }
        }

        @Nested
        @DisplayName("UC-05: Delete User with Role-based Permissions")
        class DeleteUserTests {

            @Test
            @DisplayName("Admin should delete learner successfully")
            void adminShouldDeleteLearner() {
                // Given
                UUID learnerId = (UUID) ReflectionTestUtils.getField(testLearner, "id");
                given(userRepository.findById(learnerId)).willReturn(Optional.of(testLearner));
                given(userRepository.save(any(User.class))).willReturn(testLearner);

                // When
                userService.deleteUser(learnerId, adminId, UserRole.ADMIN);

                // Then
                verify(userRepository).save(userCaptor.capture());
                User deleted = userCaptor.getValue();
                assertThat(deleted.getStatus()).isEqualTo(UserStatus.DELETED);
                assertThat(deleted.getDeletedAt()).isNotNull();
                assertThat(deleted.getDeletedBy()).isEqualTo(adminId);
            }

            @Test
            @DisplayName("Admin should delete manager successfully")
            void adminShouldDeleteManager() {
                // Given
                UUID managerId2 = (UUID) ReflectionTestUtils.getField(testContentManager, "id");
                given(userRepository.findById(managerId2)).willReturn(Optional.of(testContentManager));
                given(userRepository.save(any(User.class))).willReturn(testContentManager);

                // When
                assertThatNoException().isThrownBy(() ->
                        userService.deleteUser(managerId2, adminId, UserRole.ADMIN)
                );

                // Then
                verify(userRepository).save(userCaptor.capture());
                User deleted = userCaptor.getValue();
                assertThat(deleted.getDeletedAt()).isNotNull();
            }
        }

        @Nested
        @DisplayName("Admin Operations: Deleted Users")
        class AdminDeletedUsersTests {

            @Test
            @DisplayName("Admin should view deleted users")
            void adminShouldViewDeletedUsers() {
                // Given
                User deletedUser1 = User.builder()
                        .email("deleted1@example.com")
                        .firstName("Deleted")
                        .lastName("User1")
                        .role(UserRole.LEARNER)
                        .status(UserStatus.INACTIVE)
                        .deletedAt(LocalDateTime.now().minusDays(1))
                        .build();

                Pageable pageable = PageRequest.of(0, 10);
                Page<User> page = new PageImpl<>(Arrays.asList(deletedUser1));

                given(userRepository.findAllDeleted(pageable)).willReturn(page);
                given(userMapper.toResponse(any(User.class))).willReturn(learnerResponse);

                // When
                Page<UserResponse> result = userService.getDeletedUsers(pageable);

                // Then
                assertThat(result).hasSize(1);
                verify(userRepository).findAllDeleted(pageable);
            }

            @Test
            @DisplayName("Should restore deleted user with new information")
            void shouldRestoreDeletedUser() {
                // Given
                UUID deletedUserId = UUID.randomUUID();
                User deletedUser = User.builder()
                        .email("deleted@example.com")
                        .firstName("Old")
                        .lastName("Name")
                        .role(UserRole.LEARNER)
                        .status(UserStatus.INACTIVE)
                        .deletedAt(LocalDateTime.now().minusDays(1))
                        .deletedBy(adminId)
                        .build();
                ReflectionTestUtils.setField(deletedUser, "id", deletedUserId);

                RestoreUserRequest request = RestoreUserRequest.builder()
                        .userId(deletedUserId)
                        .email("deleted@example.com")
                        .firstName("New")
                        .lastName("Name")
                        .role(UserRole.LEARNER)
                        .build();

                given(userRepository.findById(deletedUserId)).willReturn(Optional.of(deletedUser));
                given(passwordEncoder.encode(anyString())).willReturn("newHashedPassword");
                given(userRepository.save(any(User.class))).willReturn(deletedUser);
                given(userMapper.toResponse(deletedUser)).willReturn(learnerResponse);
                doNothing().when(emailService).sendWelcomeEmailWithCredentials(anyString(), anyString(), anyString(), anyString());

                // When
                UserResponse result = userService.restoreUser(request, adminId, UserRole.ADMIN);

                // Then
                assertThat(result).isNotNull();
                verify(userRepository).save(userCaptor.capture());
                verify(emailService).sendWelcomeEmailWithCredentials(anyString(), anyString(), anyString(), anyString());

                User restored = userCaptor.getValue();
                assertThat(restored.getFirstName()).isEqualTo("New");
                assertThat(restored.getLastName()).isEqualTo("Name");
                assertThat(restored.getStatus()).isEqualTo(UserStatus.ACTIVE);
                assertThat(restored.getDeletedAt()).isNull();
                assertThat(restored.getDeletedBy()).isNull();
                assertThat(restored.getEmailVerifiedAt()).isNotNull();
            }

            @Test
            @DisplayName("Should reject restoring non-deleted user")
            void shouldRejectRestoringNonDeletedUser() {
                // Given
                UUID userId = (UUID) ReflectionTestUtils.getField(testLearner, "id");
                RestoreUserRequest request = RestoreUserRequest.builder()
                        .userId(userId)
                        .email(testLearner.getEmail())
                        .firstName("New")
                        .lastName("Name")
                        .role(UserRole.LEARNER)
                        .build();

                given(userRepository.findById(userId)).willReturn(Optional.of(testLearner));

                // When/Then
                assertThatThrownBy(() ->
                        userService.restoreUser(request, adminId, UserRole.ADMIN)
                )
                        .isInstanceOf(BusinessException.class)
                        .hasMessageContaining("Tài khoản này chưa bị xóa");
            }
        }

        @Nested
        @DisplayName("User Detail with Enrollment History")
        class UserDetailTests {

            @Test
            @DisplayName("Should return learner details with enrollment structure")
            void shouldReturnLearnerDetailsWithEnrollments() {
                // Given
                UUID learnerId = (UUID) ReflectionTestUtils.getField(testLearner, "id");
                given(userRepository.findById(learnerId)).willReturn(Optional.of(testLearner));
                given(userMapper.toResponse(testLearner)).willReturn(learnerResponse);

                // When
                UserDetailResponse result = userService.getUserDetailById(UserRole.ADMIN, learnerId);

                // Then
                assertThat(result).isNotNull();
                assertThat(result.getUserInfo()).isEqualTo(learnerResponse);
                assertThat(result.getCourseEnrollments()).isEmpty(); // Empty until course module available
                assertThat(result.getClassEnrollments()).isEmpty();
                assertThat(result.getTotalCourses()).isEqualTo(0);
            }

            @Test
            @DisplayName("Should return teacher details with taught classes structure")
            void shouldReturnTeacherDetailsWithTaughtClasses() {
                // Given
                UUID teacherId = (UUID) ReflectionTestUtils.getField(testTeacher, "id");
                UserResponse teacherResponse = UserResponse.builder()
                        .id(teacherId)
                        .email(testTeacher.getEmail())
                        .role(UserRole.TEACHER)
                        .build();

                given(userRepository.findById(teacherId)).willReturn(Optional.of(testTeacher));
                given(userMapper.toResponse(testTeacher)).willReturn(teacherResponse);

                // When
                UserDetailResponse result = userService.getUserDetailById(UserRole.ADMIN, teacherId);

                // Then
                assertThat(result).isNotNull();
                assertThat(result.getUserInfo().getRole()).isEqualTo(UserRole.TEACHER);
                assertThat(result.getTaughtClasses()).isEmpty(); // Empty until class module available
                assertThat(result.getTotalClasses()).isEqualTo(0);
            }

            @Test
            @DisplayName("Content Manager should not view user detail")
            void managerShouldNotViewUserDetail() {
                UUID learnerId = (UUID) ReflectionTestUtils.getField(testLearner, "id");

                assertThatThrownBy(() ->
                        userService.getUserDetailById(UserRole.CONTENT_MANAGER, learnerId)
                ).isInstanceOf(BusinessException.class)
                        .hasMessageContaining("Bạn không có quyền xem chi tiết tài khoản này");

                verify(userMapper, never()).toResponse(any(User.class));
            }
        }

        @Nested
        @DisplayName("User Self-Service Operations")
        class SelfServiceTests {

            @Test
            @DisplayName("User should view own profile")
            void userShouldViewOwnProfile() {
                // Given
                UUID userId = (UUID) ReflectionTestUtils.getField(testLearner, "id");
                given(userRepository.findById(userId)).willReturn(Optional.of(testLearner));
                given(userMapper.toResponse(testLearner)).willReturn(learnerResponse);

                // When
                UserResponse result = userService.getMyProfile(userId);

                // Then
                assertThat(result).isNotNull();
                assertThat(result.getEmail()).isEqualTo(testLearner.getEmail());
            }

            @Test
            @DisplayName("User should update own profile")
            void userShouldUpdateOwnProfile() {
                // Given
                UUID userId = (UUID) ReflectionTestUtils.getField(testLearner, "id");
                UpdateProfileRequest request = UpdateProfileRequest.builder()
                        .firstName("Updated")
                        .lastName("Profile")
                        .displayName("My Display Name")
                        .phoneNumber("0987654321")
                        .gender(Gender.MALE)
                        .build();

                given(userRepository.findById(userId)).willReturn(Optional.of(testLearner));
                given(userRepository.save(any(User.class))).willReturn(testLearner);
                given(userMapper.toResponse(testLearner)).willReturn(learnerResponse);

                // When
                UserResponse result = userService.updateMyProfile(userId, request);

                // Then
                assertThat(result).isNotNull();
                verify(userRepository).save(userCaptor.capture());
                User updated = userCaptor.getValue();
                assertThat(updated.getFirstName()).isEqualTo("Updated");
                assertThat(updated.getDisplayName()).isEqualTo("My Display Name");
            }
        }
        @Nested
        @DisplayName("Import Users Tests")
        class ImportUsersTests {

            @Test
            @DisplayName("Should approve import successfully (Create & Update)")
            void shouldApproveImportSuccessfully() {
                // Given
                ImportCreateUserRequest createReq = ImportCreateUserRequest.builder()
                        .email("new@example.com")
                        .fullName("New User One")
                        .role(UserRole.LEARNER)
                        .build();

                UUID existingId = UUID.randomUUID();
                ImportUpdateUserRequest updateReq = ImportUpdateUserRequest.builder()
                        .userId(existingId)
                        .email("existing@example.com")
                        .fullName("Updated Name")
                        .phoneNumber("0988888888")
                        .build();

                ApproveImportRequest request = ApproveImportRequest.builder()
                        .createUserRequests(List.of(createReq))
                        .updateUserRequests(List.of(updateReq))
                        .build();

                User existingUser = User.builder()
                        .email("existing@example.com")
                        .firstName("Old")
                        .lastName("Name")
                        .phoneNumber("0123456789")
                        .build();
                ReflectionTestUtils.setField(existingUser, "id", existingId);

                // Mock Repository calls
                given(userRepository.findByEmailIn(anyList())).willReturn(List.of()); // No existing email for create list
                given(userRepository.findAllById(anyList())).willReturn(List.of(existingUser));
                given(userRepository.saveAll(anyList())).willAnswer(invocation -> invocation.getArgument(0));

                // Mock Mapper
                given(userMapper.toResponseList(anyList())).willReturn(List.of(new UserResponse()));
                
                // Mock Security/Email
                given(passwordEncoder.encode(anyString())).willReturn("hashed");
                doNothing().when(emailService).sendWelcomeEmailWithCredentials(anyString(), anyString(), anyString(), anyString());

                // When
                ImportUserResponse response = userService.approveImport(request, adminId);

                // Then
                assertThat(response).isNotNull();
                verify(userRepository, times(2)).saveAll(anyList()); // One for create, one for update
                verify(emailService).sendWelcomeEmailWithCredentials(eq("new@example.com"), anyString(), eq("new@example.com"), anyString());
            }

            @Test
            @DisplayName("Should skip creation if email already exists in Database")
            void shouldSkipCreateIfEmailExists() {
                // Given
                ImportCreateUserRequest createReq = ImportCreateUserRequest.builder()
                        .email("existing@example.com") // Already in DB
                        .fullName("Duplicate User")
                        .role(UserRole.LEARNER)
                        .build();

                ApproveImportRequest request = ApproveImportRequest.builder()
                        .createUserRequests(List.of(createReq))
                        .updateUserRequests(List.of())
                        .build();

                User existingUser = User.builder().email("existing@example.com").build();
                given(userRepository.findByEmailIn(anyList())).willReturn(List.of(existingUser));
                given(userMapper.toResponseList(anyList())).willReturn(List.of());

                // When
                ImportUserResponse response = userService.approveImport(request, adminId);

                // Then
                assertThat(response.getCreated()).isEmpty();
                verify(userRepository, never()).saveAll(anyList()); // No saveAll calls because both lists are empty
                verify(emailService, never()).sendWelcomeEmailWithCredentials(anyString(), anyString(), anyString(), anyString());
            }
        }
    }

    // ==================== importUsersFromExcel Tests ====================

    @Nested
    @DisplayName("UC-Import: importUsersFromExcel Unit Tests")
    class ImportUsersFromExcelTests {

        // ── Helper: build in-memory xlsx MultipartFile ──────────────────────────
        private MultipartFile buildExcelFile(Consumer<XSSFWorkbook> setup) throws Exception {
            XSSFWorkbook wb = new XSSFWorkbook();
            setup.accept(wb);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            wb.close();
            return new MockMultipartFile(
                    "file", "users.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    bos.toByteArray());
        }

        /**
         * Sheet 0 (Learners): col8=fullName, col9=birthDay, col10=phone, col11=email
         */
        private void writeLearnerRow(XSSFSheet sheet, int rowIndex,
                                     String fullName, String birthDay, String phone, String email) {
            var row = sheet.createRow(rowIndex);
            row.createCell(8).setCellValue(fullName  != null ? fullName  : "");
            row.createCell(9).setCellValue(birthDay  != null ? birthDay  : "");
            row.createCell(10).setCellValue(phone    != null ? phone     : "");
            row.createCell(11).setCellValue(email    != null ? email     : "");
        }

        /**
         * Sheet 1 (Teachers): col1=fullName, col2=birthDay, col3=phone, col4=email
         */
        private void writeTeacherRow(XSSFSheet sheet, int rowIndex,
                                     String fullName, String birthDay, String phone, String email) {
            var row = sheet.createRow(rowIndex);
            row.createCell(1).setCellValue(fullName  != null ? fullName  : "");
            row.createCell(2).setCellValue(birthDay  != null ? birthDay  : "");
            row.createCell(3).setCellValue(phone     != null ? phone     : "");
            row.createCell(4).setCellValue(email     != null ? email     : "");
        }

        // ─────────────────────────────────────────────────────────────────────
        // 1. Happy Path
        // ─────────────────────────────────────────────────────────────────────

        @Test
        @DisplayName("UT-01: Valid file, sheet 0 has 1 new LEARNER row → created==1, updated==0, errors==0")
        void ut01_newLearnerRow() throws Exception {
            // TC: UT-01
            MultipartFile file = buildExcelFile(wb -> {
                XSSFSheet s0 = wb.createSheet("Learners");
                s0.createRow(0); // header
                writeLearnerRow(s0, 1, "Nguyen Van A", "15/08/2000", "0123456789", "newlearner@test.com");
            });
            given(userRepository.findByEmailIn(any(Set.class))).willReturn(List.of());

            ImportUsersResult result = userService.importUsersFromExcel(file, adminId);

            assertThat(result.getCreated()).hasSize(1);
            assertThat(result.getUpdated()).hasSize(0);
            assertThat(result.getErrors()).hasSize(0);
        }

        @Test
        @DisplayName("UT-02: Valid file, sheet 1 has 1 new TEACHER row → created contains user with role==TEACHER")
        void ut02_newTeacherRow() throws Exception {
            // TC: UT-02
            MultipartFile file = buildExcelFile(wb -> {
                wb.createSheet("Learners"); // empty sheet 0
                XSSFSheet s1 = wb.createSheet("Teachers");
                s1.createRow(0);
                writeTeacherRow(s1, 1, "Le Thi B", "20/05/1990", "0987654321", "newteacher@test.com");
            });
            given(userRepository.findByEmailIn(any(Set.class))).willReturn(List.of());

            ImportUsersResult result = userService.importUsersFromExcel(file, adminId);

            assertThat(result.getCreated()).hasSize(1);
            assertThat(result.getCreated().get(0).getRole()).isEqualTo(UserRole.TEACHER);
        }

        @Test
        @DisplayName("UT-03: Email already exists in DB, name/phone changed → updated==1, created==0")
        void ut03_existingEmailInfoChanged() throws Exception {
            // TC: UT-03
            MultipartFile file = buildExcelFile(wb -> {
                XSSFSheet s0 = wb.createSheet("Learners");
                s0.createRow(0);
                writeLearnerRow(s0, 1, "Nguyen Van Updated", "15/08/2000", "0111111111", "existing@test.com");
            });
            User existing = User.builder().email("existing@test.com")
                    .firstName("Old").lastName("Name").phoneNumber("0999999999").build();
            given(userRepository.findByEmailIn(any(Set.class))).willReturn(List.of(existing));

            ImportUsersResult result = userService.importUsersFromExcel(file, adminId);

            assertThat(result.getUpdated()).hasSize(1);
            assertThat(result.getCreated()).hasSize(0);
        }

        @Test
        @DisplayName("UT-04: Email exists, no change in name/phone → updated==0 (not added to updated list)")
        void ut04_existingEmailNoChange() throws Exception {
            // TC: UT-04
            // "Nguyen Van A" → firstName="Nguyen Van", lastName="A"
            MultipartFile file = buildExcelFile(wb -> {
                XSSFSheet s0 = wb.createSheet("Learners");
                s0.createRow(0);
                writeLearnerRow(s0, 1, "Nguyen Van A", "15/08/2000", "0123456789", "same@test.com");
            });
            User existing = User.builder().email("same@test.com")
                    .firstName("Nguyen Van").lastName("A").phoneNumber("0123456789").build();
            given(userRepository.findByEmailIn(any(Set.class))).willReturn(List.of(existing));

            ImportUsersResult result = userService.importUsersFromExcel(file, adminId);

            assertThat(result.getUpdated()).hasSize(0);
        }

        @Test
        @DisplayName("UT-05: Mix of new + existing users → correct created and updated counts")
        void ut05_mixedNewAndExisting() throws Exception {
            // TC: UT-05
            MultipartFile file = buildExcelFile(wb -> {
                XSSFSheet s0 = wb.createSheet("Learners");
                s0.createRow(0);
                writeLearnerRow(s0, 1, "New User One",   "01/01/2000", "0111111111", "new1@test.com");
                writeLearnerRow(s0, 2, "Existing User",  "02/02/1990", "0999999999", "existing@test.com");
            });
            User existing = User.builder().email("existing@test.com")
                    .firstName("Old").lastName("Name").phoneNumber("0888888888").build();
            given(userRepository.findByEmailIn(any(Set.class))).willReturn(List.of(existing));

            ImportUsersResult result = userService.importUsersFromExcel(file, adminId);

            assertThat(result.getCreated()).hasSize(1);
            assertThat(result.getUpdated()).hasSize(1);
        }

        @Test
        @DisplayName("UT-06: Both sheet 0 and sheet 1 have valid rows → totalRows == total valid rows from both sheets")
        void ut06_bothSheetsProcessed() throws Exception {
            // TC: UT-06
            MultipartFile file = buildExcelFile(wb -> {
                XSSFSheet s0 = wb.createSheet("Learners");
                s0.createRow(0);
                writeLearnerRow(s0, 1, "Learner One", "01/01/2000", "0111111111", "l1@test.com");
                writeLearnerRow(s0, 2, "Learner Two", "02/02/2001", "0222222222", "l2@test.com");
                XSSFSheet s1 = wb.createSheet("Teachers");
                s1.createRow(0);
                writeTeacherRow(s1, 1, "Teacher One", "03/03/1985", "0333333333", "t1@test.com");
            });
            given(userRepository.findByEmailIn(any(Set.class))).willReturn(List.of());

            ImportUsersResult result = userService.importUsersFromExcel(file, adminId);

            assertThat(result.getTotalRows()).isEqualTo(3);
        }

        @Test
        @DisplayName("UT-07: Workbook has >2 sheets → only sheet 0 and 1 processed, sheet 2+ ignored")
        void ut07_moreThanTwoSheetsIgnored() throws Exception {
            // TC: UT-07
            MultipartFile file = buildExcelFile(wb -> {
                XSSFSheet s0 = wb.createSheet("Learners");
                s0.createRow(0);
                writeLearnerRow(s0, 1, "Learner One", "01/01/2000", "0111111111", "learner@test.com");
                XSSFSheet s1 = wb.createSheet("Teachers");
                s1.createRow(0); // no data
                // Sheet 2 — must be ignored
                XSSFSheet s2 = wb.createSheet("Extra");
                s2.createRow(0);
                writeTeacherRow(s2, 1, "Extra User", "05/05/1995", "0555555555", "extra@test.com");
            });
            given(userRepository.findByEmailIn(any(Set.class))).willReturn(List.of());

            ImportUsersResult result = userService.importUsersFromExcel(file, adminId);

            // Only sheet0's learner should be counted
            assertThat(result.getTotalRows()).isEqualTo(1);
            assertThat(result.getCreated()).hasSize(1);
        }

        // ─────────────────────────────────────────────────────────────────────
        // 2. Row Skipping & Empty Row Handling
        // ─────────────────────────────────────────────────────────────────────

        @Test
        @DisplayName("UT-08: Header row (rowNum==0) is skipped, not counted in totalRows")
        void ut08_headerRowSkipped() throws Exception {
            // TC: UT-08
            MultipartFile file = buildExcelFile(wb -> {
                XSSFSheet s0 = wb.createSheet("Learners");
                writeLearnerRow(s0, 0, "FullName", "BirthDay", "Phone", "Email"); // header only
            });
            given(userRepository.findByEmailIn(any(Set.class))).willReturn(List.of());

            ImportUsersResult result = userService.importUsersFromExcel(file, adminId);

            assertThat(result.getTotalRows()).isEqualTo(0);
            assertThat(result.getErrors()).isEmpty();
        }

        @Test
        @DisplayName("UT-09: Completely empty row → skipped silently, not in totalRows, not in errors")
        void ut09_completelyEmptyRowSkipped() throws Exception {
            // TC: UT-09
            MultipartFile file = buildExcelFile(wb -> {
                XSSFSheet s0 = wb.createSheet("Learners");
                s0.createRow(0); // header
                s0.createRow(1); // empty row (no cells)
            });
            given(userRepository.findByEmailIn(any(Set.class))).willReturn(List.of());

            ImportUsersResult result = userService.importUsersFromExcel(file, adminId);

            assertThat(result.getTotalRows()).isEqualTo(0);
            assertThat(result.getErrors()).isEmpty();
        }

        @Test
        @DisplayName("UT-10: Partially filled row → counted in totalRows, goes through validation → may produce errors")
        void ut10_partiallyFilledRowValidated() throws Exception {
            // TC: UT-10
            MultipartFile file = buildExcelFile(wb -> {
                XSSFSheet s0 = wb.createSheet("Learners");
                s0.createRow(0); // header
                // Only email is non-blank; fullName, birthDay, phone are blank
                writeLearnerRow(s0, 1, "", "", "", "partial@test.com");
            });
            given(userRepository.findByEmailIn(any(Set.class))).willReturn(List.of());

            ImportUsersResult result = userService.importUsersFromExcel(file, adminId);

            assertThat(result.getTotalRows()).isEqualTo(1);
            assertThat(result.getErrors()).isNotEmpty();
        }

        // ─────────────────────────────────────────────────────────────────────
        // 3. Data Validation → errors
        // ─────────────────────────────────────────────────────────────────────

        @Test
        @DisplayName("UT-11: Email blank → row into errors, not created/updated")
        void ut11_emptyEmail() throws Exception {
            // TC: UT-11
            MultipartFile file = buildExcelFile(wb -> {
                XSSFSheet s0 = wb.createSheet("Learners");
                s0.createRow(0);
                writeLearnerRow(s0, 1, "Nguyen Van A", "15/08/2000", "0123456789", "");
            });
            given(userRepository.findByEmailIn(any(Set.class))).willReturn(List.of());

            ImportUsersResult result = userService.importUsersFromExcel(file, adminId);

            assertThat(result.getErrors()).hasSize(1);
            assertThat(result.getCreated()).isEmpty();
            assertThat(result.getUpdated()).isEmpty();
        }

        @Test
        @DisplayName("UT-12: Invalid email format → row into errors")
        void ut12_invalidEmailFormat() throws Exception {
            // TC: UT-12
            MultipartFile file = buildExcelFile(wb -> {
                XSSFSheet s0 = wb.createSheet("Learners");
                s0.createRow(0);
                writeLearnerRow(s0, 1, "Nguyen Van A", "15/08/2000", "0123456789", "notanemail");
            });
            given(userRepository.findByEmailIn(any(Set.class))).willReturn(List.of());

            ImportUsersResult result = userService.importUsersFromExcel(file, adminId);

            assertThat(result.getErrors()).hasSize(1);
            assertThat(result.getErrors().get(0).getEmailErrorMessage()).isNotBlank();
        }

        @Test
        @DisplayName("UT-13: fullName blank → row into errors")
        void ut13_emptyFullName() throws Exception {
            // TC: UT-13
            MultipartFile file = buildExcelFile(wb -> {
                XSSFSheet s0 = wb.createSheet("Learners");
                s0.createRow(0);
                writeLearnerRow(s0, 1, "", "15/08/2000", "0123456789", "valid@test.com");
            });
            given(userRepository.findByEmailIn(any(Set.class))).willReturn(List.of());

            ImportUsersResult result = userService.importUsersFromExcel(file, adminId);

            assertThat(result.getErrors()).hasSize(1);
            assertThat(result.getErrors().get(0).getFullNameErrorMessage()).isNotBlank();
        }

        @Test
        @DisplayName("UT-14: Phone number wrong format → row into errors")
        void ut14_invalidPhoneFormat() throws Exception {
            // TC: UT-14
            MultipartFile file = buildExcelFile(wb -> {
                XSSFSheet s0 = wb.createSheet("Learners");
                s0.createRow(0);
                writeLearnerRow(s0, 1, "Nguyen Van A", "15/08/2000", "abc123", "valid@test.com");
            });
            given(userRepository.findByEmailIn(any(Set.class))).willReturn(List.of());

            ImportUsersResult result = userService.importUsersFromExcel(file, adminId);

            assertThat(result.getErrors()).hasSize(1);
            assertThat(result.getErrors().get(0).getPhoneNumberErrorMessage()).isNotBlank();
        }

        @Test
        @DisplayName("UT-15: birthDay wrong format (not dd/MM/yyyy, e.g. 2000-01-01) → row into errors")
        void ut15_birthdayWrongFormat() throws Exception {
            // TC: UT-15
            MultipartFile file = buildExcelFile(wb -> {
                XSSFSheet s0 = wb.createSheet("Learners");
                s0.createRow(0);
                writeLearnerRow(s0, 1, "Nguyen Van A", "2000-01-01", "0123456789", "valid@test.com");
            });
            given(userRepository.findByEmailIn(any(Set.class))).willReturn(List.of());

            ImportUsersResult result = userService.importUsersFromExcel(file, adminId);

            assertThat(result.getErrors()).hasSize(1);
            assertThat(result.getErrors().get(0).getBirthDayErrorMessage()).isNotBlank();
        }

        @Test
        @DisplayName("UT-16: birthDay is impossible date (31/02/2000) → row into errors")
        void ut16_birthdayImpossibleDate() throws Exception {
            // TC: UT-16
            MultipartFile file = buildExcelFile(wb -> {
                XSSFSheet s0 = wb.createSheet("Learners");
                s0.createRow(0);
                writeLearnerRow(s0, 1, "Nguyen Van A", "31/02/2000", "0123456789", "valid@test.com");
            });
            given(userRepository.findByEmailIn(any(Set.class))).willReturn(List.of());

            ImportUsersResult result = userService.importUsersFromExcel(file, adminId);

            assertThat(result.getErrors()).hasSize(1);
            assertThat(result.getErrors().get(0).getBirthDayErrorMessage()).isNotBlank();
        }

        @Test
        @DisplayName("UT-17: Row with multiple invalid fields → only ONE ImportRowError entry for that row")
        void ut17_multipleInvalidFieldsOneError() throws Exception {
            // TC: UT-17
            MultipartFile file = buildExcelFile(wb -> {
                XSSFSheet s0 = wb.createSheet("Learners");
                s0.createRow(0);
                // email bad, fullName empty, phone bad
                writeLearnerRow(s0, 1, "", "15/08/2000", "badphone", "notanemail");
            });
            given(userRepository.findByEmailIn(any(Set.class))).willReturn(List.of());

            ImportUsersResult result = userService.importUsersFromExcel(file, adminId);

            // One entry (not one per field)
            assertThat(result.getErrors()).hasSize(1);
        }

        // ─────────────────────────────────────────────────────────────────────
        // 4. Duplicate Email Within File
        // ─────────────────────────────────────────────────────────────────────

        @Test
        @DisplayName("UT-18: Email appears twice in same sheet → first valid, second into errors with 'trùng lặp'")
        void ut18_duplicateEmailSameSheet() throws Exception {
            // TC: UT-18
            MultipartFile file = buildExcelFile(wb -> {
                XSSFSheet s0 = wb.createSheet("Learners");
                s0.createRow(0);
                writeLearnerRow(s0, 1, "Nguyen Van A", "15/08/2000", "0111111111", "dup@test.com");
                writeLearnerRow(s0, 2, "Nguyen Van A", "15/08/2000", "0111111111", "dup@test.com");
            });
            given(userRepository.findByEmailIn(any(Set.class))).willReturn(List.of());

            ImportUsersResult result = userService.importUsersFromExcel(file, adminId);

            assertThat(result.getCreated()).hasSize(1);
            assertThat(result.getErrors()).hasSize(1);
            assertThat(result.getErrors().get(0).getEmailErrorMessage()).contains("trùng lặp");
        }

        @Test
        @DisplayName("UT-19: Same email in sheet 0 and sheet 1 → sheet 0 processed, sheet 1 row into errors")
        void ut19_duplicateEmailAcrossSheets() throws Exception {
            // TC: UT-19
            MultipartFile file = buildExcelFile(wb -> {
                XSSFSheet s0 = wb.createSheet("Learners");
                s0.createRow(0);
                writeLearnerRow(s0, 1, "Nguyen Van A", "15/08/2000", "0111111111", "cross@test.com");
                XSSFSheet s1 = wb.createSheet("Teachers");
                s1.createRow(0);
                writeTeacherRow(s1, 1, "Nguyen Van A", "15/08/2000", "0111111111", "cross@test.com");
            });
            given(userRepository.findByEmailIn(any(Set.class))).willReturn(List.of());

            ImportUsersResult result = userService.importUsersFromExcel(file, adminId);

            assertThat(result.getCreated()).hasSize(1);
            assertThat(result.getErrors()).hasSize(1);
            assertThat(result.getErrors().get(0).getEmailErrorMessage()).contains("trùng lặp");
        }

        @Test
        @DisplayName("UT-20: Duplicate email with different case → treated as duplicate (lowercase normalization)")
        void ut20_duplicateEmailDifferentCase() throws Exception {
            // TC: UT-20
            MultipartFile file = buildExcelFile(wb -> {
                XSSFSheet s0 = wb.createSheet("Learners");
                s0.createRow(0);
                writeLearnerRow(s0, 1, "Nguyen Van A", "15/08/2000", "0111111111", "user@test.com");
                writeLearnerRow(s0, 2, "Nguyen Van A", "15/08/2000", "0111111111", "User@Test.com");
            });
            given(userRepository.findByEmailIn(any(Set.class))).willReturn(List.of());

            ImportUsersResult result = userService.importUsersFromExcel(file, adminId);

            assertThat(result.getCreated()).hasSize(1);
            assertThat(result.getErrors()).hasSize(1);
            assertThat(result.getErrors().get(0).getEmailErrorMessage()).contains("trùng lặp");
        }

        // ─────────────────────────────────────────────────────────────────────
        // 5. Email Normalization
        // ─────────────────────────────────────────────────────────────────────

        @Test
        @DisplayName("UT-21: Email with leading/trailing spaces → trimmed + lowercased before processing")
        void ut21_emailWithWhitespace() throws Exception {
            // TC: UT-21
            // Note: getCellValueAsString already trims cell values, so the normalization
            // under test is the trim().toLowerCase() in the service logic itself.
            MultipartFile file = buildExcelFile(wb -> {
                XSSFSheet s0 = wb.createSheet("Learners");
                s0.createRow(0);
                writeLearnerRow(s0, 1, "Nguyen Van A", "15/08/2000", "0111111111", "USER@TEST.COM");
            });
            given(userRepository.findByEmailIn(any(Set.class))).willReturn(List.of());

            ImportUsersResult result = userService.importUsersFromExcel(file, adminId);

            assertThat(result.getCreated()).hasSize(1);
            assertThat(result.getCreated().get(0).getEmail()).isEqualTo("user@test.com");
        }

        @Test
        @DisplayName("UT-22: Uppercase email in file matches lowercase user in DB → identified as existing, not created")
        void ut22_uppercaseEmailMatchesDbLowercase() throws Exception {
            // TC: UT-22
            MultipartFile file = buildExcelFile(wb -> {
                XSSFSheet s0 = wb.createSheet("Learners");
                s0.createRow(0);
                writeLearnerRow(s0, 1, "Nguyen Van Updated", "15/08/2000", "0000000000", "EXISTING@TEST.COM");
            });
            // DB has the lowercase version
            User existing = User.builder().email("existing@test.com")
                    .firstName("Old").lastName("Name").phoneNumber("0999999999").build();
            given(userRepository.findByEmailIn(any(Set.class))).willReturn(List.of(existing));

            ImportUsersResult result = userService.importUsersFromExcel(file, adminId);

            assertThat(result.getCreated()).isEmpty();
            assertThat(result.getUpdated()).hasSize(1);
        }

        // ─────────────────────────────────────────────────────────────────────
        // 6. Name Parsing Logic
        // ─────────────────────────────────────────────────────────────────────

        @Test
        @DisplayName("UT-23: fullName = single word → stored as-is in created DTO (service splits on save)")
        void ut23_singleWordFullName() throws Exception {
            // TC: UT-23
            MultipartFile file = buildExcelFile(wb -> {
                XSSFSheet s0 = wb.createSheet("Learners");
                s0.createRow(0);
                writeLearnerRow(s0, 1, "Madonna", "15/08/2000", "0111111111", "madonna@test.com");
            });
            given(userRepository.findByEmailIn(any(Set.class))).willReturn(List.of());

            ImportUsersResult result = userService.importUsersFromExcel(file, adminId);

            assertThat(result.getCreated()).hasSize(1);
            assertThat(result.getCreated().get(0).getFullName()).isEqualTo("Madonna");
        }

        @Test
        @DisplayName("UT-24: fullName = multiple words (e.g. 'Nguyen Van A') → stored in created DTO correctly")
        void ut24_multiWordFullName() throws Exception {
            // TC: UT-24
            MultipartFile file = buildExcelFile(wb -> {
                XSSFSheet s0 = wb.createSheet("Learners");
                s0.createRow(0);
                writeLearnerRow(s0, 1, "Nguyen Van A", "15/08/2000", "0111111111", "nguyenvana@test.com");
            });
            given(userRepository.findByEmailIn(any(Set.class))).willReturn(List.of());

            ImportUsersResult result = userService.importUsersFromExcel(file, adminId);

            assertThat(result.getCreated()).hasSize(1);
            assertThat(result.getCreated().get(0).getFullName()).isEqualTo("Nguyen Van A");
        }

        // ─────────────────────────────────────────────────────────────────────
        // 7. birthDay Handling
        // ─────────────────────────────────────────────────────────────────────

        @Test
        @DisplayName("UT-25: Blank birthDay → birthDate=null in created DTO, no error produced")
        void ut25_blankBirthday() throws Exception {
            // TC: UT-25
            MultipartFile file = buildExcelFile(wb -> {
                XSSFSheet s0 = wb.createSheet("Learners");
                s0.createRow(0);
                writeLearnerRow(s0, 1, "Nguyen Van A", "", "0111111111", "nobirth@test.com");
            });
            given(userRepository.findByEmailIn(any(Set.class))).willReturn(List.of());

            ImportUsersResult result = userService.importUsersFromExcel(file, adminId);

            assertThat(result.getErrors()).isEmpty();
            assertThat(result.getCreated()).hasSize(1);
            assertThat(result.getCreated().get(0).getBirthDay()).isNull();
        }

        @Test
        @DisplayName("UT-26: Valid birthDay '15/08/2000' → parsed as LocalDate.of(2000, 8, 15)")
        void ut26_validBirthday() throws Exception {
            // TC: UT-26
            MultipartFile file = buildExcelFile(wb -> {
                XSSFSheet s0 = wb.createSheet("Learners");
                s0.createRow(0);
                writeLearnerRow(s0, 1, "Nguyen Van A", "15/08/2000", "0111111111", "birth@test.com");
            });
            given(userRepository.findByEmailIn(any(Set.class))).willReturn(List.of());

            ImportUsersResult result = userService.importUsersFromExcel(file, adminId);

            assertThat(result.getCreated()).hasSize(1);
            assertThat(result.getCreated().get(0).getBirthDay()).isEqualTo(LocalDate.of(2000, 8, 15));
        }

        // ─────────────────────────────────────────────────────────────────────
        // 8. Role Assignment
        // ─────────────────────────────────────────────────────────────────────

        @Test
        @DisplayName("UT-27: Row from sheet index 0 → role == UserRole.LEARNER")
        void ut27_sheet0RoleLearner() throws Exception {
            // TC: UT-27
            MultipartFile file = buildExcelFile(wb -> {
                XSSFSheet s0 = wb.createSheet("Learners");
                s0.createRow(0);
                writeLearnerRow(s0, 1, "Learner User", "01/01/2000", "0111111111", "learner@test.com");
            });
            given(userRepository.findByEmailIn(any(Set.class))).willReturn(List.of());

            ImportUsersResult result = userService.importUsersFromExcel(file, adminId);

            assertThat(result.getCreated()).hasSize(1);
            assertThat(result.getCreated().get(0).getRole()).isEqualTo(UserRole.LEARNER);
        }

        @Test
        @DisplayName("UT-28: Row from sheet index 1 → role == UserRole.TEACHER")
        void ut28_sheet1RoleTeacher() throws Exception {
            // TC: UT-28
            MultipartFile file = buildExcelFile(wb -> {
                wb.createSheet("Learners"); // empty sheet 0
                XSSFSheet s1 = wb.createSheet("Teachers");
                s1.createRow(0);
                writeTeacherRow(s1, 1, "Teacher User", "01/01/1985", "0222222222", "teacher@test.com");
            });
            given(userRepository.findByEmailIn(any(Set.class))).willReturn(List.of());

            ImportUsersResult result = userService.importUsersFromExcel(file, adminId);

            assertThat(result.getCreated()).hasSize(1);
            assertThat(result.getCreated().get(0).getRole()).isEqualTo(UserRole.TEACHER);
        }

        // ─────────────────────────────────────────────────────────────────────
        // 9. Return Object Integrity
        // ─────────────────────────────────────────────────────────────────────

        @Test
        @DisplayName("UT-29: 3 created + 2 updated + 1 error → counts and totalRows match exactly")
        void ut29_returnObjectIntegrity() throws Exception {
            // TC: UT-29
            MultipartFile file = buildExcelFile(wb -> {
                XSSFSheet s0 = wb.createSheet("Learners");
                s0.createRow(0);
                writeLearnerRow(s0, 1, "New One",   "01/01/2000", "0111111111", "new1@test.com");
                writeLearnerRow(s0, 2, "New Two",   "02/02/2001", "0222222222", "new2@test.com");
                writeLearnerRow(s0, 3, "New Three", "03/03/2002", "0333333333", "new3@test.com");
                writeLearnerRow(s0, 4, "Updated One", "04/04/1990", "0444444444", "ex1@test.com");
                writeLearnerRow(s0, 5, "Updated Two", "05/05/1991", "0555555555", "ex2@test.com");
                // One bad row
                writeLearnerRow(s0, 6, "Bad User",   "15/08/2000", "badphone",  "bad@email");
            });
            User ex1 = User.builder().email("ex1@test.com").firstName("Old1").lastName("Name").phoneNumber("0900000001").build();
            User ex2 = User.builder().email("ex2@test.com").firstName("Old2").lastName("Name").phoneNumber("0900000002").build();
            given(userRepository.findByEmailIn(any(Set.class))).willReturn(List.of(ex1, ex2));

            ImportUsersResult result = userService.importUsersFromExcel(file, adminId);

            assertThat(result.getCreatedCount()).isEqualTo(3);
            assertThat(result.getUpdatedCount()).isEqualTo(2);
            assertThat(result.getErrorCount()).isEqualTo(1);
            assertThat(result.getTotalRows()).isEqualTo(6);
        }

        @Test
        @DisplayName("UT-30: File with only headers (no data rows) → all counts == 0, no exception")
        void ut30_emptyFileAllCountsZero() throws Exception {
            // TC: UT-30
            MultipartFile file = buildExcelFile(wb -> {
                XSSFSheet s0 = wb.createSheet("Learners");
                s0.createRow(0); // header only
            });
            given(userRepository.findByEmailIn(any(Set.class))).willReturn(List.of());

            assertThatNoException().isThrownBy(() -> {
                ImportUsersResult result = userService.importUsersFromExcel(file, adminId);
                assertThat(result.getTotalRows()).isEqualTo(0);
                assertThat(result.getCreatedCount()).isEqualTo(0);
                assertThat(result.getUpdatedCount()).isEqualTo(0);
                assertThat(result.getErrorCount()).isEqualTo(0);
            });
        }

        // ─────────────────────────────────────────────────────────────────────
        // 10. Exception Handling
        // ─────────────────────────────────────────────────────────────────────

        @Test
        @DisplayName("UT-31: file.getInputStream() throws IOException → wrapped as BusinessException(IMPORT_FAILED)")
        void ut31_inputStreamThrowsIOException() throws Exception {
            // TC: UT-31
            MultipartFile file = mock(MultipartFile.class);
            given(file.getInputStream()).willThrow(new IOException("disk error"));

            assertThatThrownBy(() -> userService.importUsersFromExcel(file, adminId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("IMPORT_FAILED"));
        }

        @Test
        @DisplayName("UT-32: Corrupt file bytes → WorkbookFactory fails → wrapped as BusinessException(IMPORT_FAILED)")
        void ut32_corruptFileContent() {
            // TC: UT-32
            MockMultipartFile corruptFile = new MockMultipartFile(
                    "file", "corrupt.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "this is not excel content".getBytes());

            assertThatThrownBy(() -> userService.importUsersFromExcel(corruptFile, adminId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo("IMPORT_FAILED"));
        }

        @Test
        @DisplayName("UT-33: BusinessException thrown internally → re-thrown as-is, not wrapped again")
        void ut33_businessExceptionReThrownAsIs() throws Exception {
            // TC: UT-33
            // Trigger BusinessException from findByEmailIn (called after row parsing)
            MultipartFile file = buildExcelFile(wb -> {
                XSSFSheet s0 = wb.createSheet("Learners");
                s0.createRow(0);
                writeLearnerRow(s0, 1, "Nguyen Van A", "15/08/2000", "0111111111", "user@test.com");
            });
            BusinessException original = new BusinessException("SOME_CODE", "Original business error");
            given(userRepository.findByEmailIn(any(Set.class))).willThrow(original);

            // Must be the exact same exception object (not wrapped)
            assertThatThrownBy(() -> userService.importUsersFromExcel(file, adminId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Original business error")
                    .isSameAs(original);
        }
    }
}

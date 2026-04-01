package com.fu.user.service;

import com.fu.common.exception.BusinessException;
import com.fu.infrastructure.email.EmailService;
import com.fu.user.dto.*;
import com.fu.user.entity.*;
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
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
                .authProvider(AuthProvider.LOCAL)
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
                .authProvider(AuthProvider.LOCAL)
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
                .authProvider(AuthProvider.LOCAL)
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
                .authProvider(AuthProvider.LOCAL)
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
                .authProvider(AuthProvider.LOCAL)
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
}

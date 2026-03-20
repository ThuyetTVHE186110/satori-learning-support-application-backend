package com.fu.user.service.impl;

import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.infrastructure.email.EmailService;
import com.fu.user.dto.*;
import com.fu.user.entity.AuthProvider;
import com.fu.user.entity.User;
import com.fu.user.entity.UserRole;
import com.fu.user.entity.UserStatus;
import com.fu.user.exception.DeletedUserExistsException;
import com.fu.user.mapper.UserMapper;
import com.fu.user.repository.UserRepository;
import com.fu.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementation of UserService.
 * Handles all User Management use cases (UC-01 to UC-05) for Admin.
 * Also handles user self-service profile operations.
 *
 * <h2>Admin Operations (Web Portal)</h2>
 * <ul>
 *   <li>UC-01: View User List - search, filter by role/status, pagination</li>
 *   <li>UC-02: Create User - with welcome email containing login credentials</li>
 *   <li>UC-03: View User Information - detailed user profile</li>
 *   <li>UC-04: Update User Information - modify name, phone, role, status</li>
 *   <li>UC-05: Delete User - soft delete (status → INACTIVE)</li>
 * </ul>
 *
 * <h2>User Self-Service</h2>
 * <ul>
 *   <li>View own profile</li>
 *   <li>Update own profile (except email)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;

    private static final String UPPERCASE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWERCASE_CHARS = "abcdefghijklmnopqrstuvwxyz";
    private static final String DIGIT_CHARS = "0123456789";
    private static final String SPECIAL_CHARS = "!@#$%^&*()_+-=[]{}|;:,.<>?";
    private static final int DEFAULT_PASSWORD_LENGTH = 12;

    // ==================== Admin Operations (UC-01 to UC-05) ====================

    /**
     * UC-02: Create User (by Admin)
     * Creates a new user account (Learner/Teacher/Manager/Admin).
     * <p>
     * Business Rules:
     * - BR-01: Email must be unique
     * - If email exists with DELETED status: throw DeletedUserExistsException to allow user choice
     * - Otherwise if email exists with non-DELETED status: error
     * - Admin can create any role (LEARNER/TEACHER/MANAGER/ADMIN)
     * - Generate random password with strict requirements, hash with BCrypt
     * - Send welcome email with login credentials
     */
    @Override
    @Transactional
    public UserResponse createUser(CreateUserRequest request, UUID createdByUserId, UserRole creatorRole) {
        log.info("Admin {} creating user with email: {}", createdByUserId, request.getEmail());

        // Validate role based on creator's role
        validateRoleCreation(request.getRole(), creatorRole);

        // Check if email exists
        Optional<User> existingUserOpt = userRepository.findByEmail(request.getEmail());

        if (existingUserOpt.isPresent()) {
            User existingUser = existingUserOpt.get();
            // If DELETED status → always throw DeletedUserExistsException (409 Conflict)
            // so frontend can prompt the user to restore instead of silently re-creating.
            if (existingUser.getDeletedAt() != null) {
                log.warn("Email already exists with DELETED status: {}", request.getEmail());
                DeletedUserInfo deletedInfo = DeletedUserInfo.builder()
                        .userId(existingUser.getId())
                        .email(existingUser.getEmail())
                        .firstName(existingUser.getFirstName())
                        .lastName(existingUser.getLastName())
                        .displayName(existingUser.getDisplayName())
                        .role(existingUser.getRole())
                        .status(existingUser.getStatus())
                        .deletedAt(existingUser.getDeletedAt())
                        .deletedBy(existingUser.getDeletedBy())
                        .message("Email này đã tồn tại trong hệ thống nhưng đã bị xóa. Bạn có muốn khôi phục tài khoản này không?")
                        .build();
                throw new DeletedUserExistsException(deletedInfo);
            } else {
                // Email exists with non-DELETED status → error
                log.warn("Email already exists with status {}: {}", existingUser.getStatus(), request.getEmail());
                throw new BusinessException("EMAIL_EXISTS", "Email này đã được sử dụng. Vui lòng nhập email khác");
            }
        }

        // Create new user
        String rawPassword = generateRandomPassword();
        User user = createNewUser(request, rawPassword, createdByUserId);
        User savedUser = userRepository.save(user);
        log.info("User created successfully with id: {}", savedUser.getId());

        // Send welcome email with login credentials
        emailService.sendWelcomeEmailWithCredentials(
                savedUser.getEmail(),
                savedUser.getFullName(),
                savedUser.getEmail(),
                rawPassword
        );

        return userMapper.toResponse(savedUser);
    }

    /**
     * UC-01/UC-03: Get user by ID
     */
    @Override
    public UserResponse getUserById(UUID id) {
        log.debug("Fetching user by id: {}", id);
        User user = findUserById(id);
        return userMapper.toResponse(user);
    }

    /**
     * UC-03: Get detailed user information with enrollment history
     */
    @Override
    public UserDetailResponse getUserDetailById(UserRole userRole, UUID id) {

        log.debug("Fetching detailed user information for id: {}", id);
        User user = findUserByIdIncludingDeleted(id);

        UserResponse userResponse = userMapper.toResponse(user);

        UserDetailResponse detailResponse = UserDetailResponse.builder()
                .userInfo(userResponse)
                .build();

        // TODO: Populate enrollment history when course/class management modules are available
        // For now, return empty lists based on role
        if (user.getRole() == UserRole.LEARNER) {
            detailResponse.setCourseEnrollments(java.util.Collections.emptyList());
            detailResponse.setClassEnrollments(java.util.Collections.emptyList());
            detailResponse.setTotalCourses(0);
            detailResponse.setTotalClasses(0);
            detailResponse.setCompletedCourses(0);
            detailResponse.setAverageGrade(0.0);
        } else if (user.getRole() == UserRole.TEACHER) {
            detailResponse.setTaughtClasses(java.util.Collections.emptyList());
            detailResponse.setTotalClasses(0);
        }

        return detailResponse;
    }

    /**
     * Get user by email
     */
    @Override
    public UserResponse getUserByEmail(String email) {
        log.debug("Fetching user by email: {}", email);
        User user = userRepository.findActiveByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User", "email", email));
        return userMapper.toResponse(user);
    }

    /**
     * UC-01: View User List
     * Get paginated list of users.
     * - Admin sees all users (including deleted)
     */
    @Override
    public Page<UserResponse> getAllUsers(Pageable pageable, UserRole currentUserRole) {
        log.debug("Fetching all users with pageable: {} for admin role", pageable);

        if (currentUserRole != UserRole.ADMIN) {
            throw new BusinessException("FORBIDDEN", "Bạn không có quyền thực hiện thao tác này");
        }

        // Admin sees all users including deleted
        return userRepository.findAll(pageable)
                .map(userMapper::toResponse);
    }

    /**
     * UC-01: Search users with filters
     * Supports search by keyword (name/email), filter by role and status.
     * Admin can see all users.
     */
    @Override
    public Page<UserResponse> searchUsers(UserFilterRequest filter, Pageable pageable, UserRole currentUserRole) {
        log.debug("Searching users with filter: {}, for admin", filter);

        if (currentUserRole != UserRole.ADMIN) {
            throw new BusinessException("FORBIDDEN", "Bạn không có quyền thực hiện thao tác này");
        }

        // Admin sees all users
        return userRepository.searchUsers(
                filter.getKeyword(),
                filter.getRole(),
                filter.getStatus(),
                pageable
        ).map(userMapper::toResponse);
    }

    /**
     * UC-04: Update User Information
     * Admin can update all users.
     * Deleted users cannot be updated.
     */
    @Override
    @Transactional
    public UserResponse updateUser(UUID id, UpdateUserRequest request, UUID updatedByUserId, UserRole updaterRole) {
        log.info("Admin {} updating user with id: {}", updatedByUserId, id);
        // Find user based on updater role
        User user = findUserByIdIncludingDeleted(id);
        // Validate user is not deleted
        if (user.getDeletedAt() != null) {
            throw new BusinessException("USER_DELETED", "Không thể chỉnh sửa tài khoản đã bị xóa");
        }
        // Validate permission to update this user
        validateUpdatePermission(user, updaterRole);
        //Update email if provided
        if (request.getEmail() != null && !request.getEmail().equals(user.getEmail())) {
            // Check if new email already exists
            Optional<User> existingUserOpt = userRepository.findByEmail(request.getEmail());
            //Check if existingUser is deleted
            if (existingUserOpt.isPresent()) {
                User existingUser = existingUserOpt.get();
                // If DELETED status → throw error
                if (existingUser.getDeletedAt() != null) {
                    log.warn("Email already exists with DELETED status: {}", request.getEmail());
                    throw new BusinessException("EMAIL_EXISTS_DELETED", "Email này đã tồn tại trong hệ thống nhưng đã bị xóa. Vui lòng chọn email khác hoặc khôi phục tài khoản đã xóa");
                }
            }
            // If email exists with non-DELETED status → error
            if (existingUserOpt.isPresent() && !existingUserOpt.get().getId().equals(id)) {
                throw new BusinessException("EMAIL_EXISTS", "Email này đã được sử dụng. Vui lòng nhập email khác");
            }
            user.setEmail(request.getEmail());
        }
        // Update fields if provided
        if (request.getFirstName() != null) {
            user.setFirstName(request.getFirstName());
        }
        if (request.getLastName() != null) {
            user.setLastName(request.getLastName());
        }
        if (request.getDisplayName() != null) {
            user.setDisplayName(request.getDisplayName());
        }
        if (request.getPhoneNumber() != null) {
            user.setPhoneNumber(request.getPhoneNumber());
        }
        if (request.getDateOfBirth() != null) {
            user.setDateOfBirth(request.getDateOfBirth());
        }
        if (request.getAvatarUrl() != null) {
            user.setAvatarUrl(request.getAvatarUrl());
        }
        if (request.getStatus() != null && request.getStatus() == UserStatus.DELETED) {
            throw new BusinessException("INVALID_STATUS", "Không thể cập nhật trạng thái thành DELETED. Vui lòng sử dụng chức năng xóa để xóa tài khoản");
        }
        // Update role if provided (with permission check)
        if (request.getRole() != null) {
            validateRoleUpdate(request.getRole(), updaterRole);
            user.setRole(request.getRole());
        }

        // Update status if provided
        if (request.getStatus() != null) {
            user.setStatus(request.getStatus());
        }

        user.setUpdatedBy(updatedByUserId);
        User updatedUser = userRepository.save(user);
        log.info("User updated successfully with id: {}", id);

        return userMapper.toResponse(updatedUser);
    }

    /**
     * UC-05: Delete User (Soft Delete)
     * Sets deletedAt and deletedBy, changes status to DELETED.
     * Admin can delete all users.
     * Historical data (learning progress, scores) is preserved for reporting.
     */
    @Override
    @Transactional
    public void deleteUser(UUID id, UUID deletedByUserId, UserRole deleterRole) {
        log.info("Admin {} soft deleting user with id: {}", deletedByUserId, id);

        User user = findUserById(id);

        // Validate permission to delete
        validateDeletePermission(user, deleterRole);

        // Soft delete: set status to DELETED and mark deletion
        user.setStatus(UserStatus.DELETED);
        user.setDeletedAt(LocalDateTime.now());
        user.setDeletedBy(deletedByUserId);
        user.setUpdatedBy(deletedByUserId);

        userRepository.save(user);
        log.info("User soft deleted (set to DELETED) with id: {}", id);
    }

    // ==================== Admin-only Operations ====================

    /**
     * Admin: Restore deleted user
     */
    @Override
    @Transactional
    public UserResponse restoreUser(RestoreUserRequest request, UUID restoredByUserId, UserRole restorerRole) {
        log.info("Admin {} restoring user with id: {}", restoredByUserId, request.getUserId());

        // Find the deleted user
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", request.getUserId()));

        if (user.getDeletedAt() == null) {
            throw new BusinessException("USER_NOT_DELETED", "Tài khoản này chưa bị xóa");
        }

        // Validate permission to restore (Admin only)
        validateRestorePermission(request.getRole(), restorerRole);

        // Generate new password
        String rawPassword = generateRandomPassword();

        // Update user information with new data from request
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setRole(request.getRole());
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(LocalDateTime.now());
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);

        // Clear soft delete markers
        user.setDeletedAt(null);
        user.setDeletedBy(null);

        // Update audit
        user.setUpdatedBy(restoredByUserId);

        User restoredUser = userRepository.save(user);
        log.info("User restored successfully with id: {}", restoredUser.getId());

        // Send welcome email with new credentials
        emailService.sendWelcomeEmailWithCredentials(
                restoredUser.getEmail(),
                restoredUser.getFullName(),
                restoredUser.getEmail(),
                rawPassword
        );

        return userMapper.toResponse(restoredUser);
    }

    /**
     * UC-06: Get Deleted Users
     * Admin-only: Get paginated list of deleted users.
     */
    @Override
    public Page<UserResponse> getDeletedUsers(Pageable pageable) {
        log.debug("Fetching deleted users with pageable: {}", pageable);
        Page<User> page = userRepository.findAllDeleted(pageable);
        return page.map(userMapper::toResponse);
    }

    // ==================== User Self-Service Operations ====================

    /**
     * Get current user's own profile.
     */
    @Override
    public UserResponse getMyProfile(UUID userId) {
        log.debug("Fetching profile for user: {}", userId);
        User user = findUserById(userId);
        return userMapper.toResponse(user);
    }

    /**
     * Update current user's own profile.
     * Users can update personal info (name, phone, avatar, etc.) except email.
     */
    @Override
    @Transactional
    public UserResponse updateMyProfile(UUID userId, UpdateProfileRequest request) {
        log.info("User {} updating their own profile", userId);

        User user = findUserById(userId);
        // Check email is existing
        if (request.getEmail() != null && !request.getEmail().equals(user.getEmail())) {
            // Check if new email already exists
            Optional<User> existingUserOpt = userRepository.findByEmail(request.getEmail());
            //Check if existingUser is deleted
            if (existingUserOpt.isPresent()) {
                User existingUser = existingUserOpt.get();
                // If DELETED status → throw error
                if (existingUser.getDeletedAt() != null) {
                    log.warn("Email already exists with DELETED status: {}", request.getEmail());
                    throw new BusinessException("EMAIL_EXISTS_DELETED", "Email này đã tồn tại trong hệ thống nhưng đã bị xóa. Vui lòng chọn email khác hoặc khôi phục tài khoản đã xóa");
                }
            }
            // If email exists with non-DELETED status → error
            if (existingUserOpt.isPresent() && !existingUserOpt.get().getId().equals(userId)) {
                throw new BusinessException("EMAIL_EXISTS", "Email này đã được sử dụng. Vui lòng nhập email khác");
            }
            user.setEmail(request.getEmail());
        }
        // Update allowed fields only
        if (request.getFirstName() != null) {
            user.setFirstName(request.getFirstName());
        }
        if (request.getLastName() != null) {
            user.setLastName(request.getLastName());
        }
        if (request.getDisplayName() != null) {
            user.setDisplayName(request.getDisplayName());
        }
        if (request.getPhoneNumber() != null) {
            user.setPhoneNumber(request.getPhoneNumber());
        }
        if (request.getDateOfBirth() != null) {
            user.setDateOfBirth(request.getDateOfBirth());
        }
        if (request.getGender() != null) {
            user.setGender(request.getGender());
        }
        if (request.getAvatarUrl() != null) {
            user.setAvatarUrl(request.getAvatarUrl());
        }

        User updatedUser = userRepository.save(user);
        log.info("Profile updated successfully for user: {}", userId);

        return userMapper.toResponse(updatedUser);
    }

    // ==================== Private Helper Methods ====================

    /**
     * Find user by ID (not soft-deleted).
     */
    private User findUserById(UUID id) {
        return userRepository.findById(id)
                .filter(user -> user.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
    }

    /**
     * Find user by ID including soft-deleted.
     */
    private User findUserByIdIncludingDeleted(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
    }

    /**
     * Validate if the creator has permission to create a user with the given role.
     * - Admin can create: LEARNER, TEACHER, MANAGER, ADMIN
     */
    private void validateRoleCreation(UserRole targetRole, UserRole creatorRole) {
        if (creatorRole != UserRole.ADMIN) {
            throw new BusinessException("FORBIDDEN", "Bạn không có quyền tạo tài khoản");
        }
    }

    /**
     * Validate if the updater has permission to update the target user.
     * - ADMIN can update all users
     */
    private void validateUpdatePermission(User targetUser, UserRole updaterRole) {
        if (updaterRole != UserRole.ADMIN) {
            throw new BusinessException("FORBIDDEN", "Bạn không có quyền chỉnh sửa tài khoản này");
        }
    }

    /**
     * Validate if the role can be updated to the target role by the updater.
     */
    private void validateRoleUpdate(UserRole targetRole, UserRole updaterRole) {
        if (updaterRole != UserRole.ADMIN) {
            throw new BusinessException("FORBIDDEN", "Bạn không có quyền thay đổi vai trò");
        }
    }

    /**
     * Validate if the deleter has permission to delete the target user.
     * - ADMIN can delete all users
     */
    private void validateDeletePermission(User targetUser, UserRole deleterRole) {
        if (deleterRole != UserRole.ADMIN) {
            throw new BusinessException("FORBIDDEN", "Bạn không có quyền xóa tài khoản này");
        }
        if (targetUser.getRole() == UserRole.ADMIN) {
            throw new BusinessException("FORBIDDEN", "Không thể xóa tài khoản ADMIN");
        }
    }

    /**
     * Validate permission to restore a user.
     * - ADMIN: can restore any role
     */
    private void validateRestorePermission(UserRole targetRole, UserRole actorRole) {
        if (actorRole != UserRole.ADMIN) {
            throw new BusinessException("FORBIDDEN", "Bạn không có quyền khôi phục tài khoản này");
        }
    }

    /**
     * Create new user entity from request.
     */
    private User createNewUser(CreateUserRequest request, String rawPassword, UUID createdByUserId) {
        User user = userMapper.toEntity(request);

        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRole(request.getRole());
        user.setStatus(UserStatus.ACTIVE);
        user.setAuthProvider(AuthProvider.LOCAL);
        user.setEmailVerifiedAt(LocalDateTime.now()); // Verified by manager/admin
        user.setCreatedBy(createdByUserId);

        if (request.getPhoneNumber() != null) {
            user.setPhoneNumber(request.getPhoneNumber());
        }
        if (request.getDateOfBirth() != null) {
            user.setDateOfBirth(request.getDateOfBirth());
        }

        return user;
    }

    /**
     * Generate random password with required constraints:
     * - At least 1 uppercase letter
     * - At least 1 lowercase letter
     * - At least 1 digit
     * - At least 1 special character
     * - No emoji characters
     * Total length: DEFAULT_PASSWORD_LENGTH (12 characters)
     */
    private String generateRandomPassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder(DEFAULT_PASSWORD_LENGTH);

        // Ensure at least one character from each required category
        password.append(UPPERCASE_CHARS.charAt(random.nextInt(UPPERCASE_CHARS.length())));
        password.append(LOWERCASE_CHARS.charAt(random.nextInt(LOWERCASE_CHARS.length())));
        password.append(DIGIT_CHARS.charAt(random.nextInt(DIGIT_CHARS.length())));
        password.append(SPECIAL_CHARS.charAt(random.nextInt(SPECIAL_CHARS.length())));

        // Fill remaining positions with random characters from all categories
        String allChars = UPPERCASE_CHARS + LOWERCASE_CHARS + DIGIT_CHARS + SPECIAL_CHARS;
        for (int i = 4; i < DEFAULT_PASSWORD_LENGTH; i++) {
            password.append(allChars.charAt(random.nextInt(allChars.length())));
        }

        // Shuffle the password to avoid predictable pattern
        return shuffleString(password.toString(), random);
    }

    /**
     * Shuffle a string randomly.
     */
    private String shuffleString(String input, SecureRandom random) {
        char[] characters = input.toCharArray();
        for (int i = characters.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char temp = characters[i];
            characters[i] = characters[j];
            characters[j] = temp;
        }
        return new String(characters);
    }

}


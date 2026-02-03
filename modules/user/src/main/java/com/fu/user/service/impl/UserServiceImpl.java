package com.fu.user.service.impl;

import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.infrastructure.email.EmailService;
import com.fu.user.dto.*;
import com.fu.user.entity.AuthProvider;
import com.fu.user.entity.User;
import com.fu.user.entity.UserRole;
import com.fu.user.entity.UserStatus;
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
import java.util.Optional;
import java.util.UUID;

/**
 * Implementation of UserService.
 * Handles all User Management use cases (UC-01 to UC-05) for Manager.
 * Also handles user self-service profile operations.
 * 
 * <h2>Manager Operations (Web Portal)</h2>
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

    private static final String PASSWORD_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%";
    private static final int DEFAULT_PASSWORD_LENGTH = 12;

    // ==================== Manager Operations (UC-01 to UC-05) ====================

    /**
     * UC-02: Create User (by Manager)
     * Creates a new user account (Learner/Teacher).
     * 
     * Business Rules:
     * - BR-01: Email must be unique
     * - If email exists with DELETED status: restore account and update info
     * - Otherwise: create new account
     * - Generate random password, hash with BCrypt
     * - Send welcome email with login credentials
     */
    @Override
    @Transactional
    public UserResponse createUser(CreateUserRequest request, UUID createdByUserId) {
        log.info("Manager {} creating user with email: {}", createdByUserId, request.getEmail());

        // Validate role - Manager cannot create Manager/Admin
        if (request.getRole() == UserRole.MANAGER || request.getRole() == UserRole.ADMIN) {
            throw new BusinessException("INVALID_ROLE", "Không thể tạo tài khoản với vai trò Manager/Admin");
        }

        // Check if email exists
        Optional<User> existingUserOpt = userRepository.findByEmail(request.getEmail());
        
        User user;
        String rawPassword = generateRandomPassword();
        
        if (existingUserOpt.isPresent()) {
            User existingUser = existingUserOpt.get();
            
            // If DELETED status → restore account
            if (existingUser.getStatus() == UserStatus.DELETED) {
                log.info("Restoring deleted account for email: {}", request.getEmail());
                user = restoreDeletedAccount(existingUser, rawPassword, createdByUserId);
            } else {
                // Email exists with non-DELETED status → error
                log.warn("Email already exists with status {}: {}", existingUser.getStatus(), request.getEmail());
                throw new BusinessException("EMAIL_EXISTS", "Email này đã được sử dụng. Vui lòng nhập email khác");
            }
        } else {
            // Create new user
            user = createNewUser(request, rawPassword, createdByUserId);
        }

        User savedUser = userRepository.save(user);
        log.info("User created/restored successfully with id: {}", savedUser.getId());

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
     * Get paginated list of users (excluding DELETED).
     */
    @Override
    public Page<UserResponse> getAllUsers(Pageable pageable) {
        log.debug("Fetching all users with pageable: {}", pageable);
        return userRepository.findAllActive(pageable)
                .map(userMapper::toResponse);
    }

    /**
     * UC-01: Search users with filters
     * Supports search by keyword (name/email), filter by role and status.
     */
    @Override
    public Page<UserResponse> searchUsers(UserFilterRequest filter, Pageable pageable) {
        log.debug("Searching users with filter: {}", filter);
        
        return userRepository.searchUsers(
                filter.getKeyword(),
                filter.getRole(),
                filter.getStatus(),
                pageable
        ).map(userMapper::toResponse);
    }

    /**
     * UC-04: Update User Information
     * Manager updates user details (name, phone, role, status).
     */
    @Override
    @Transactional
    public UserResponse updateUser(UUID id, UpdateUserRequest request, UUID updatedByUserId) {
        log.info("Manager {} updating user with id: {}", updatedByUserId, id);

        User user = findUserById(id);

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
        
        // Update role if provided (only LEARNER or TEACHER allowed)
        if (request.getRole() != null) {
            if (request.getRole() == UserRole.MANAGER || request.getRole() == UserRole.ADMIN) {
                throw new BusinessException("INVALID_ROLE", "Không thể thay đổi vai trò thành Manager/Admin");
            }
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
     * Sets status to INACTIVE to prevent login immediately.
     * Historical data (learning progress, scores) is preserved for reporting.
     */
    @Override
    @Transactional
    public void deleteUser(UUID id, UUID deletedByUserId) {
        log.info("Soft deleting user with id: {}, by user: {}", id, deletedByUserId);
        
        User user = findUserById(id);
        
        // Cannot delete a Manager/Admin
        if (user.isManager()) {
            throw new BusinessException("CANNOT_DELETE_MANAGER", "Không thể xóa tài khoản Manager/Admin");
        }
        
        // Soft delete: set status to INACTIVE
        user.setStatus(UserStatus.INACTIVE);
        user.setUpdatedBy(deletedByUserId);
        
        userRepository.save(user);
        log.info("User soft deleted (set to INACTIVE) with id: {}", id);
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
                .filter(user -> user.getStatus() != UserStatus.DELETED)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
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
        user.setEmailVerifiedAt(LocalDateTime.now()); // Verified by manager
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
     * Restore deleted account: keep existing info, reset status and password.
     */
    private User restoreDeletedAccount(User user, String rawPassword, UUID restoredByUserId) {
        // Reset security fields
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
        
        return user;
    }

    /**
     * Generate random password with letters, numbers and special chars.
     */
    private String generateRandomPassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder(DEFAULT_PASSWORD_LENGTH);
        
        for (int i = 0; i < DEFAULT_PASSWORD_LENGTH; i++) {
            password.append(PASSWORD_CHARS.charAt(random.nextInt(PASSWORD_CHARS.length())));
        }
        
        return password.toString();
    }
}

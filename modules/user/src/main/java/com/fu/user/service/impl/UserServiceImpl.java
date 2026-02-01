package com.fu.user.service.impl;

import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.user.dto.CreateUserRequest;
import com.fu.user.dto.UpdateUserRequest;
import com.fu.user.dto.UserFilterRequest;
import com.fu.user.dto.UserResponse;
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
import java.util.UUID;

/**
 * Implementation of UserService.
 * Handles all User Management use cases (UC-01 to UC-05).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    // TODO: Inject EmailService when available
    // private final EmailService emailService;

    private static final String ALLOWED_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%";
    private static final int DEFAULT_PASSWORD_LENGTH = 12;

    /**
     * UC-02: Create User
     * Creates a new user account with generated password.
     */
    @Override
    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        log.info("Creating user with email: {}", request.getEmail());

        // BR-01: Email must be unique
        if (userRepository.existsActiveByEmail(request.getEmail())) {
            log.warn("Email already exists: {}", request.getEmail());
            throw new BusinessException("EMAIL_EXISTS", "Email này đã được sử dụng. Vui lòng nhập email khác");
        }

        // Validate role - Manager cannot create another Manager/Admin
        if (request.getRole() == UserRole.MANAGER || request.getRole() == UserRole.ADMIN) {
            throw new BusinessException("INVALID_ROLE", "Không thể tạo tài khoản với vai trò Manager/Admin");
        }

        // Map request to entity
        User user = userMapper.toEntity(request);
        
        // Generate random password and hash with BCrypt
        String rawPassword = generateRandomPassword();
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        
        // Set role and status
        user.setRole(request.getRole());
        user.setStatus(UserStatus.ACTIVE); // Active immediately as created by Manager
        
        // Set optional fields
        if (request.getPhoneNumber() != null) {
            user.setPhoneNumber(request.getPhoneNumber());
        }
        if (request.getDateOfBirth() != null) {
            user.setDateOfBirth(request.getDateOfBirth());
        }

        User savedUser = userRepository.save(user);
        log.info("User created successfully with id: {}", savedUser.getId());

        // TODO: Send welcome email with login credentials
        // emailService.sendWelcomeEmail(savedUser.getEmail(), savedUser.getFullName(), rawPassword);
        log.info("Welcome email should be sent to: {} with generated password", savedUser.getEmail());

        return userMapper.toResponse(savedUser);
    }

    /**
     * UC-01: Get user by ID
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
     * UC-01: Get all active users with pagination
     * Default sort: createdAt DESC
     */
    @Override
    public Page<UserResponse> getAllUsers(Pageable pageable) {
        log.debug("Fetching all users with pageable: {}", pageable);
        return userRepository.findAllActive(pageable)
                .map(userMapper::toResponse);
    }

    /**
     * UC-01: Search users with filters
     * Supports keyword search and role/status filters
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
     */
    @Override
    @Transactional
    public UserResponse updateUser(UUID id, UpdateUserRequest request) {
        log.info("Updating user with id: {}", id);

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

        User updatedUser = userRepository.save(user);
        log.info("User updated successfully with id: {}", id);

        return userMapper.toResponse(updatedUser);
    }

    /**
     * UC-05: Delete User (Soft Delete)
     * Sets status to INACTIVE and records deletion info.
     */
    @Override
    @Transactional
    public void deleteUser(UUID id, UUID deletedByUserId) {
        log.info("Soft deleting user with id: {}, by user: {}", id, deletedByUserId);
        
        User user = findUserById(id);
        
        // Cannot delete a Manager
        if (user.isManager()) {
            throw new BusinessException("CANNOT_DELETE_MANAGER", "Không thể xóa tài khoản Manager");
        }
        
        // Perform soft delete
        user.setStatus(UserStatus.INACTIVE);
        user.softDelete(deletedByUserId);
        
        userRepository.save(user);
        log.info("User soft deleted with id: {}", id);
    }

    /**
     * Find user by ID or throw exception
     */
    private User findUserById(UUID id) {
        return userRepository.findById(id)
                .filter(user -> user.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
    }

    /**
     * Generate a random password for new users.
     * Password will be sent via email.
     */
    private String generateRandomPassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder(DEFAULT_PASSWORD_LENGTH);
        
        for (int i = 0; i < DEFAULT_PASSWORD_LENGTH; i++) {
            password.append(ALLOWED_CHARACTERS.charAt(random.nextInt(ALLOWED_CHARACTERS.length())));
        }
        
        return password.toString();
    }
}

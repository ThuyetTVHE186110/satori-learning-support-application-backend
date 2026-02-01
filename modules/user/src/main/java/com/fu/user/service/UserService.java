package com.fu.user.service;

import com.fu.user.dto.CreateUserRequest;
import com.fu.user.dto.UpdateUserRequest;
import com.fu.user.dto.UserFilterRequest;
import com.fu.user.dto.UserResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Service interface for User Management operations.
 * Implements UC-01 to UC-05 from business requirements.
 */
public interface UserService {

    /**
     * UC-02: Create User
     * Manager creates a new user account (Learner/Teacher).
     * - Validates email uniqueness (BR-01)
     * - Generates random password and hashes with BCrypt
     * - Sends welcome email with login credentials
     * 
     * @param request CreateUserRequest with user details
     * @return Created user response
     */
    UserResponse createUser(CreateUserRequest request);

    /**
     * UC-01: View User List (single user)
     * Get user by ID.
     * 
     * @param id User UUID
     * @return User response
     */
    UserResponse getUserById(UUID id);

    /**
     * Get user by email.
     * 
     * @param email User email
     * @return User response
     */
    UserResponse getUserByEmail(String email);

    /**
     * UC-01: View User List
     * Get paginated list of active users.
     * Default sort: createdAt DESC
     * 
     * @param pageable Pagination parameters
     * @return Page of user responses
     */
    Page<UserResponse> getAllUsers(Pageable pageable);

    /**
     * UC-01: View User List with search and filters
     * Supports:
     * - Search by keyword (name, email)
     * - Filter by role (LEARNER/TEACHER)
     * - Filter by status (ACTIVE/INACTIVE)
     * 
     * @param filter Filter criteria
     * @param pageable Pagination parameters
     * @return Page of user responses
     */
    Page<UserResponse> searchUsers(UserFilterRequest filter, Pageable pageable);

    /**
     * UC-04: Update User Information
     * Manager updates user details (phone, role, status, etc.)
     * 
     * @param id User UUID
     * @param request Update request with changed fields
     * @return Updated user response
     */
    UserResponse updateUser(UUID id, UpdateUserRequest request);

    /**
     * UC-05: Delete User (Soft Delete)
     * Soft deactivation - sets status to INACTIVE and records deletedAt/deletedBy.
     * User cannot login after deletion.
     * 
     * @param id User UUID to delete
     * @param deletedByUserId UUID of the manager performing deletion
     */
    void deleteUser(UUID id, UUID deletedByUserId);
}

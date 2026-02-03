package com.fu.user.service;

import com.fu.user.dto.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Service interface for User Management operations.
 * Implements UC-01 to UC-05 from business requirements.
 * 
 * <h2>Use Cases (Manager Operations on Web Portal)</h2>
 * <ul>
 *   <li>UC-01: View User List - search, filter, pagination</li>
 *   <li>UC-02: Create User - manual creation with welcome email</li>
 *   <li>UC-03: View User Information - view user details</li>
 *   <li>UC-04: Update User Information - modify user data</li>
 *   <li>UC-05: Delete User - soft delete (set status to INACTIVE)</li>
 * </ul>
 * 
 * <h2>User Self-Service Operations</h2>
 * <ul>
 *   <li>View own profile</li>
 *   <li>Update own profile (except email)</li>
 * </ul>
 */
public interface UserService {

    // ==================== Manager Operations (UC-01 to UC-05) ====================

    /**
     * UC-02: Create User
     * Manager creates a new user account (Learner/Teacher).
     * <ul>
     *   <li>Validates email uniqueness (BR-01)</li>
     *   <li>If email exists with DELETED status: restore account with new info</li>
     *   <li>Generates random password and hashes with BCrypt</li>
     *   <li>Sets status to ACTIVE (created by manager)</li>
     *   <li>Sends welcome email with login credentials and password setup guide</li>
     * </ul>
     * 
     * @param request CreateUserRequest with user details
     * @param createdByUserId UUID of the manager creating the user
     * @return Created user response
     */
    UserResponse createUser(CreateUserRequest request, UUID createdByUserId);

    /**
     * UC-01/UC-03: Get user by ID.
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
     * Get paginated list of users.
     * Default sort: createdAt DESC
     * 
     * @param pageable Pagination parameters
     * @return Page of user responses
     */
    Page<UserResponse> getAllUsers(Pageable pageable);

    /**
     * UC-01: View User List with search and filters
     * Supports:
     * <ul>
     *   <li>Search by keyword (name, email)</li>
     *   <li>Filter by role (LEARNER/TEACHER)</li>
     *   <li>Filter by status (ACTIVE/INACTIVE/etc.)</li>
     * </ul>
     * 
     * @param filter Filter criteria
     * @param pageable Pagination parameters
     * @return Page of user responses
     */
    Page<UserResponse> searchUsers(UserFilterRequest filter, Pageable pageable);

    /**
     * UC-04: Update User Information
     * Manager updates user details (name, phone, role, status, etc.)
     * 
     * @param id User UUID
     * @param request Update request with changed fields
     * @param updatedByUserId UUID of the manager performing update
     * @return Updated user response
     */
    UserResponse updateUser(UUID id, UpdateUserRequest request, UUID updatedByUserId);

    /**
     * UC-05: Delete User (Soft Delete)
     * Sets status to INACTIVE to prevent login immediately.
     * Historical data (learning progress, scores) is preserved for reporting.
     * 
     * @param id User UUID to delete
     * @param deletedByUserId UUID of the manager performing deletion
     */
    void deleteUser(UUID id, UUID deletedByUserId);

    // ==================== User Self-Service Operations ====================

    /**
     * Get current user's own profile.
     * 
     * @param userId User UUID
     * @return User profile response
     */
    UserResponse getMyProfile(UUID userId);

    /**
     * Update current user's own profile.
     * Users can update personal info (name, phone, avatar, etc.) except email.
     * 
     * @param userId User UUID
     * @param request Update profile request
     * @return Updated user response
     */
    UserResponse updateMyProfile(UUID userId, UpdateProfileRequest request);
}

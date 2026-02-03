package com.fu.user.repository;

import com.fu.user.entity.User;
import com.fu.user.entity.UserRole;
import com.fu.user.entity.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    /**
     * Find user by email (for login, uniqueness check)
     */
    Optional<User> findByEmail(String email);

    /**
     * Find active user by email (not soft-deleted)
     */
    @Query("SELECT u FROM User u WHERE u.email = :email AND u.deletedAt IS NULL")
    Optional<User> findActiveByEmail(@Param("email") String email);

    /**
     * Check if email exists (for uniqueness validation BR-01)
     */
    boolean existsByEmail(String email);

    /**
     * Check if email exists for active users only
     */
    @Query("SELECT CASE WHEN COUNT(u) > 0 THEN true ELSE false END FROM User u WHERE u.email = :email AND u.deletedAt IS NULL")
    boolean existsActiveByEmail(@Param("email") String email);

    /**
     * Find all active users (not soft-deleted) with pagination
     * Default for UC-01: View User List
     */
    @Query("SELECT u FROM User u WHERE u.deletedAt IS NULL ORDER BY u.createdAt DESC")
    Page<User> findAllActive(Pageable pageable);

    /**
     * Search users by keyword (name or email) with optional filters
     * Supports UC-01 alternatives: search, filter by role/status
     */
    @Query("SELECT u FROM User u WHERE u.deletedAt IS NULL " +
           "AND (:keyword IS NULL OR :keyword = '' OR " +
           "     LOWER(u.firstName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(u.lastName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(u.displayName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "AND (:role IS NULL OR u.role = :role) " +
           "AND (:status IS NULL OR u.status = :status)")
    Page<User> searchUsers(
            @Param("keyword") String keyword,
            @Param("role") UserRole role,
            @Param("status") UserStatus status,
            Pageable pageable
    );

    /**
     * Count users by role (for statistics)
     */
    @Query("SELECT COUNT(u) FROM User u WHERE u.role = :role AND u.deletedAt IS NULL")
    long countByRole(@Param("role") UserRole role);

    /**
     * Count users by status (for statistics)
     */
    @Query("SELECT COUNT(u) FROM User u WHERE u.status = :status AND u.deletedAt IS NULL")
    long countByStatus(@Param("status") UserStatus status);

    /**
     * Find user by Google ID (for OAuth login)
     */
    Optional<User> findByGoogleId(String googleId);

    /**
     * Find user by activation token (for email verification)
     */
    @Query("SELECT u FROM User u WHERE u.activationToken = :token AND u.deletedAt IS NULL")
    Optional<User> findByActivationToken(@Param("token") String token);
}

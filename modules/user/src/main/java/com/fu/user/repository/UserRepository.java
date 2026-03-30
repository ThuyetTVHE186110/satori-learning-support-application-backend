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

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    /**
     * Find user by email (for login, uniqueness check)
     */
    Optional<User> findByEmail(String email);

    /**
     * Batch fetch users by a set of emails.
     * Used by Excel import to replace N+1 findByEmail calls with a single IN query.
     */
    List<User> findByEmailIn(Collection<String> emails);

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
    @Query("SELECT u FROM User u WHERE u.deletedAt IS NULL AND u.role IN :allowedRoles ORDER BY u.createdAt DESC ")
    Page<User> findAllActive(Pageable pageable, @Param("allowedRoles") java.util.List<UserRole> allowedRoles);

    /**
     * Search users by keyword (name or email) with optional filters
     * Supports UC-01 alternatives: search, filter by role/status
     */
    @Query("SELECT u FROM User u WHERE u.deletedAt IS NULL " +
            "AND (:#{#keyword == null || #keyword.length() == 0} = true OR " +
            "     LOWER(u.firstName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "     LOWER(u.lastName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "     LOWER(u.displayName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "     LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
            "AND (:#{#role == null} = true OR u.role = :role) " +
            "AND (:#{#status == null} = true OR u.status = :status)")
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

    /**
     * Find user by password reset token (for reset password flow)
     */
    @Query("SELECT u FROM User u WHERE u.passwordResetToken = :token AND u.deletedAt IS NULL")
    Optional<User> findByPasswordResetToken(@Param("token") String token);

    /**
     * Search users by keyword with role filters (excluding certain roles for Manager)
     * Manager can only see LEARNER and TEACHER
     */
    @Query("SELECT u FROM User u WHERE u.deletedAt IS NULL " +
            "AND (:#{#keyword == null || #keyword.length() == 0} = true OR " +
            "     LOWER(u.firstName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "     LOWER(u.lastName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "     LOWER(u.displayName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "     LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
            "AND (:#{#role == null} = true OR u.role = :role) " +
            "AND (:#{#status == null} = true OR u.status = :status) " +
            "AND u.role IN :allowedRoles")
    Page<User> searchUsersByRoles(
            @Param("keyword") String keyword,
            @Param("role") UserRole role,
            @Param("status") UserStatus status,
            @Param("allowedRoles") java.util.List<UserRole> allowedRoles,
            Pageable pageable
    );

    /**
     * Find all deleted users (for Admin only)
     */
    @Query("SELECT u FROM User u WHERE u.deletedAt IS NOT NULL ORDER BY u.deletedAt DESC")
    Page<User> findAllDeleted(Pageable pageable);

    /**
     * Find deleted user by email
     */
    @Query("SELECT u FROM User u WHERE u.email = :email AND u.deletedAt IS NOT NULL")
    Optional<User> findDeletedByEmail(@Param("email") String email);

    /**
     * Find eligible learners for a specific class (Active or Inactive, and not currently an active member)
     */
    @Query("SELECT u FROM User u WHERE u.deletedAt IS NULL " +
            "AND u.role = 'LEARNER' " +
            "AND u.status IN ('INACTIVE') " +
            "AND (:#{#keyword == null || #keyword.length() == 0} = true OR " +
            "     LOWER(u.firstName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "     LOWER(u.lastName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "     LOWER(u.displayName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "     LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
            "AND (:#{#excludedUserIds == null || #excludedUserIds.isEmpty()} = true OR u.id NOT IN :excludedUserIds)")
    Page<User> findEligibleLearnersForClass(
            @Param("keyword") String keyword,
            @Param("excludedUserIds") java.util.List<UUID> excludedUserIds,
            Pageable pageable
    );

    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE User u SET u.status = :status, u.updatedAt = CURRENT_TIMESTAMP WHERE u.id IN :ids AND u.role = 'LEARNER'")
    void updateStatusByUserIdIn(@Param("ids") java.util.Collection<java.util.UUID> ids, @Param("status") UserStatus status);

    /**
     * Fetch the single active class enrollment for a learner.
     * Business rule: 1 learner → at most 1 active class → 1 course at any time.
     *
         * Returns at most one row: (class_id, class_name, course_id,
         *                           course_name, start_date, end_date, status).
     * Cross-schema JOIN: user_management (class_members, classes) → learning_content (courses).
     */
    @Query(value = """
            SELECT
                c.id                                                    AS class_id,
                c.name                                                  AS class_name,
                c.course_id                                             AS course_id,
                co.name                                                 AS course_name,
                c.start_date                                            AS start_date,
                c.end_date                                              AS end_date,
                                CASE WHEN c.is_active THEN 'ACTIVE' ELSE 'INACTIVE' END AS status
            FROM user_management.class_members cm
            JOIN user_management.classes c   ON c.id  = cm.class_id
            JOIN learning_content.courses co ON co.id = c.course_id
            WHERE cm.user_id    = :userId
              AND cm.removed_at IS NULL
              AND c.deleted_at  IS NULL
              AND c.is_active   = true
              AND co.deleted_at IS NULL
            LIMIT 1
            """, nativeQuery = true)
    java.util.List<Object[]> findActiveEnrollment(@Param("userId") UUID userId);
    @Query("SELECT u FROM User u WHERE u.email = :email AND u.deletedAt IS NULL")
    Optional<User> findByEmailAndNotDeleted(@Param("email") String email);
}

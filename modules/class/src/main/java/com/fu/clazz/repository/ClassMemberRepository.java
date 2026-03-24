package com.fu.clazz.repository;

import com.fu.clazz.entity.ClassMember;
import com.fu.clazz.entity.Clazz;
import com.fu.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for ClassMember entity
 */
@Repository
public interface ClassMemberRepository extends JpaRepository<ClassMember, UUID> {

    /**
     * Find active members of a class (not removed)
     */
    @Query("SELECT m FROM ClassMember m WHERE m.classId = :classId AND m.removedAt IS NULL")
    Page<ClassMember> findActiveByClassId(@Param("classId") UUID classId, Pageable pageable);

    /**
     * Find active members of a class with keyword search (name or email)
     */
    @Query("SELECT m FROM ClassMember m, User u WHERE m.userId = u.id " +
           "AND m.classId = :classId AND m.removedAt IS NULL " +
           "AND (:#{#keyword == null || #keyword.isBlank()} = true OR " +
           "     LOWER(u.firstName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(u.lastName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(u.displayName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "     LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<ClassMember> findActiveByClassIdAndKeyword(
            @Param("classId") UUID classId,
            @Param("keyword") String keyword,
            Pageable pageable);

    /**
     * Count active members in a class
     */
    @Query("SELECT COUNT(m) FROM ClassMember m WHERE m.classId = :classId AND m.removedAt IS NULL")
    long countActiveByClassId(@Param("classId") UUID classId);

    /**
     * Find membership record (active or removed)
     */
    Optional<ClassMember> findByClassIdAndUserId(UUID classId, UUID userId);

    /**
     * Check if user is an active member of a class
     */
    @Query("SELECT CASE WHEN COUNT(m) > 0 THEN true ELSE false END FROM ClassMember m " +
           "WHERE m.classId = :classId AND m.userId = :userId AND m.removedAt IS NULL")
    boolean existsActiveByClassIdAndUserId(@Param("classId") UUID classId, @Param("userId") UUID userId);

    /**
     * Find all classes a user is actively a member of
     */
    @Query("SELECT m FROM ClassMember m WHERE m.userId = :userId AND m.removedAt IS NULL")
    Page<ClassMember> findActiveByUserId(@Param("userId") UUID userId, Pageable pageable);

    @Query("SELECT m.classId FROM ClassMember m WHERE m.userId = :userId AND m.removedAt IS NULL")
    java.util.List<UUID> findActiveClassIdsByUserId(@Param("userId") UUID userId);
 @Query("SELECT m.userId FROM ClassMember m JOIN Clazz c ON m.classId = c.id " +
           "WHERE m.removedAt IS NULL AND c.isActive = true AND c.deletedAt IS NULL")
    java.util.List<UUID> findUserIdsInActiveClasses();
    /**
     * Count active class memberships for a user in classes that are currently Active.
     */
    @Query("SELECT COUNT(m) FROM ClassMember m JOIN Clazz c ON m.classId = c.id " +
           "WHERE m.userId = :userId AND m.removedAt IS NULL AND c.isActive = true AND c.deletedAt IS NULL")
    long countActiveByUserId(@Param("userId") UUID userId);
}

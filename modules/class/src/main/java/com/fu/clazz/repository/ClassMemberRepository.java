package com.fu.clazz.repository;

import com.fu.clazz.entity.ClassMember;
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


    @Query("SELECT m.classId FROM ClassMember m WHERE m.userId = :userId AND m.removedAt IS NULL")
    java.util.List<UUID> findActiveClassIdsByUserId(@Param("userId") UUID userId);

    @Query("SELECT m.userId FROM ClassMember m JOIN Clazz c ON m.classId = c.id " +
            "WHERE m.removedAt IS NULL AND (c.status = 'ACTIVE' OR c.status = 'NOT_STARTED') AND c.deletedAt IS NULL")
    java.util.List<UUID> findUserIdsInActiveClasses();


    @Query("SELECT m.userId FROM ClassMember m WHERE m.classId = :classId AND m.removedAt IS NULL")
    java.util.List<UUID> findActiveUserIdsByClassId(@Param("classId") UUID classId);

    /**
     * Find all user IDs in a class (active AND removed members)
     */
    @Query("SELECT m.userId FROM ClassMember m WHERE m.classId = :classId")
    java.util.List<UUID> findAllUserIdsByClassId(@Param("classId") UUID classId);

    /**
     * Find all ClassMember records in a class (active AND removed)
     * Used to check removedAt status for each member
     */
    @Query("SELECT m FROM ClassMember m WHERE m.classId = :classId")
    java.util.List<ClassMember> findAllByClassId(@Param("classId") UUID classId);

    /**
     * Find all user IDs who were members of a closed class (regardless of removedAt status)
     * Used for filtering eligible learners to show students from a specific closed class
     */
    @Query("SELECT m.userId FROM ClassMember m WHERE m.classId = :closedClassId")
    java.util.List<UUID> findUserIdsByClosedClass(@Param("closedClassId") UUID closedClassId);

    /**
     * Check if a learner is in ANY active class (not removed)
     * Used to validate that learner is not already enrolled in another class
     */
    @Query("SELECT CASE WHEN COUNT(m) > 0 THEN true ELSE false END FROM ClassMember m WHERE m.userId = :userId AND m.removedAt IS NULL")
    boolean existsByUserIdAndRemovedAtNull(@Param("userId") UUID userId);
}


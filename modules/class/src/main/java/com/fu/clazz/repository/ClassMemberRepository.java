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

    /**
     * Count active class memberships for a user.
     */
    @Query("SELECT COUNT(m) FROM ClassMember m WHERE m.userId = :userId AND m.removedAt IS NULL")
    long countActiveByUserId(@Param("userId") UUID userId);
}


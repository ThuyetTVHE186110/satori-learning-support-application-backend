package com.fu.clazz.repository;

import com.fu.clazz.entity.Clazz;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Clazz entity
 */
@Repository
public interface ClassRepository extends JpaRepository<Clazz, UUID>, JpaSpecificationExecutor<Clazz> {

        /**
         * Find class by ID (non-deleted)
         */
        @Query("SELECT c FROM Clazz c WHERE c.id = :id AND c.deletedAt IS NULL")
        Optional<Clazz> findByIdAndNotDeleted(@Param("id") UUID id);

        /**
         * Find all non-deleted classes with pagination
         */
        @Query("SELECT c FROM Clazz c WHERE c.deletedAt IS NULL")
        Page<Clazz> findAllNotDeleted(Pageable pageable);

        /**
         * Find classes by active status
         */
        Page<Clazz> findByIsActiveAndDeletedAtIsNull(Boolean isActive, Pageable pageable);

        /**
         * Find classes by JLPT level
         */
    Page<Clazz> findByDeletedAtIsNull(Pageable pageable);

        /**
         * Find classes by teacher ID
         */
        @Query("SELECT c FROM Clazz c JOIN ClassTeacherHistory cth ON c.id = cth.classId " +
                "WHERE cth.teacherId = :teacherId AND cth.releasedAt IS NULL AND c.deletedAt IS NULL")
        Page<Clazz> findByTeacherIdAndDeletedAtIsNull(@Param("teacherId") UUID teacherId, Pageable pageable);

        /**
         * Find active classes by teacher ID
         */
        @Query("SELECT c FROM Clazz c JOIN ClassTeacherHistory cth ON c.id = cth.classId " +
                "WHERE cth.teacherId = :teacherId AND cth.releasedAt IS NULL AND c.isActive = true AND c.deletedAt IS NULL")
        Page<Clazz> findByTeacherIdAndIsActiveTrueAndDeletedAtIsNull(@Param("teacherId") UUID teacherId, Pageable pageable);


        /**
         * Search classes by name (case-insensitive)
         */
        @Query("SELECT c FROM Clazz c WHERE LOWER(c.name) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
                        "AND c.deletedAt IS NULL")
        Page<Clazz> searchByName(@Param("keyword") String keyword, Pageable pageable);

        /**
         * Search classes by teacher ID and name (case-insensitive)
         */
        @Query("SELECT c FROM Clazz c JOIN ClassTeacherHistory cth ON c.id = cth.classId " +
                        "WHERE cth.teacherId = :teacherId AND cth.releasedAt IS NULL " +
                        "AND LOWER(c.name) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
                        "AND c.isActive = true AND c.deletedAt IS NULL")
        Page<Clazz> searchByTeacherAndName(@Param("teacherId") UUID teacherId,
                        @Param("keyword") String keyword,
                        Pageable pageable);


        /**
         * Check if class name already exists (case-insensitive, non-deleted)
         */
        @Query("SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END FROM Clazz c " +
                        "WHERE LOWER(c.name) = LOWER(:name) AND c.deletedAt IS NULL")
        boolean existsByNameIgnoreCase(@Param("name") String name);

        /**
         * Check if class name exists excluding specific class ID
         */
        @Query("SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END FROM Clazz c " +
                        "WHERE LOWER(c.name) = LOWER(:name) AND c.id <> :excludeId AND c.deletedAt IS NULL")
        boolean existsByNameIgnoreCaseAndIdNot(@Param("name") String name, @Param("excludeId") UUID excludeId);

        /**
         * Find classes by active status (including deleted)
         * 
         * @param isActive
         * @param pageable
         * @return Page of classes matching active status, including deleted classes
         */
        Page<Clazz> findByIsActive(Boolean isActive, Pageable pageable);

        /**
         * Find inactive classes that should be activated (startDate <= today and not yet reached endDate)
         */
        @Query("SELECT c FROM Clazz c WHERE c.isActive = false AND c.deletedAt IS NULL " +
                "AND (c.startDate <= :today AND (c.endDate IS NULL OR c.endDate > :today))")
        List<Clazz> findInactiveClassesToActivate(@Param("today") java.time.LocalDate today);
}

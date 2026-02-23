package com.fu.clazz.repository;

import com.fu.clazz.entity.Clazz;
import com.fu.clazz.entity.Clazz.JlptLevel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Clazz entity
 */
@Repository
public interface ClazzRepository extends JpaRepository<Clazz, UUID> {

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
    Page<Clazz> findByJlptLevelAndDeletedAtIsNull(JlptLevel jlptLevel, Pageable pageable);

    /**
     * Find classes by teacher ID
     */
    Page<Clazz> findByTeacherIdAndDeletedAtIsNull(UUID teacherId, Pageable pageable);

    /**
     * Search classes by name (case-insensitive)
     */
    @Query("SELECT c FROM Clazz c WHERE LOWER(c.name) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "AND c.deletedAt IS NULL")
    Page<Clazz> searchByName(@Param("keyword") String keyword, Pageable pageable);

    /**
     * Check if class code already exists (non-deleted)
     */
    @Query("SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END FROM Clazz c " +
           "WHERE c.code = :code AND c.deletedAt IS NULL")
    boolean existsByCode(@Param("code") String code);

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
}


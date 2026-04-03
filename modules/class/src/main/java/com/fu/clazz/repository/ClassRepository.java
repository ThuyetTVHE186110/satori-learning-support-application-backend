package com.fu.clazz.repository;

import com.fu.clazz.entity.ClassStatus;
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

    @Query("SELECT c FROM Clazz c WHERE c.status = 'NOT_STARTED' AND c.deletedAt IS NULL " +
            "AND (c.startDate <= :today AND (c.endDate IS NULL OR c.endDate > :today))")
    List<Clazz> findInactiveClassesToActivate(@Param("today") java.time.LocalDate today);

    /**
     * Find classes by status (non-deleted)
     */
    List<Clazz> findByStatusAndDeletedAtIsNull(ClassStatus status);
}

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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Future;

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

    /**
     * Find learner's enrolled classes (joined via ClassMember)
     * Only returns classes where:
     * - ClassMember.removedAt IS NULL (active membership)
     * - Class.status IN provided statuses
     * - Class.deletedAt IS NULL (not soft-deleted)
     */
    @Query("SELECT c FROM Clazz c " +
            "JOIN ClassMember cm ON cm.classId = c.id " +
            "WHERE cm.userId = :learnerId " +
            "AND cm.removedAt IS NULL " +
            "AND c.status IN :statuses " +
            "AND c.deletedAt IS NULL " +
            "ORDER BY c.status ASC, c.startDate DESC")
    List<Clazz> findLearnerClasses(@Param("learnerId") UUID learnerId,
                                   @Param("statuses") List<ClassStatus> statuses);

    /**
     * Check if course has active classes (non-deleted)
     */
    boolean existsByCourseIdAndStatusInAndDeletedAtIsNull(UUID courseId, java.util.Collection<ClassStatus> statuses);

    Optional<Clazz> findByNameAndDeletedAtIsNull(String name);

    /**
     * Find class by name (case-insensitive, non-deleted)
     * Used for retrieving class details during import
     */
    @Query("SELECT c FROM Clazz c WHERE LOWER(c.name) = LOWER(:name) AND c.deletedAt IS NULL")
    Optional<Clazz> findByNameIgnoreCaseAndNotDeleted(@Param("name") String name);

    @Query("SELECT c FROM Clazz c WHERE LOWER(c.name) = LOWER(:name)")
    Optional<Clazz> findByNameIgnoreCase(String name);
}

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

import java.time.LocalDate;
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
     */
    @Query("SELECT c FROM Clazz c WHERE LOWER(c.name) = LOWER(:name) AND c.deletedAt IS NULL")
    Optional<Clazz> findByNameIgnoreCaseAndNotDeleted(@Param("name") String name);

    @Query("SELECT c FROM Clazz c WHERE LOWER(c.name) = LOWER(:name)")
    Optional<Clazz> findByNameIgnoreCase(String name);

    // =====================================================
    // DASHBOARD KPI QUERIES
    // =====================================================

    /**
     * Count all non-deleted classes
     */
    @Query("SELECT COUNT(c) FROM Clazz c WHERE c.deletedAt IS NULL")
    Long countAllClasses();

    /**
     * Count active classes (status = ACTIVE, non-deleted)
     */
    @Query("SELECT COUNT(c) FROM Clazz c WHERE c.status = 'ACTIVE' AND c.deletedAt IS NULL")
    Long countActiveClasses();

    /**
     * Count total enrolled students across all classes (removedAt IS NULL)
     */
    @Query("SELECT COUNT(m) FROM ClassMember m " +
            "JOIN Clazz c ON m.classId = c.id " +
            "WHERE m.removedAt IS NULL AND c.deletedAt IS NULL")
    Long countTotalStudents();

    /**
     * Count unique teachers currently assigned to classes (releasedAt IS NULL)
     */
    @Query("SELECT COUNT(DISTINCT cth.teacherId) FROM ClassTeacherHistory cth " +
            "WHERE cth.releasedAt IS NULL")
    Long countUniqueTeachers();

    // =====================================================
    // STATUS DISTRIBUTION QUERIES
    // =====================================================

    /**
     * Count classes with status ACTIVE (non-deleted)
     */
    @Query("SELECT COUNT(c) FROM Clazz c WHERE c.status = 'ACTIVE' AND c.deletedAt IS NULL")
    Long countByStatusActive();

    /**
     * Count classes with status CLOSED (non-deleted)
     */
    @Query("SELECT COUNT(c) FROM Clazz c WHERE c.status = 'CLOSED' AND c.deletedAt IS NULL")
    Long countByStatusClosed();

    /**
     * Count classes with status NOT_STARTED (non-deleted)
     */
    @Query("SELECT COUNT(c) FROM Clazz c WHERE c.status = 'NOT_STARTED' AND c.deletedAt IS NULL")
    Long countByStatusNotStarted();

    // =====================================================
    // MONTHLY ENROLLMENT TREND (last 6 months)
    // =====================================================

    /**
     * Returns monthly enrollment counts for the last 6 months.
     * Each row: [month (Timestamp truncated to month), count (Long)]
     */
    @Query(value = "SELECT time_series.month AT TIME ZONE 'Asia/Ho_Chi_Minh' AS month, " +
            "COALESCE(COUNT(cm.id), 0) AS enrollmentCount " +
            "FROM (SELECT generate_series(DATE_TRUNC('month', CURRENT_DATE) - INTERVAL '5 months', " +
            "DATE_TRUNC('month', CURRENT_DATE), '1 month'::interval) AS month) time_series " +
            "LEFT JOIN user_management.class_members cm " +
            "ON DATE_TRUNC('month', cm.joined_at AT TIME ZONE 'Asia/Ho_Chi_Minh') = time_series.month " +
            "AND cm.removed_at IS NULL " +
            "GROUP BY time_series.month " +
            "ORDER BY time_series.month ASC",
            nativeQuery = true)
    List<Object[]> getMonthlyEnrollmentTrend();

    // =====================================================
    // PRIORITY CLASS QUERIES
    // =====================================================

    /**
     * Find overdue classes: expectedEndDate < today AND status != CLOSED
     */
    @Query("SELECT c FROM Clazz c " +
            "WHERE c.expectedEndDate < :today " +
            "AND c.status != 'CLOSED' " +
            "AND c.deletedAt IS NULL")
    List<Clazz> findOverdueClasses(@Param("today") LocalDate today);

    /**
     * Find classes ending soon: endDate between today and endRange AND status != CLOSED
     */
    @Query("SELECT c FROM Clazz c " +
            "WHERE c.endDate IS NOT NULL " +
            "AND c.endDate <= :endRange " +
            "AND c.endDate > :today " +
            "AND c.status != 'CLOSED' " +
            "AND c.deletedAt IS NULL")
    List<Clazz> findClassesEndingSoon(@Param("today") LocalDate today,
                                      @Param("endRange") LocalDate endRange);

    /**
     * Find classes starting soon: startDate between today and startRange AND status NOT IN (ACTIVE, CLOSED)
     */
    @Query("SELECT c FROM Clazz c " +
            "WHERE c.startDate IS NOT NULL " +
            "AND c.startDate <= :startRange " +
            "AND c.startDate > :today " +
            "AND c.status != 'ACTIVE' " +
            "AND c.status != 'CLOSED' " +
            "AND c.deletedAt IS NULL")
    List<Clazz> findClassesStartingSoon(@Param("today") LocalDate today,
                                        @Param("startRange") LocalDate startRange);

    /**
     * Find classes with incomplete enrollment: enrolledCount < maxStudents AND status != CLOSED
     */
    @Query("SELECT c FROM Clazz c " +
            "WHERE c.status != 'CLOSED' " +
            "AND c.deletedAt IS NULL " +
            "AND (SELECT COUNT(m) FROM ClassMember m WHERE m.classId = c.id AND m.removedAt IS NULL) < c.maxStudents")
    List<Clazz> findIncompleteClasses();

    /**
     * Get enrolled count for a specific class (active members only)
     */
    @Query("SELECT COUNT(m) FROM ClassMember m WHERE m.classId = :classId AND m.removedAt IS NULL")
    Integer getEnrolledCount(@Param("classId") UUID classId);
}

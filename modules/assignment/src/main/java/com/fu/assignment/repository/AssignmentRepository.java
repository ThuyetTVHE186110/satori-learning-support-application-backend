package com.fu.assignment.repository;

import com.fu.assignment.entity.Assignment;
import com.fu.assignment.entity.AssignmentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AssignmentRepository extends JpaRepository<Assignment, UUID> {

    /**
     * Find active (non-deleted) assignment by ID.
     */
    @Query("SELECT a FROM Assignment a WHERE a.id = :id AND a.deletedAt IS NULL")
    Optional<Assignment> findByIdAndNotDeleted(@Param("id") UUID id);

    /**
     * Find all non-deleted assignments created by a specific user.
     */
    @Query("SELECT a FROM Assignment a WHERE a.createdBy = :createdBy AND a.deletedAt IS NULL")
    Page<Assignment> findByCreatedByAndNotDeleted(@Param("createdBy") UUID createdBy, Pageable pageable);

    /**
     * Find all non-deleted assignments linked to a specific lesson.
     */
    @Query("SELECT a FROM Assignment a WHERE a.lessonId = :lessonId AND a.deletedAt IS NULL")
    Page<Assignment> findByLessonIdAndNotDeleted(@Param("lessonId") UUID lessonId, Pageable pageable);

    /**
     * Find all non-deleted assignments by status.
     */
    @Query("SELECT a FROM Assignment a WHERE a.status = :status AND a.deletedAt IS NULL")
    Page<Assignment> findByStatusAndNotDeleted(@Param("status") AssignmentStatus status, Pageable pageable);

    /**
     * Search assignments by keyword (title or description) — manager view.
     */
    @Query("""
            SELECT a FROM Assignment a
            WHERE a.deletedAt IS NULL
              AND (:keyword IS NULL
                   OR LOWER(a.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(a.description) LIKE LOWER(CONCAT('%', :keyword, '%')))
            """)
    Page<Assignment> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    /**
     * Find assignments assigned to a specific class (via assignment_targets JOIN).
     */
    @Query("""
            SELECT DISTINCT a FROM Assignment a
            JOIN AssignmentTarget t ON t.assignmentId = a.id
            WHERE t.classId = :classId
              AND a.deletedAt IS NULL
            """)
    Page<Assignment> findByClassId(@Param("classId") UUID classId, Pageable pageable);

    /**
     * Find published assignments assigned to a specific class.
     */
    @Query("""
            SELECT DISTINCT a FROM Assignment a
            JOIN AssignmentTarget t ON t.assignmentId = a.id
            WHERE t.classId = :classId
              AND a.status = com.fu.assignment.entity.AssignmentStatus.PUBLISHED
              AND a.deletedAt IS NULL
            """)
    Page<Assignment> findPublishedByClassId(@Param("classId") UUID classId, Pageable pageable);

    /**
     * Find published assignments assigned to a specific user (direct assignment or via class).
     */
    @Query("""
            SELECT DISTINCT a FROM Assignment a
            JOIN AssignmentTarget t ON t.assignmentId = a.id
            WHERE (t.userId = :userId OR t.classId IN :classIds)
              AND a.status = com.fu.assignment.entity.AssignmentStatus.PUBLISHED
              AND a.deletedAt IS NULL
            """)
    Page<Assignment> findPublishedForLearner(
            @Param("userId") UUID userId,
            @Param("classIds") java.util.List<UUID> classIds,
            Pageable pageable);
}

package com.fu.assignment.repository;

import com.fu.assignment.entity.Assignment;
import com.fu.assignment.entity.AssignmentStatus;
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

@Repository
public interface AssignmentRepository extends JpaRepository<Assignment, UUID>, JpaSpecificationExecutor<Assignment> {

    /**
     * Find active (non-deleted) assignment by ID.
     */
    @Query("SELECT a FROM Assignment a WHERE a.id = :id AND a.deletedAt IS NULL")
    Optional<Assignment> findByIdAndNotDeleted(@Param("id") UUID id);

    List<Assignment> findAllByStatusAndDeletedAtIsNull(AssignmentStatus status);

    /**
     * Get counts by status for a specific class.
     */
    @Query(value = """
            SELECT a.status, COUNT(*) 
            FROM assignment.assignments a
            JOIN assignment.assignment_targets t ON t.assignment_id = a.id
            WHERE t.class_id = :classId AND a.deleted_at IS NULL
            GROUP BY a.status
            """, nativeQuery = true)
    List<Object[]> countByStatusForClass(@Param("classId") UUID classId);

    /**
     * Find learner assignments sorted by urgency:
     * 1. NOT_STARTED / IN_PROGRESS — closest due date first (ascending)
     * 2. OVERDUE — most recently expired first (descending)
     * 3. SUBMITTED / GRADED — most recent first (descending)
     *
     * Uses EXISTS instead of JOIN on assignment_targets to avoid duplicates
     * (which would require DISTINCT and conflict with CASE-based ORDER BY in PostgreSQL).
     */
    @Query(value = """
            SELECT a.*
            FROM assignment.assignments a
            LEFT JOIN LATERAL (
                SELECT s.status
                FROM assignment.assignment_submissions s
                WHERE s.assignment_id = a.id AND s.user_id = :learnerId
                ORDER BY s.attempt_number DESC
                LIMIT 1
            ) sub ON TRUE
            WHERE EXISTS (
                SELECT 1 FROM assignment.assignment_targets t
                WHERE t.assignment_id = a.id
                  AND (t.class_id IN (:classIds) OR t.user_id = :learnerId)
            )
              AND a.deleted_at IS NULL
              AND a.status IN ('PUBLISHED', 'CLOSED')
              AND (CAST(:title AS text) = '' OR LOWER(a.title) LIKE LOWER(CONCAT('%', CAST(:title AS text), '%')))
              AND (CAST(:type AS text) = '' OR CAST(a.assignment_type AS text) = CAST(:type AS text))
            ORDER BY
              CASE
                  -- 1. NOT_STARTED / IN_PROGRESS (Within deadline)
                  WHEN (sub.status IS NULL OR sub.status = 'IN_PROGRESS') AND a.due_date >= CURRENT_TIMESTAMP THEN 0
                  -- 2. OVERDUE (Explicit status OR past deadline and not submitted/graded)
                  WHEN sub.status = 'OVERDUE' OR ((sub.status IS NULL OR sub.status = 'IN_PROGRESS') AND a.due_date < CURRENT_TIMESTAMP) THEN 1
                  -- 3. SUBMITTED / GRADED
                  ELSE 2
              END ASC,
              CASE
                  -- Sorting within Group 0: Closest due date first (ASC)
                  WHEN (sub.status IS NULL OR sub.status = 'IN_PROGRESS') AND a.due_date >= CURRENT_TIMESTAMP THEN a.due_date
              END ASC,
              a.due_date DESC
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM assignment.assignments a
            WHERE EXISTS (
                SELECT 1 FROM assignment.assignment_targets t
                WHERE t.assignment_id = a.id
                  AND (t.class_id IN (:classIds) OR t.user_id = :learnerId)
            )
              AND a.deleted_at IS NULL
              AND a.status IN ('PUBLISHED', 'CLOSED')
              AND (CAST(:title AS text) = '' OR LOWER(a.title) LIKE LOWER(CONCAT('%', CAST(:title AS text), '%')))
              AND (CAST(:type AS text) = '' OR CAST(a.assignment_type AS text) = CAST(:type AS text))
            """,
            nativeQuery = true)
    Page<Assignment> findLearnerAssignmentsSorted(
            @Param("learnerId") UUID learnerId,
            @Param("classIds") List<UUID> classIds,
            @Param("title") String title,
            @Param("type") String type,
            Pageable pageable);
}

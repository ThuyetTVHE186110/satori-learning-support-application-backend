package com.fu.assignment.repository;

import com.fu.assignment.entity.AssignmentSubmission;
import com.fu.assignment.entity.SubmissionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AssignmentSubmissionRepository extends JpaRepository<AssignmentSubmission, UUID> {

    /**
     * Find latest submission of a user for a given assignment.
     */
    @Query("""
            SELECT s FROM AssignmentSubmission s
            WHERE s.assignmentId = :assignmentId AND s.userId = :userId
            ORDER BY s.attemptNumber DESC
            """)
    List<AssignmentSubmission> findByAssignmentIdAndUserIdOrderByAttemptDesc(
            @Param("assignmentId") UUID assignmentId,
            @Param("userId") UUID userId);

    Optional<AssignmentSubmission> findByAssignmentIdAndUserIdAndAttemptNumber(
            UUID assignmentId, UUID userId, Integer attemptNumber);

    /**
     * Count total attempts by a user for an assignment.
     */
    long countByAssignmentIdAndUserId(UUID assignmentId, UUID userId);

    /**
     * Find all submissions for an assignment (teacher view).
     */
    Page<AssignmentSubmission> findByAssignmentId(UUID assignmentId, Pageable pageable);

    /**
     * Find all submissions of a specific user (learner view).
     */
    Page<AssignmentSubmission> findByUserId(UUID userId, Pageable pageable);

    /**
     * Find submissions pending grading.
     */
    @Query("""
            SELECT s FROM AssignmentSubmission s
            WHERE s.assignmentId = :assignmentId
              AND s.status = com.fu.assignment.entity.SubmissionStatus.SUBMITTED
            """)
    Page<AssignmentSubmission> findPendingGrading(@Param("assignmentId") UUID assignmentId, Pageable pageable);

    /**
     * Count submissions by status for an assignment.
     * Uses native query with explicit cast to avoid PostgreSQL enum type mismatch.
     */
    @Query(value = "SELECT COUNT(*) FROM assignment.assignment_submissions " +
            "WHERE assignment_id = :assignmentId AND status = CAST(:status AS submission_status)",
            nativeQuery = true)
    long countByAssignmentIdAndStatus(@Param("assignmentId") UUID assignmentId,
                                      @Param("status") String status);

    /**
     * Check if a user already has an active (non-submitted) submission.
     */
    @Query("""
            SELECT COUNT(s) > 0 FROM AssignmentSubmission s
            WHERE s.assignmentId = :assignmentId
              AND s.userId = :userId
              AND s.status = com.fu.assignment.entity.SubmissionStatus.IN_PROGRESS
            """)
    boolean hasActiveSubmission(@Param("assignmentId") UUID assignmentId, @Param("userId") UUID userId);
}

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
     * Find all submissions for an assignment without pagination.
     * Used to fetch all submissions for manual filtering/pagination in service layer.
     */
    List<AssignmentSubmission> findByAssignmentId(UUID assignmentId);

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

    /**
     * Count ungraded submissions (SUBMITTED) for a list of assignment IDs.
     */
    @Query(value = "SELECT assignment_id, COUNT(*) FROM assignment.assignment_submissions " +
            "WHERE assignment_id IN :assignmentIds AND status = 'SUBMITTED' " +
            "GROUP BY assignment_id",
            nativeQuery = true)
    List<Object[]> countUngradedByAssignmentIds(@Param("assignmentIds") List<UUID> assignmentIds);

    /**
     * Get submission counts by status for all assignments in a class.
     */
    @Query(value = """
            SELECT s.status, COUNT(*) 
            FROM assignment.assignment_submissions s
            JOIN assignment.assignments a ON a.id = s.assignment_id
            JOIN assignment.assignment_targets t ON t.assignment_id = a.id
            WHERE t.class_id = :classId AND a.deleted_at IS NULL
            GROUP BY s.status
            """, nativeQuery = true)
    List<Object[]> countSubmissionsByStatusForClass(@Param("classId") UUID classId);

    /**
     * Get average score of all GRADED submissions for a class.
     * Returns null if no graded submissions exist.
     */
    @Query(value = """
            SELECT AVG(CAST(s.score AS NUMERIC))
            FROM assignment.assignment_submissions s
            JOIN assignment.assignments a ON a.id = s.assignment_id
            JOIN assignment.assignment_targets t ON t.assignment_id = a.id
            WHERE t.class_id = :classId AND a.deleted_at IS NULL AND s.status = 'GRADED'
            """, nativeQuery = true)
    Double getAverageScoreForGradedSubmissions(@Param("classId") UUID classId);

    /**
     * Find the latest submission status and ID for a list of assignments for a specific user.
     * Returns a list of [assignmentId, status, submissionId] arrays.
     */
    @Query(value = """
            SELECT DISTINCT ON (assignment_id) assignment_id, status, id
            FROM assignment.assignment_submissions
            WHERE assignment_id IN :assignmentIds AND user_id = :userId
            ORDER BY assignment_id, attempt_number DESC
            """, nativeQuery = true)
    List<Object[]> findLatestStatusByAssignmentIdsAndUserId(
            @Param("assignmentIds") List<UUID> assignmentIds,
            @Param("userId") UUID userId);

    @Query("""
            SELECT s FROM AssignmentSubmission s
            WHERE s.assignmentId = :assignmentId
              AND s.userId = :userId
              AND s.status = com.fu.assignment.entity.SubmissionStatus.IN_PROGRESS
            """)
    Optional<AssignmentSubmission> findActiveByAssignmentIdAndUserId(@Param("assignmentId") UUID assignmentId, @Param("userId") UUID userId);

    /**
     * Count distinct students who submitted (SUBMITTED or GRADED) for a specific assignment in a class.
     * Returns count of distinct user_ids that have submissions with SUBMITTED or GRADED status.
     * Only counts submissions from active class members (ClassMember.removedAt IS NULL).
     */
    @Query(value = """
            SELECT COUNT(DISTINCT s.user_id)
            FROM assignment.assignment_submissions s
            JOIN assignment.assignments a ON a.id = s.assignment_id
            JOIN assignment.assignment_targets t ON t.assignment_id = a.id
            JOIN user_management.class_members cm ON cm.class_id = t.class_id AND cm.user_id = s.user_id
            WHERE a.id = :assignmentId
              AND t.class_id = :classId
              AND s.status IN ('SUBMITTED', 'GRADED')
              AND a.deleted_at IS NULL
              AND cm.removed_at IS NULL
            """, nativeQuery = true)
    long countSubmissionsInClassForAssignment(
            @Param("assignmentId") UUID assignmentId,
            @Param("classId") UUID classId);

    /**
     * Get average score of GRADED submissions for a specific assignment in a class.
     * Returns null if no graded submissions exist.
     * Only calculates average for active class members (ClassMember.removedAt IS NULL).
     */
    @Query(value = """
            SELECT AVG(CAST(s.score AS NUMERIC))
            FROM assignment.assignment_submissions s
            JOIN assignment.assignments a ON a.id = s.assignment_id
            JOIN assignment.assignment_targets t ON t.assignment_id = a.id
            JOIN user_management.class_members cm ON cm.class_id = t.class_id AND cm.user_id = s.user_id
            WHERE a.id = :assignmentId
              AND t.class_id = :classId
              AND s.status = 'GRADED'
              AND a.deleted_at IS NULL
              AND cm.removed_at IS NULL
            """, nativeQuery = true)
    Double getAverageScoreForAssignmentInClass(
            @Param("assignmentId") UUID assignmentId,
            @Param("classId") UUID classId);

    /**
     * Find submissions by assignment ID and learner keyword search.
     * Searches by firstName, lastName, displayName, or email with case-insensitive LIKE matching.
     */
    @Query(value = """
            SELECT s.*
            FROM assignment.assignment_submissions s
            JOIN assignment.assignments a ON a.id = s.assignment_id
            JOIN user_management.users u ON u.id = s.user_id
            WHERE a.id = :assignmentId
              AND (LOWER(u.first_name) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(u.last_name) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(u.display_name) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')))
            ORDER BY s.attempt_number DESC, s.created_at DESC
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM assignment.assignment_submissions s
            JOIN assignment.assignments a ON a.id = s.assignment_id
            JOIN "user".users u ON u.id = s.user_id
            WHERE a.id = :assignmentId
              AND (LOWER(u.first_name) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(u.last_name) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(u.display_name) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')))
            """,
            nativeQuery = true)
    Page<AssignmentSubmission> findByAssignmentIdAndKeyword(
            @Param("assignmentId") UUID assignmentId,
            @Param("keyword") String keyword,
            Pageable pageable);
}

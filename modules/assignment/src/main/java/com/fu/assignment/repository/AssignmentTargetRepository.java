package com.fu.assignment.repository;

import com.fu.assignment.entity.AssignmentTarget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AssignmentTargetRepository extends JpaRepository<AssignmentTarget, UUID> {

    List<AssignmentTarget> findByAssignmentId(UUID assignmentId);

    List<AssignmentTarget> findByClassId(UUID classId);

    boolean existsByAssignmentIdAndClassId(UUID assignmentId, UUID classId);

    boolean existsByAssignmentIdAndUserId(UUID assignmentId, UUID userId);

    @Modifying
    @Query("DELETE FROM AssignmentTarget t WHERE t.assignmentId = :assignmentId")
    void deleteAllByAssignmentId(@Param("assignmentId") UUID assignmentId);

    /**
     * Find all class IDs that a specific user belongs to (for learner access check).
     */
    @Query("""
            SELECT t.classId FROM AssignmentTarget t
            WHERE t.assignmentId = :assignmentId AND t.classId IS NOT NULL
            """)
    List<UUID> findClassIdsByAssignmentId(@Param("assignmentId") UUID assignmentId);
}

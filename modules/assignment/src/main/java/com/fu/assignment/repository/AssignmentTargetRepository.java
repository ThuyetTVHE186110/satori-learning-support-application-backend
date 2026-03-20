package com.fu.assignment.repository;

import com.fu.assignment.entity.AssignmentTarget;
import com.fu.course.entity.Course;
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

    /**
     * Fetch JLPT level for an assignment by joining targets, classes, and courses.
     * Useful for AI grading to know the target proficiency level.
     */
    @Query(value = """
            SELECT co.jlpt_level
            FROM assignment.assignment_targets at
            JOIN user_management.classes cl ON at.class_id = cl.id
            JOIN learning_content.courses co ON cl.course_id = co.id
            WHERE at.assignment_id = :assignmentId
            LIMIT 1
            """, nativeQuery = true)
    String findJlptLevelByAssignmentId(@Param("assignmentId") UUID assignmentId);
}

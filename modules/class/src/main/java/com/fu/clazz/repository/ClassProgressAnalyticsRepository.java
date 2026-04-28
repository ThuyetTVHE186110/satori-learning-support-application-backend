package com.fu.clazz.repository;

import com.fu.clazz.dto.analytics.ClassMemberInfo;
import com.fu.clazz.dto.analytics.LearnerProgressMetrics;
import com.fu.clazz.dto.analytics.LearningTrendMetrics;
import com.fu.clazz.entity.Clazz;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.query.Param;

/**
 * Analytics repository for class progress dashboard queries.
 * Located in class module to avoid circular dependency issues.
 * Queries assignment and submission data across schemas.
 * 
 * Note: This repository uses Clazz as the domain type to satisfy Spring Data JPA requirements,
 * but all queries are custom native queries that operate across multiple schemas.
 */
@Component
public interface ClassProgressAnalyticsRepository extends Repository<Clazz, UUID> {

    /**
     * Count distinct lessons with assignments in class
     */
    @Query(value = """
            SELECT COUNT(DISTINCT a.lesson_id)
            FROM assignment.assignments a
            WHERE a.class_id = CAST(:classId AS UUID) AND a.deleted_at IS NULL AND a.lesson_id IS NOT NULL
            """, nativeQuery = true)
    Long countLessonsWithAssignmentsInClass(@Param("classId") UUID classId);

    /**
     * Get average score from graded submissions
     */
    @Query(value = """
            SELECT AVG(CAST(s.score AS NUMERIC))
            FROM assignment.assignment_submissions s
            JOIN assignment.assignments a ON a.id = s.assignment_id
            WHERE a.class_id = CAST(:classId AS UUID) AND a.deleted_at IS NULL AND s.status = 'GRADED'
            """, nativeQuery = true)
    Double getAverageScoreForClass(@Param("classId") UUID classId);

    /**
     * Count total assignments in class
     */
    @Query(value = """
            SELECT COUNT(a.id)
            FROM assignment.assignments a
            WHERE a.class_id = CAST(:classId AS UUID) AND a.deleted_at IS NULL
            """, nativeQuery = true)
    Long countAssignmentsInClass(@Param("classId") UUID classId);

    /**
     * Get learner progress metrics (completion, submission, scores)
     */
    @Query(value = """
            SELECT cm.user_id,
                   COUNT(DISTINCT a.id) as total_assignments,
                   COUNT(CASE WHEN s.status = 'GRADED' THEN 1 END) as completed_assignments,
                   COUNT(CASE WHEN s.submitted_at IS NOT NULL THEN 1 END) as submitted_assignments,
                   AVG(CASE WHEN s.status = 'GRADED' THEN CAST(s.score AS NUMERIC) ELSE NULL END) as avg_score
            FROM user_management.class_members cm
            LEFT JOIN assignment.assignments a ON a.class_id = cm.class_id AND a.deleted_at IS NULL
            LEFT JOIN assignment.assignment_submissions s ON a.id = s.assignment_id AND s.user_id = cm.user_id
            WHERE cm.class_id = CAST(:classId AS UUID) AND cm.removed_at IS NULL
            GROUP BY cm.user_id
            ORDER BY cm.user_id
            """, nativeQuery = true)
    List<LearnerProgressMetrics> getProgressMetricsForClass(@Param("classId") UUID classId);

    /**
     * Get active class members with user information
     */
    @Query(value = """
            SELECT cm.user_id, 
                   CONCAT(u.first_name, ' ', u.last_name) as full_name,
                   u.email, u.avatar_url, cm.joined_at
            FROM user_management.class_members cm
            JOIN user_management.users u ON cm.user_id = u.id
            WHERE cm.class_id = CAST(:classId AS UUID) AND cm.removed_at IS NULL
            ORDER BY u.first_name, u.last_name
            """, nativeQuery = true)
    List<ClassMemberInfo> getActiveClassMembersWithUserInfo(@Param("classId") UUID classId);

    /**
     * Get weekly learning trend data for class.
     * Groups submissions by custom weeks starting from class startDate.
     * Week 1 = startDate to startDate+6 days, Week 2 = startDate+7 to startDate+13, etc.
     */
    @Query(value = """
            SELECT 
                (c.start_date + (FLOOR((EXTRACT(EPOCH FROM s.submitted_at - c.start_date) / 86400) / 7) * 7) * INTERVAL '1 day')::DATE as period_start,
                COUNT(DISTINCT s.user_id) as active_learners_count,
                COUNT(s.id) as submissions_count,
                AVG(CAST(s.score AS NUMERIC)) as average_score
            FROM assignment.assignment_submissions s
            JOIN assignment.assignments a ON a.id = s.assignment_id
            JOIN user_management.classes c ON a.class_id = c.id
            WHERE a.class_id = CAST(:classId AS UUID)
                AND a.deleted_at IS NULL 
                AND s.submitted_at IS NOT NULL
                AND (s.status = 'GRADED' OR s.status = 'SUBMITTED')
            GROUP BY (c.start_date + (FLOOR((EXTRACT(EPOCH FROM s.submitted_at - c.start_date) / 86400) / 7) * 7) * INTERVAL '1 day')
            ORDER BY period_start ASC
            """, nativeQuery = true)
    List<LearningTrendMetrics> getWeeklyTrendDataForClass(@Param("classId") UUID classId);
}

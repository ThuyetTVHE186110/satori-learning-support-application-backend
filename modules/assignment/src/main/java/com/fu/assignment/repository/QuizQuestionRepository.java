package com.fu.assignment.repository;

import com.fu.assignment.entity.QuizQuestion;
import com.fu.course.entity.Course;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface QuizQuestionRepository extends JpaRepository<QuizQuestion, UUID> {

    /**
     * Find questions linked to a specific assignment (via assignment_questions join).
     */
    @Query("""
            SELECT q FROM QuizQuestion q
            JOIN AssignmentQuestion aq ON aq.questionId = q.id
            WHERE aq.assignmentId = :assignmentId
            ORDER BY aq.orderIndex ASC
            """)
    List<QuizQuestion> findByAssignmentId(@Param("assignmentId") UUID assignmentId);

    /**
     * Find published questions by JLPT level.
     */
    @Query("""
            SELECT q FROM QuizQuestion q
            WHERE q.jlptLevel = :jlptLevel
            """)
    Page<QuizQuestion> findPublishedByJlptLevel(@Param("jlptLevel") Course.JlptLevel jlptLevel, Pageable pageable);

    /**
     * Count questions linked to an assignment.
     */
    @Query("SELECT COUNT(aq) FROM AssignmentQuestion aq WHERE aq.assignmentId = :assignmentId")
    long countByAssignmentId(@Param("assignmentId") UUID assignmentId);
}

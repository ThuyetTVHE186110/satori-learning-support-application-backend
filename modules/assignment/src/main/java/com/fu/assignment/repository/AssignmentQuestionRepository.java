package com.fu.assignment.repository;

import com.fu.assignment.entity.AssignmentQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AssignmentQuestionRepository extends JpaRepository<AssignmentQuestion, UUID> {

    List<AssignmentQuestion> findByAssignmentIdOrderByOrderIndex(UUID assignmentId);

    Optional<AssignmentQuestion> findByAssignmentIdAndQuestionId(UUID assignmentId, UUID questionId);

    boolean existsByAssignmentIdAndQuestionId(UUID assignmentId, UUID questionId);

    @Modifying
    @Query("DELETE FROM AssignmentQuestion aq WHERE aq.assignmentId = :assignmentId")
    void deleteAllByAssignmentId(@Param("assignmentId") UUID assignmentId);

    long countByAssignmentId(UUID assignmentId);
}

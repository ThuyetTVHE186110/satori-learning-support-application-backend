package com.fu.assignment.repository;

import com.fu.assignment.entity.WritingAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WritingAssignmentRepository extends JpaRepository<WritingAssignment, UUID> {

    Optional<WritingAssignment> findByAssignmentId(UUID assignmentId);

    boolean existsByAssignmentId(UUID assignmentId);

    void deleteByAssignmentId(UUID assignmentId);
}

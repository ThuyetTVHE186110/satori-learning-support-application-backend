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
}

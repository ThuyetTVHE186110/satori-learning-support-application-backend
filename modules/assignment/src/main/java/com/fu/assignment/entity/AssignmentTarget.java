package com.fu.assignment.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * AssignmentTarget entity — maps an assignment to a class or user.
 * Maps to assignment.assignment_targets table.
 *
 * Business: An assignment can be assigned to an entire class (class_id)
 * or to specific users (user_id). At least one must be non-null (DB CHECK constraint).
 */
@Entity
@Table(name = "assignment_targets", schema = "assignment", indexes = {
        @Index(name = "idx_at_assignment_id", columnList = "assignment_id"),
        @Index(name = "idx_at_class_id", columnList = "class_id"),
        @Index(name = "idx_at_user_id", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString
public class AssignmentTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "assignment_id", nullable = false)
    private UUID assignmentId;

    /** Assigned to entire class (nullable — either class_id or user_id must be set) */
    @Column(name = "class_id")
    private UUID classId;

    /** Assigned to specific user (nullable — either class_id or user_id must be set) */
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "assigned_at")
    @Builder.Default
    private LocalDateTime assignedAt = LocalDateTime.now();

    @Column(name = "assigned_by")
    private UUID assignedBy;
}

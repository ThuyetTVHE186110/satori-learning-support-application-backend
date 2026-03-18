package com.fu.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Soft-deletable entity class extending AuditableEntity with soft delete support.
 * Contains: id, createdAt, updatedAt, createdBy, updatedBy (from parents) + deletedAt, deletedBy
 * 
 * Use this for:
 * - Important content that should never be hard-deleted
 * - User data, courses, lessons, assignments, etc.
 * 
 * Soft delete behavior:
 * - Records are never physically deleted
 * - deletedAt is set to current timestamp when "deleted"
 * - deletedBy tracks who performed the deletion
 * - Queries should filter WHERE deleted_at IS NULL
 * 
 * Tables using this:
 * - users, classes, courses, lessons
 * - vocabulary, grammar_points, quiz_questions, assignments
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@MappedSuperclass
public abstract class SoftDeletableEntity extends AuditableEntity {

    /**
     * Timestamp when the record was soft-deleted.
     * NULL means the record is active (not deleted).
     */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /**
     * UUID of the user who deleted this record.
     * Useful for audit trails and accountability.
     */
    @Column(name = "deleted_by")
    private UUID deletedBy;

    /**
     * Check if this entity has been soft-deleted.
     * @return true if deletedAt is not null
     */
    public boolean isDeleted() {
        return deletedAt != null;
    }

    /**
     * Soft delete this entity.
     * Sets deletedAt to current time.
     * Note: deletedBy should be set separately via the service layer.
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * Soft delete this entity with the deleting user's ID.
     * @param deletedByUserId UUID of the user performing the deletion
     */
    public void softDelete(UUID deletedByUserId) {
        this.deletedAt = LocalDateTime.now();
        this.deletedBy = deletedByUserId;
    }

    /**
     * Restore a soft-deleted entity.
     * Clears deletedAt and deletedBy fields.
     */
    public void restore() {
        this.deletedAt = null;
        this.deletedBy = null;
    }
}

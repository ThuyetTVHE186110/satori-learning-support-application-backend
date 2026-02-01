package com.fu.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;

import java.util.UUID;

/**
 * Auditable entity class extending BaseEntity with user tracking.
 * Contains: id, createdAt, updatedAt (from BaseEntity) + createdBy, updatedBy
 * 
 * Use this for:
 * - User-created content that doesn't need soft delete
 * - Tables where you need to track who created/modified records
 * 
 * For soft-deletable content, use {@link SoftDeletableEntity}
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@MappedSuperclass
public abstract class AuditableEntity extends BaseEntity {

    /**
     * UUID of the user who created this record.
     * Automatically populated by Spring Data JPA Auditing via AuditorAware.
     */
    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    /**
     * UUID of the user who last modified this record.
     * Automatically populated by Spring Data JPA Auditing via AuditorAware.
     */
    @LastModifiedBy
    @Column(name = "updated_by")
    private UUID updatedBy;
}

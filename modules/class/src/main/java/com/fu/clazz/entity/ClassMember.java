package com.fu.clazz.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * ClassMember entity - junction table for class membership.
 * Maps to user_management.class_members table.
 * <p>
 * Business: Tracks which users belong to which classes.
 * Uses removedAt for soft-removal from class (not hard delete).
 */
@Entity
@Table(name = "class_members", schema = "user_management",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_class_members_class_user", columnNames = {"class_id", "user_id"})
        },
        indexes = {
                @Index(name = "idx_class_members_class_id", columnList = "class_id"),
                @Index(name = "idx_class_members_user_id", columnList = "user_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
public class ClassMember {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "class_id", nullable = false)
    private UUID classId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "joined_at")
    @Builder.Default
    private LocalDateTime joinedAt = LocalDateTime.now();

    @Column(name = "removed_at")
    private LocalDateTime removedAt;

    /**
     * Check if this member is currently active (not removed).
     */
    public boolean isActive() {
        return removedAt == null;
    }

    /**
     * Remove this member from the class.
     */
    public void remove() {
        this.removedAt = LocalDateTime.now();
    }

    /**
     * Restore a removed member.
     */
    public void restore() {
        this.removedAt = null;
        this.joinedAt = LocalDateTime.now();
    }
}


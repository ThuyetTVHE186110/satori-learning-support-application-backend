package com.fu.assignment.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * WritingAssignment entity.
 * Maps to assignment.writing_assignments table.
 *
 * Business: Supplemental content for WRITING and TRANSLATION assignments.
 * One-to-one with Assignment. Created/updated by teacher alongside the Assignment.
 *
 * WRITING    → teacher sets prompt; learner writes free-form response.
 * TRANSLATION → teacher provides source_text; learner translates JA→VI or VI→JA.
 */
@Entity
@Table(name = "writing_assignments", schema = "assignment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class WritingAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The assignment this content belongs to. One assignment has one writing content. */
    @Column(name = "assignment_id", nullable = false, unique = true)
    private UUID assignmentId;

    // ── Prompt ────────────────────────────────────────────────────────────────

    /** Đề bài (tiếng Việt / tiếng Anh) */
    @Column(name = "prompt", nullable = false, columnDefinition = "TEXT")
    private String prompt;


    // ── Audit ─────────────────────────────────────────────────────────────────
    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}

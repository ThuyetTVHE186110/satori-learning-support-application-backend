package com.fu.assignment.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

import com.fu.infrastructure.persistence.SoftDeletableEntity;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;

/**
 * Assignment entity.
 * Maps to assignment.assignments table.
 * <p>
 * Business: Assignments created by TEACHER for their class.
 * Three types: QUIZ (auto-scored), WRITING and TRANSLATION (teacher-graded).
 * <p>
 * Update policy:
 * - hasSubmissions = false → full edit allowed (all fields)
 * - hasSubmissions = true  → only basic info editable (title, description, instructions, dueDate, ...)
 */
@Entity
@Table(name = "assignments", schema = "assignment", indexes = {
        @Index(name = "idx_assignments_status", columnList = "status"),
        @Index(name = "idx_assignments_class_id", columnList = "class_id"),
        @Index(name = "idx_assignments_created_by", columnList = "created_by"),
        @Index(name = "idx_assignments_due_date", columnList = "due_date")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
public class Assignment extends SoftDeletableEntity {

    // ── Basic Info ────────────────────────────────────────────────────────────

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "instructions", columnDefinition = "TEXT")
    private String instructions;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "assignment_type")
    private AssignmentType assignmentType;

    // ── Target ────────────────────────────────────────────────────────────────

    /**
     * The class this assignment belongs to. Set by teacher at creation.
     */
    @Column(name = "class_id")
    private UUID classId;

    // ── Timing ────────────────────────────────────────────────────────────────

    @Column(name = "start_date")
    private LocalDateTime startDate;

    @Column(name = "due_date", nullable = false)
    private LocalDateTime dueDate;

    // ── Scoring ───────────────────────────────────────────────────────────────

    // ── Quiz Settings (only for QUIZ type) ───────────────────────────────────

    /**
     * Optional background/listening audio for the entire assignment.
     * Used for listening-comprehension quizzes where a single audio track covers all questions.
     */
    @Column(name = "audio_url", length = 1024)
    private String audioUrl;

    @Column(name = "question_count")
    @Builder.Default
    private Integer questionCount = 10;

    // ── Status ────────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "status")
    private AssignmentStatus status = AssignmentStatus.DRAFT;

    /**
     * Cache flag: true once the first learner starts this assignment.
     * Determines whether full-edit or basic-only edit is allowed.
     */
    @Column(name = "has_submissions", nullable = false)
    @Builder.Default
    private Boolean hasSubmissions = false;

    // ── Business Helpers ──────────────────────────────────────────────────────

    public boolean isQuiz() {
        return assignmentType == AssignmentType.QUIZ;
    }

    public boolean isWritingOrTranslation() {
        return assignmentType == AssignmentType.WRITING
                || assignmentType == AssignmentType.TRANSLATION;
    }
}

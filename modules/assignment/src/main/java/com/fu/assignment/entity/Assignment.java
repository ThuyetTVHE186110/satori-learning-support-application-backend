package com.fu.assignment.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

import com.fu.infrastructure.persistence.SoftDeletableEntity;

/**
 * Assignment entity.
 * Maps to assignment.assignments table.
 *
 * Business: Assignments created by TEACHER for their class.
 * Three types: QUIZ (auto-scored), WRITING and TRANSLATION (teacher-graded).
 *
 * Update policy:
 *  - hasSubmissions = false → full edit allowed (all fields)
 *  - hasSubmissions = true  → only basic info editable (title, description, instructions, dueDate, ...)
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
    @Column(name = "assignment_type", nullable = false, length = 30)
    @org.hibernate.annotations.ColumnTransformer(write = "CAST(? AS assignment_type)")
    private AssignmentType assignmentType;

    // ── Target ────────────────────────────────────────────────────────────────

    /** The class this assignment belongs to. Set by teacher at creation. */
    @Column(name = "class_id")
    private UUID classId;

    // ── Timing ────────────────────────────────────────────────────────────────

    @Column(name = "start_date")
    private LocalDateTime startDate;

    @Column(name = "due_date", nullable = false)
    private LocalDateTime dueDate;

    /** NULL = no time limit */
    @Column(name = "time_limit_minutes")
    private Integer timeLimitMinutes;

    @Column(name = "late_submission_allowed")
    @Builder.Default
    private Boolean lateSubmissionAllowed = false;

    @Column(name = "late_submission_penalty_percent")
    @Builder.Default
    private Integer lateSubmissionPenaltyPercent = 0;

    // ── Scoring ───────────────────────────────────────────────────────────────

    @Column(name = "max_score")
    @Builder.Default
    private Integer maxScore = 100;

    @Column(name = "passing_score")
    @Builder.Default
    private Integer passingScore = 60;

    // ── Quiz Settings (only for QUIZ type) ───────────────────────────────────

    @Column(name = "shuffle_questions")
    @Builder.Default
    private Boolean shuffleQuestions = false;

    @Column(name = "shuffle_options")
    @Builder.Default
    private Boolean shuffleOptions = false;

    /** Show correct answers after submission */
    @Column(name = "show_correct_answers")
    @Builder.Default
    private Boolean showCorrectAnswers = true;

    @Column(name = "allow_retry")
    @Builder.Default
    private Boolean allowRetry = false;

    @Column(name = "max_retries")
    @Builder.Default
    private Integer maxRetries = 1;

    // ── Content Reference ─────────────────────────────────────────────────────

    @Column(name = "lesson_id")
    private UUID lessonId;

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
    @Column(name = "status", length = 20)
    @org.hibernate.annotations.ColumnTransformer(write = "CAST(? AS assignment_status)")
    @Builder.Default
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

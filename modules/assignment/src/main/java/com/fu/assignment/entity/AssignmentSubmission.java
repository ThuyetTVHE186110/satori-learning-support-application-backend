package com.fu.assignment.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * AssignmentSubmission entity.
 * Maps to assignment.assignment_submissions table.
 *
 * Business: Tracks a learner's submission for an assignment.
 * - QUIZ:        answers stored as JSONB; auto-scored on submit → status = GRADED
 * - WRITING:     writtenAnswer text; teacher grades → status = GRADED via gradeWriting()
 * - TRANSLATION: writtenAnswer text; teacher grades → status = GRADED
 */
@Entity
@Table(name = "assignment_submissions", schema = "assignment",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_submission_attempt",
                        columnNames = {"assignment_id", "user_id", "attempt_number"})
        },
        indexes = {
                @Index(name = "idx_sub_assignment_id", columnList = "assignment_id"),
                @Index(name = "idx_sub_user_id", columnList = "user_id"),
                @Index(name = "idx_sub_status", columnList = "status"),
                @Index(name = "idx_sub_submitted_at", columnList = "submitted_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString
public class AssignmentSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // ── References ────────────────────────────────────────────────────────────

    @Column(name = "assignment_id", nullable = false)
    private UUID assignmentId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    // ── Status & Attempt ──────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    @org.hibernate.annotations.ColumnTransformer(write = "CAST(? AS submission_status)")
    @Builder.Default
    private SubmissionStatus status = SubmissionStatus.NOT_STARTED;

    @Column(name = "attempt_number")
    @Builder.Default
    private Integer attemptNumber = 1;

    // ── Progress Timestamps ───────────────────────────────────────────────────

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    // ── Quiz Scoring (auto) ───────────────────────────────────────────────────

    /** Auto-calculated score for QUIZ type (comparison of answers) */
    @Column(name = "ai_auto_score", precision = 5, scale = 2)
    private BigDecimal aiAutoScore;

    /** Final score (for QUIZ = aiAutoScore; for Writing/Translation = teacherScore) */
    @Column(name = "score", precision = 5, scale = 2)
    private BigDecimal score;

    @Column(name = "correct_count")
    @Builder.Default
    private Integer correctCount = 0;

    @Column(name = "total_questions")
    @Builder.Default
    private Integer totalQuestions = 0;

    // ── Writing/Translation Scoring (manual) ──────────────────────────────────

    /** Learner's written answer for WRITING or TRANSLATION assignment */
    @Column(name = "written_answer", columnDefinition = "TEXT")
    private String writtenAnswer;

    /** URLs of images submitted by learner (e.g. handwritten answers photographed) */
    @Column(name = "image_urls", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String imageUrls;

    /** Teacher-given score for WRITING/TRANSLATION */
    @Column(name = "teacher_score", precision = 5, scale = 2)
    private BigDecimal teacherScore;

    // ── Common Feedback & Grading ─────────────────────────────────────────────

    /** General feedback (used for both quiz and writing) */
    @Column(name = "feedback", columnDefinition = "TEXT")
    private String feedback;

    @Column(name = "graded_by")
    private UUID gradedBy;

    @Column(name = "graded_at")
    private LocalDateTime gradedAt;

    // ── Time Tracking ─────────────────────────────────────────────────────────

    @Column(name = "time_spent_seconds")
    @Builder.Default
    private Integer timeSpentSeconds = 0;

    // ── Quiz Answers (JSONB) ──────────────────────────────────────────────────

    /**
     * Answers stored as JSONB (QUIZ type).
     * Structure: [{"questionId":"uuid","selectedAnswer":"A","isCorrect":true,"timeSpent":15}, ...]
     */
    @Column(name = "answers", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    @Builder.Default
    private String answers = "[]";

    // ── Audit ─────────────────────────────────────────────────────────────────

    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "updated_by")
    private UUID updatedBy;

    // ── Business Logic Helpers ────────────────────────────────────────────────

    public boolean isPassed(Integer passingScore) {
        return score != null && passingScore != null
                && score.compareTo(BigDecimal.valueOf(passingScore)) >= 0;
    }

    public boolean isSubmitted() {
        return status == SubmissionStatus.SUBMITTED || status == SubmissionStatus.GRADED;
    }

    public boolean isInProgress() {
        return status == SubmissionStatus.IN_PROGRESS;
    }
}

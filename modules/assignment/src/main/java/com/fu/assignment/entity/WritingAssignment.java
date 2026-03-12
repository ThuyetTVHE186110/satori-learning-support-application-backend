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

    /** Đề bài tiếng Nhật (nếu muốn hiển thị song ngữ) */
    @Column(name = "prompt_japanese", columnDefinition = "TEXT")
    private String promptJapanese;

    // ── Sample Answer (hidden from learner) ───────────────────────────────────

    @Column(name = "sample_answer", columnDefinition = "TEXT")
    private String sampleAnswer;

    @Column(name = "sample_answer_japanese", columnDefinition = "TEXT")
    private String sampleAnswerJapanese;

    // ── Word / Length Constraints ─────────────────────────────────────────────

    /** Số từ tối thiểu */
    @Column(name = "min_words")
    private Integer minWords;

    /** Số từ tối đa */
    @Column(name = "max_words")
    private Integer maxWords;

    // ── Translation-specific (only for TRANSLATION type) ──────────────────────

    /**
     * Đoạn văn cần dịch (chỉ dùng cho TRANSLATION).
     * Learner đọc đoạn này và dịch sang chiều translation_direction.
     */
    @Column(name = "source_text", columnDefinition = "TEXT")
    private String sourceText;

    /**
     * Chiều dịch: JA_TO_VI (Nhật → Việt) hoặc VI_TO_JA (Việt → Nhật).
     * Chỉ 2 giá trị hợp lệ, được validate ở service layer.
     */
    @Column(name = "translation_direction", length = 10)
    private String translationDirection;

    // ── Audit ─────────────────────────────────────────────────────────────────

    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}

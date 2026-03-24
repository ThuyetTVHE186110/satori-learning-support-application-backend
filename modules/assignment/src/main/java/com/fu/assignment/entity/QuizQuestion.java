package com.fu.assignment.entity;

import com.fu.course.entity.Course;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

import com.fu.infrastructure.persistence.SoftDeletableEntity;

/**
 * QuizQuestion entity.
 * Maps to assignment.quiz_questions table.
 *
 * Business: Reusable quiz questions. Can be created manually or AI-generated from PDF/text.
 * Supports multiple choice, fill-in-the-blank, matching, true/false, ordering.
 * For LISTENING questions: attach audioUrl. For READING: attach imageUrl.
 */
@Entity
@Table(name = "quiz_questions", schema = "assignment", indexes = {
        @Index(name = "idx_quiz_questions_jlpt_level", columnList = "jlpt_level"),
        @Index(name = "idx_quiz_questions_status", columnList = "status"),
        @Index(name = "idx_quiz_questions_ai_generated", columnList = "ai_generated")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
public class QuizQuestion extends SoftDeletableEntity {

    // ── Question Content ──────────────────────────────────────────────────────

    @Column(name = "question_text", nullable = false, columnDefinition = "TEXT")
    private String questionText;


    /**
     * Question type: 'multiple_choice', 'fill_blank', 'matching', 'true_false', 'ordering'
     */
    @Column(name = "question_type", nullable = false, length = 50)
    private String questionType;

    /**
     * Question subtype (medium): 'LISTENING', 'READING', 'STANDARD'
     * LISTENING = có audio, READING = có hình ảnh/văn bản, STANDARD = thường
     */
    @Column(name = "question_subtype", length = 50)
    @Builder.Default
    private String questionSubtype = "STANDARD";

    // ── Options (JSONB) ───────────────────────────────────────────────────────

    /**
     * Options for multiple choice.
     * Structure: [{"id":"A","text":"食べる","isCorrect":true}, ...]
     */
    @Column(name = "options", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String options;

    // ── Correct Answers ───────────────────────────────────────────────────────

    /** For fill_blank, true_false */
    @Column(name = "correct_answer", columnDefinition = "TEXT")
    private String correctAnswer;

    /** For matching, ordering: [{"left":"A","right":"1"}, ...] */
    @Column(name = "correct_answers", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String correctAnswers;

    // ── Explanation ───────────────────────────────────────────────────────────

    /** Giải thích do teacher / import viết */
    @Column(name = "explanation", columnDefinition = "TEXT")
    private String explanation;

    // ── AI Generation ─────────────────────────────────────────────────────────

    /** True nếu câu hỏi được AI tạo từ file/text */
    @Column(name = "ai_generated", nullable = false)
    @Builder.Default
    private Boolean aiGenerated = false;

    // ── Classification ────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "jlpt_level", length = 5)
    @org.hibernate.annotations.ColumnTransformer(write = "CAST(? AS jlpt_level)")
    private Course.JlptLevel jlptLevel;

}

package com.fu.practice.entity;

import com.fu.infrastructure.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * LessonProgress — tiến độ luyện tập của học viên theo từng bài học.
 * Maps to practice.lesson_progress table.
 *
 * Được upsert sau mỗi phiên luyện tập hoàn thành.
 */
@Entity
@Table(name = "lesson_progress", schema = "practice",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_lesson_progress_user_lesson",
                columnNames = {"user_id", "lesson_id"}
        ),
        indexes = {
                @Index(name = "idx_lesson_progress_user_id", columnList = "user_id"),
                @Index(name = "idx_lesson_progress_lesson_id", columnList = "lesson_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
public class LessonProgress extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "lesson_id", nullable = false)
    private UUID lessonId;

    @Column(name = "total_sessions", nullable = false)
    @Builder.Default
    private int totalSessions = 0;

    /**
     * Điểm cao nhất đạt được (0-100).
     */
    @Column(name = "best_score", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal bestScore = BigDecimal.ZERO;

    /**
     * Điểm phiên luyện tập gần nhất (0-100).
     */
    @Column(name = "last_score", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal lastScore = BigDecimal.ZERO;

    /**
     * Tỉ lệ thuộc từ vựng (0-100).
     */
    @Column(name = "vocab_mastery", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal vocabMastery = BigDecimal.ZERO;

    /**
     * Tỉ lệ nắm ngữ pháp (0-100).
     */
    @Column(name = "grammar_mastery", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal grammarMastery = BigDecimal.ZERO;

    @Column(name = "last_practiced_at")
    private LocalDateTime lastPracticedAt;
}

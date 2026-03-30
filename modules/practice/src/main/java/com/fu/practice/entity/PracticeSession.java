package com.fu.practice.entity;

import com.fu.infrastructure.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * PracticeSession — một phiên luyện tập của học viên.
 * Maps to practice.practice_sessions table.
 */
@Entity
@Table(name = "practice_sessions", schema = "practice", indexes = {
        @Index(name = "idx_practice_sessions_user_id", columnList = "user_id"),
        @Index(name = "idx_practice_sessions_lesson_id", columnList = "lesson_id"),
        @Index(name = "idx_practice_sessions_course_id", columnList = "course_id"),
        @Index(name = "idx_practice_sessions_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
public class PracticeSession extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "lesson_id")
    private UUID lessonId;

    @Column(name = "course_id", nullable = false)
    private UUID courseId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "session_type", columnDefinition = "practice_session_type", nullable = false)
    private PracticeSessionType sessionType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", columnDefinition = "practice_status", nullable = false)
    @Builder.Default
    private PracticeStatus status = PracticeStatus.IN_PROGRESS;

    @Column(name = "total_items", nullable = false)
    @Builder.Default
    private int totalItems = 0;

    @Column(name = "completed_items", nullable = false)
    @Builder.Default
    private int completedItems = 0;

    @Column(name = "correct_items", nullable = false)
    @Builder.Default
    private int correctItems = 0;

    @Column(name = "score", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal score = BigDecimal.ZERO;

    /**
     * Context JSON: thông tin bài học, JLPT level, v.v.
     */
    @Column(name = "context", columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String context;

    /**
     * AI Feedback JSON: tổng kết, điểm mạnh, điểm yếu, gợi ý.
     */
    @Column(name = "ai_feedback", columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String aiFeedback;

    @Column(name = "started_at", nullable = false)
    @Builder.Default
    private LocalDateTime startedAt = LocalDateTime.now();

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;
}

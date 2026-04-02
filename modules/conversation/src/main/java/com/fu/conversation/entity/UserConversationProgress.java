package com.fu.conversation.entity;

import com.fu.infrastructure.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Tracks per-user progress on a conversation topic.
 * Maps to speaking.user_conversation_progress.
 * Updated by RoleplaySessionService on session start and completion.
 */
@Entity
@Table(name = "user_conversation_progress", schema = "speaking",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_user_conv_progress",
                columnNames = {"user_id", "topic_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class UserConversationProgress extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "topic_id", nullable = false)
    private UUID topicId;

    @Column(name = "total_sessions", nullable = false)
    @Builder.Default
    private Integer totalSessions = 0;

    @Column(name = "total_messages_sent", nullable = false)
    @Builder.Default
    private Integer totalMessagesSent = 0;

    @Column(name = "best_fluency_score", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal bestFluencyScore = BigDecimal.ZERO;

    @Column(name = "best_accuracy_score", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal bestAccuracyScore = BigDecimal.ZERO;

    @Column(name = "best_vocabulary_score", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal bestVocabularyScore = BigDecimal.ZERO;

    @Column(name = "best_overall_score", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal bestOverallScore = BigDecimal.ZERO;

    @Column(name = "last_practiced_at")
    private LocalDateTime lastPracticedAt;

    @Column(name = "is_completed", nullable = false)
    @Builder.Default
    private Boolean isCompleted = false;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}

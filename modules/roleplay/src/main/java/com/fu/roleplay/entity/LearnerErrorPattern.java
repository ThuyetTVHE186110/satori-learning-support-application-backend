package com.fu.roleplay.entity;

import com.fu.infrastructure.persistence.BaseEntity;
import com.fu.roleplay.enums.ErrorType;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnTransformer;

import java.util.UUID;

/**
 * Tracks error type counts per user per session for cross-session pattern detection.
 * One row per (userId, sessionId, errorType) combination.
 */
@Entity
@Table(name = "learner_error_patterns", schema = "ai_conversation",
        indexes = {
                @Index(name = "idx_error_patterns_user", columnList = "user_id"),
                @Index(name = "idx_error_patterns_user_type", columnList = "user_id, error_type"),
                @Index(name = "idx_error_patterns_session", columnList = "session_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
public class LearnerErrorPattern extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_type", nullable = false, length = 30)
    private ErrorType errorType;

    @Column(name = "occurrence_count", nullable = false)
    @Builder.Default
    private Integer occurrenceCount = 0;

    @Column(name = "examples", columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    @Builder.Default
    private String examples = "[]";
}

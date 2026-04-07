package com.fu.roleplay.entity;

import com.fu.infrastructure.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnTransformer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "conversation_sessions", schema = "ai_conversation",
        indexes = {
                @Index(name = "idx_conversation_sessions_user", columnList = "user_id, created_at"),
                @Index(name = "idx_conversation_sessions_status", columnList = "user_id, status"),
                @Index(name = "idx_conversation_sessions_conversation", columnList = "conversation_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
public class ConversationSession extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "session_type", nullable = false)
    @ColumnTransformer(write = "CAST(? AS session_type)")
    private SessionType sessionType;

    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(name = "assignment_id")
    private UUID assignmentId;

    @Column(name = "context", columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    @Builder.Default
    private String context = "{}";

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    @ColumnTransformer(write = "CAST(? AS session_status)")
    @Builder.Default
    private SessionStatus status = SessionStatus.ACTIVE;

    @Column(name = "feedback", columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    @Builder.Default
    private String feedback = "{}";

    @Column(name = "message_count")
    @Builder.Default
    private Integer messageCount = 0;

    @Column(name = "user_message_count")
    @Builder.Default
    private Integer userMessageCount = 0;

    @Column(name = "hints_used")
    @Builder.Default
    private Integer hintsUsed = 0;

    @Column(name = "corrections_count")
    @Builder.Default
    private Integer correctionsCount = 0;

    @Column(name = "duration_seconds")
    @Builder.Default
    private Integer durationSeconds = 0;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    // Scoring columns (added by 10-roleplay-module.sql)
    @Column(name = "mission_score", precision = 5, scale = 2)
    private BigDecimal missionScore;

    @Column(name = "pronunciation_score", precision = 5, scale = 2)
    private BigDecimal pronunciationScore;

    @Column(name = "language_score", precision = 5, scale = 2)
    private BigDecimal languageScore;

    @Column(name = "overall_score", precision = 5, scale = 2)
    private BigDecimal overallScore;

    @Column(name = "mission_summary", columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    @Builder.Default
    private String missionSummary = "{}";

    @Column(name = "conversation_phase", length = 20)
    @Builder.Default
    private String conversationPhase = "CONVERSATION";

    public enum SessionType {
        GUIDED_CONVERSATION, ROLEPLAY, FREE_TALK, PRACTICE_ASSIGNMENT
    }

    public enum SessionStatus {
        ACTIVE, COMPLETED, ABANDONED
    }
}

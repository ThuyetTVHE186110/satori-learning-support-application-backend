package com.fu.conversation.entity;

import com.fu.infrastructure.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnTransformer;

import java.util.UUID;

/**
 * JPA entity for ai_conversation.conversation_sessions.
 * Used exclusively by the CURRICULUM_QA chatbot feature.
 * Roleplay sessions are managed by the roleplay module's ConversationSession entity.
 */
@Entity
@Table(name = "conversation_sessions", schema = "ai_conversation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class ChatSession extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** PostgreSQL enum: session_type. Always 'CURRICULUM_QA' for chatbot sessions. */
    @Column(name = "session_type", columnDefinition = "session_type", nullable = false)
    @ColumnTransformer(write = "?::session_type")
    private String sessionType;

    /** JSONB: { courseId, lessonId?, jlptLevel?, sessionType } */
    @Column(name = "context", columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String context;

    /** PostgreSQL enum: session_status (ACTIVE, COMPLETED, ABANDONED). */
    @Column(name = "status", columnDefinition = "session_status", nullable = false)
    @ColumnTransformer(write = "?::session_status")
    private String status;

    @Column(name = "message_count", nullable = false)
    @Builder.Default
    private int messageCount = 0;

    @Column(name = "user_message_count", nullable = false)
    @Builder.Default
    private int userMessageCount = 0;

    @Column(name = "hints_used", nullable = false)
    @Builder.Default
    private int hintsUsed = 0;

    @Column(name = "corrections_count", nullable = false)
    @Builder.Default
    private int correctionsCount = 0;

    /** Display title shown in session list. Set at creation from course/lesson context;
     *  overwritten async with an AI-generated summary after the first message. */
    @Column(name = "title", length = 255)
    private String title;
}

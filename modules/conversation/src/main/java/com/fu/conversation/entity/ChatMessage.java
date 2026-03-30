package com.fu.conversation.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnTransformer;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * JPA entity for ai_conversation.conversation_messages.
 * Does not extend BaseEntity because conversation_messages has no updated_at column.
 */
@Entity
@Table(name = "conversation_messages", schema = "ai_conversation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    /** PostgreSQL enum: message_role (USER, ASSISTANT, SYSTEM, HINT). */
    @Column(name = "role", columnDefinition = "message_role", nullable = false)
    @ColumnTransformer(write = "?::message_role")
    private String role;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}

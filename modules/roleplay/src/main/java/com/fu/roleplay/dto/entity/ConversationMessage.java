package com.fu.roleplay.dto.entity;

import com.fu.infrastructure.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnTransformer;

import java.util.UUID;

@Entity
@Table(name = "conversation_messages", schema = "ai_conversation",
        indexes = {
                @Index(name = "idx_conversation_messages_session", columnList = "session_id, sequence_number")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
public class ConversationMessage extends BaseEntity {

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    @ColumnTransformer(write = "CAST(? AS message_role)")
    private MessageRole role;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "japanese_content", columnDefinition = "TEXT")
    private String japaneseContent;

    @Column(name = "translation", columnDefinition = "TEXT")
    private String translation;

    @Column(name = "romaji", columnDefinition = "TEXT")
    private String romaji;

    @Column(name = "audio_url", columnDefinition = "TEXT")
    private String audioUrl;

    @Column(name = "user_audio_url", columnDefinition = "TEXT")
    private String userAudioUrl;

    @Column(name = "corrections", columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    @Builder.Default
    private String corrections = "[]";

    @Column(name = "vocabulary_used", columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    @Builder.Default
    private String vocabularyUsed = "[]";

    @Column(name = "is_hint_request")
    @Builder.Default
    private Boolean isHintRequest = false;

    @Column(name = "sequence_number", nullable = false)
    private Integer sequenceNumber;

    @Column(name = "processing_time_ms")
    private Integer processingTimeMs;

    @Column(name = "message_type", length = 30)
    private String messageType;

    public enum MessageRole {
        USER, ASSISTANT, SYSTEM, HINT
    }
}

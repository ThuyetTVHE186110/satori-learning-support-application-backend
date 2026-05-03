package com.fu.roleplay.entity;

import com.fu.infrastructure.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "message_pronunciation", schema = "ai_conversation",
        indexes = {
                @Index(name = "idx_msg_pronunciation_message", columnList = "message_id"),
                @Index(name = "idx_msg_pronunciation_session", columnList = "session_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
public class MessagePronunciation extends BaseEntity {

    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "overall_score", precision = 5, scale = 2)
    private BigDecimal overallScore;

    @Column(name = "accuracy_score", precision = 5, scale = 2)
    private BigDecimal accuracyScore;

    @Column(name = "fluency_score", precision = 5, scale = 2)
    private BigDecimal fluencyScore;

    @Column(name = "completeness_score", precision = 5, scale = 2)
    private BigDecimal completenessScore;

    @Column(name = "transcribed_text", columnDefinition = "TEXT")
    private String transcribedText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_response", columnDefinition = "jsonb")
    @Builder.Default
    private String rawResponse = "{}";

    /**
     * Azure word-level array: [{word, accuracyScore, errorType, phonemes:[{phoneme, accuracyScore}]}].
     * Source of truth for {@code PronunciationAggregator} session rollup. Nullable when the
     * provider returned no per-word detail (eg. parse failure or non-Azure provider in future).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "word_details", columnDefinition = "jsonb")
    private String wordDetails;

    /**
     * Per-message pre-aggregation of phonemes scoring &lt; 70: {ipa: {count, avgScore}}.
     * Avoids re-scanning {@link #wordDetails} at session level. Nullable when no weak phonemes.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "weak_phonemes", columnDefinition = "jsonb")
    private String weakPhonemes;
}

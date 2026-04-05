package com.fu.conversation.entity;

import com.fu.infrastructure.persistence.SoftDeletableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnTransformer;

import java.util.UUID;

/**
 * ConversationMission - A specific task or goal within a Conversation.
 * Learners must complete missions during a practice session.
 * Maps to speaking.conversation_missions table.
 */
@Entity
@Table(name = "conversation_missions", schema = "speaking",
        indexes = {
                @Index(name = "idx_conv_missions_conversation", columnList = "conversation_id"),
                @Index(name = "idx_conv_missions_type", columnList = "mission_type")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
public class ConversationMission extends SoftDeletableEntity {

    /**
     * Parent conversation this mission belongs to.
     */
    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "title_japanese", length = 255)
    private String titleJapanese;

    @Column(name = "title_vi", length = 255)
    private String titleVi;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "description_vi", columnDefinition = "TEXT")
    private String descriptionVi;

    @Enumerated(EnumType.STRING)
    @Column(name = "mission_type", columnDefinition = "mission_type")
    @ColumnTransformer(write = "CAST(? AS mission_type)")
    private MissionType missionType;

    /**
     * JSON object describing conditions required to complete this mission.
     * Example: {"requiredActions": [...], "requiredVocabulary": [...], "completionConditions": "..."}
     */
    @Column(name = "success_criteria", columnDefinition = "jsonb")
    @ColumnTransformer(write = "CAST(? AS jsonb)")
    @Builder.Default
    private String successCriteria = "{}";

    /**
     * JSON array of vocabulary words the learner must use.
     */
    @Column(name = "required_vocabulary", columnDefinition = "jsonb")
    @ColumnTransformer(write = "CAST(? AS jsonb)")
    @Builder.Default
    private String requiredVocabulary = "[]";

    /**
     * JSON array of grammar patterns the learner must use.
     */
    @Column(name = "required_grammar", columnDefinition = "jsonb")
    @ColumnTransformer(write = "CAST(? AS jsonb)")
    @Builder.Default
    private String requiredGrammar = "[]";

    @Column(name = "order_index")
    @Builder.Default
    private Integer orderIndex = 0;

    /**
     * Type of conversation mission — defines the nature of the task.
     */
    public enum MissionType {
        INFORMATION_GATHERING,
        TRANSACTIONAL,
        SOCIAL,
        PROBLEM_SOLVING,
        DESCRIPTIVE,
        CUSTOM
    }
}

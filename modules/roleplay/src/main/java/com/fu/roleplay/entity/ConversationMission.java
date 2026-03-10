package com.fu.roleplay.entity;

import com.fu.infrastructure.persistence.SoftDeletableEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Entity
@Table(name = "conversation_missions", schema = "speaking",
        indexes = {
                @Index(name = "idx_conv_missions_topic", columnList = "topic_id"),
                @Index(name = "idx_conv_missions_type", columnList = "mission_type")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
public class ConversationMission extends SoftDeletableEntity {

    @Column(name = "topic_id", nullable = false)
    private UUID topicId;

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
    @Column(name = "mission_type", nullable = false)
    @ColumnTransformer(write = "CAST(? AS mission_type)")
    @Builder.Default
    private MissionType missionType = MissionType.CUSTOM;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "success_criteria", columnDefinition = "jsonb")
    @Builder.Default
    private String successCriteria = "{}";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "required_vocabulary", columnDefinition = "jsonb")
    @Builder.Default
    private String requiredVocabulary = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "required_grammar", columnDefinition = "jsonb")
    @Builder.Default
    private String requiredGrammar = "[]";

    @Column(name = "order_index")
    @Builder.Default
    private Integer orderIndex = 0;

    public enum MissionType {
        INFORMATION_GATHERING, TRANSACTIONAL, SOCIAL, PROBLEM_SOLVING, DESCRIPTIVE, CUSTOM
    }
}

package com.fu.roleplay.dto.entity;

import com.fu.conversation.entity.ConversationMission;
import com.fu.infrastructure.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Entity
@Table(name = "session_missions", schema = "ai_conversation",
        indexes = {
                @Index(name = "idx_session_missions_session", columnList = "session_id"),
                @Index(name = "idx_session_missions_status", columnList = "session_id, status")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
public class SessionMission extends BaseEntity {

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "mission_id")
    private UUID missionId;

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
    private ConversationMission.MissionType missionType = ConversationMission.MissionType.CUSTOM;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "success_criteria", columnDefinition = "jsonb")
    @Builder.Default
    private String successCriteria = "{}";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @ColumnTransformer(write = "CAST(? AS mission_status)")
    @Builder.Default
    private MissionStatus status = MissionStatus.PENDING;

    @Column(name = "progress_pct")
    @Builder.Default
    private Integer progressPct = 0;

    @Column(name = "ai_reasoning", columnDefinition = "TEXT")
    private String aiReasoning;

    public enum MissionStatus {
        PENDING, IN_PROGRESS, COMPLETED, FAILED
    }
}

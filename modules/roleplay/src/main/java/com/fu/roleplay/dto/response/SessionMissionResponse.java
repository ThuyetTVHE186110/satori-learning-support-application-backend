package com.fu.roleplay.dto.response;

import com.fu.conversation.entity.ConversationMission;
import com.fu.roleplay.dto.entity.SessionMission;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionMissionResponse {

    private UUID id;
    private String title;
    private String titleJapanese;
    private String titleVi;
    private String description;
    private String descriptionVi;
    private String aiReasoning;
    private ConversationMission.MissionType missionType;
    private SessionMission.MissionStatus status;
    private Integer progressPct;
}

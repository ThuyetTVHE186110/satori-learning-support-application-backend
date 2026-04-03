package com.fu.roleplay.dto.response;

import com.fu.conversation.entity.ConversationMission;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MissionResponse {

    private UUID id;
    private UUID conversationId;
    private String title;
    private String titleJapanese;
    private String titleVi;
    private String description;
    private String descriptionVi;
    private ConversationMission.MissionType missionType;
    private String successCriteria;
    private String requiredVocabulary;
    private String requiredGrammar;
    private Integer orderIndex;
    private UUID createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

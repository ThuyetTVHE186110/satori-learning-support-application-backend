package com.fu.conversation.dto.request;

import com.fu.conversation.entity.ConversationMission;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateMissionRequest {

    @Size(max = 255)
    private String title;

    @Size(max = 255)
    private String titleJapanese;

    @Size(max = 255)
    private String titleVi;

    private String description;

    private String descriptionVi;

    private ConversationMission.MissionType missionType;

    private String successCriteria;

    private String requiredVocabulary;

    private String requiredGrammar;

    private Integer orderIndex;
}

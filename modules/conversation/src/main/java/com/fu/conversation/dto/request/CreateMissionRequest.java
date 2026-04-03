package com.fu.conversation.dto.request;

import com.fu.conversation.entity.ConversationMission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateMissionRequest {

    @NotNull
    private UUID conversationId;

    @NotBlank
    @Size(max = 255)
    private String title;

    @Size(max = 255)
    private String titleJapanese;

    @Size(max = 255)
    private String titleVi;

    private String description;

    private String descriptionVi;

    @Builder.Default
    private ConversationMission.MissionType missionType = ConversationMission.MissionType.CUSTOM;

    private String successCriteria;

    private String requiredVocabulary;

    private String requiredGrammar;

    @Builder.Default
    private Integer orderIndex = 0;
}

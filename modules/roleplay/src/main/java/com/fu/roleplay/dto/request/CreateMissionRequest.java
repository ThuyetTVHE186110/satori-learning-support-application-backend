package com.fu.roleplay.dto.request;

import com.fu.common.validator.ValidJson;
import com.fu.conversation.entity.ConversationMission;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

    @NotNull(message = "Topic ID is required")
    private UUID topicId;

    @NotBlank(message = "Title is required")
    @Size(max = 255)
    private String title;

    @Size(max = 255)
    private String titleJapanese;

    @Size(max = 5000, message = "Description must not exceed 5000 characters")
    private String description;

    @Builder.Default
    private ConversationMission.MissionType missionType = ConversationMission.MissionType.CUSTOM;

    @ValidJson(message = "Success criteria must be valid JSON")
    @Size(max = 10000, message = "Success criteria must not exceed 10000 characters")
    private String successCriteria;

    @Size(max = 5000, message = "Required vocabulary must not exceed 5000 characters")
    private String requiredVocabulary;

    @Size(max = 5000, message = "Required grammar must not exceed 5000 characters")
    private String requiredGrammar;

    @Min(0)
    @Max(100)
    @Builder.Default
    private Integer orderIndex = 0;
}

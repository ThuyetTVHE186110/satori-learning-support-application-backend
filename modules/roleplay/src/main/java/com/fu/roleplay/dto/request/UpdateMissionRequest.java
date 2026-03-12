package com.fu.roleplay.dto.request;

import com.fu.common.validator.ValidJson;
import com.fu.conversation.entity.ConversationMission;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

    @Size(max = 5000, message = "Description must not exceed 5000 characters")
    private String description;

    private ConversationMission.MissionType missionType;

    @ValidJson(message = "Success criteria must be valid JSON")
    @Size(max = 10000, message = "Success criteria must not exceed 10000 characters")
    private String successCriteria;

    @Size(max = 5000, message = "Required vocabulary must not exceed 5000 characters")
    private String requiredVocabulary;

    @Size(max = 5000, message = "Required grammar must not exceed 5000 characters")
    private String requiredGrammar;

    @Min(0)
    @Max(100)
    private Integer orderIndex;
}

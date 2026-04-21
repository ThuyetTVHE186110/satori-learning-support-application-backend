package com.fu.conversation.dto.request;

import com.fu.conversation.entity.ConversationMission;
import com.fu.course.entity.Course;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateConversationRequest {

    private UUID topicId;

    private UUID courseId;

    @NotBlank(message = "Title is required")
    @Size(max = 255)
    private String title;

    @Size(max = 255)
    private String titleJapanese;
    @Size(max = 1000)
    private String description;
    @Size(max = 1000)
    private String descriptionVi;
    @NotBlank(message = "Không được để trống kịch bản")
    private String scenario;

    private String vocabularyHints;

    private String grammarPoints;

    private String usefulExpressions;

    @Size(max = 100)
    private String category;

    @Min(1) @Max(10)
    @Builder.Default
    private Integer difficultyScore = 1;
    @NotBlank(message = "Không được để trống vai trò của AI")
    private String aiRole;
    @NotBlank(message = "Không được để trống vai trò của học viên")
    private String learnerRole;

    private String roleplayContext;

    private String thumbnailUrl;

    @Builder.Default
    private Integer orderIndex = 0;

    /**
     * Optional missions to create atomically with this conversation.
     */
    @Valid
    private List<InlineMissionRequest> missions;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InlineMissionRequest {

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
}

package com.fu.conversation.dto.request;

import com.fu.course.entity.Course;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
public class UpdateConversationRequest {

    private UUID courseId;

    @NotBlank(message = "Title is required")
    @Size(max = 255)
    private String title;

    @Size(max = 255)
    private String titleJapanese;

    private String description;

    private String descriptionVi;

    private String scenario;

    private String vocabularyHints;

    private String grammarPoints;

    private String usefulExpressions;

    @Size(max = 100)
    private String category;

    @Min(1) @Max(10)
    private Integer difficultyScore;

    private String aiRole;

    private String learnerRole;

    private String roleplayContext;

    private String thumbnailUrl;

    private Integer orderIndex;
}

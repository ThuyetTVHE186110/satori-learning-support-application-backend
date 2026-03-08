package com.fu.conversation.dto.request;

import com.fu.course.entity.Course;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
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
public class UpdateTopicRequest {

    private UUID courseId;

    @Pattern(regexp = ".*\\S.*", message = "Title must not be blank")
    @Size(max = 255)
    private String title;

    @Size(max = 255)
    private String titleJapanese;

    private String description;

    private String scenario;

    private String vocabularyHints;

    private String grammarPoints;

    private String usefulExpressions;

    @Size(max = 100)
    private String category;

    @Min(1) @Max(10)
    private Integer difficultyScore;

    private Boolean isRoleplay;

    private String roleplayContext;

    private String thumbnailUrl;

    private Integer orderIndex;
}

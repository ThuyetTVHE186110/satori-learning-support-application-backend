package com.fu.conversation.dto.request;

import com.fu.course.entity.Course;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request to AI-generate topic suggestions under a theme.
 * Topics are course-specific: the course's vocabulary and grammar are used as context
 * to generate relevant, level-appropriate topics and missions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerateTopicRequest {

    @NotNull(message = "themeId is required")
    private UUID themeId;

    /**
     * Course whose vocabulary and grammar are used as AI context for generating topics.
     * Required — topics are course-specific.
     */
    @NotNull(message = "courseId is required")
    private UUID courseId;

    /**
     * Override JLPT level (defaults to course's JLPT level if omitted).
     */
    private Course.JlptLevel jlptLevel;

    @Builder.Default
    @Min(1) @Max(5)
    private Integer count = 3;
}

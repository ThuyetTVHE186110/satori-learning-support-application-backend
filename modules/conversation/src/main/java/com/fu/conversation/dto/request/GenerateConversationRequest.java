package com.fu.conversation.dto.request;

import com.fu.common.validator.SafeText;
import com.fu.course.entity.Course;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request to AI-generate conversation suggestions under a topic.
 * Conversations are course-specific: the course's vocabulary and grammar are used as context.
 * 
 * The title, description, and difficultyLevel provide guidance to the AI for generation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerateConversationRequest {

    @NotNull(message = "topicId is required")
    private UUID topicId;

    /**
     * Course whose vocabulary and grammar are used as AI context.
     */
    @NotNull(message = "courseId is required")
    private UUID courseId;

    /**
     * Conversation title/name (required, guides AI generation).
     */
    @NotBlank(message = "title is required")
    @SafeText
    private String title;

    /**
     * Conversation description (required, guides AI generation).
     */
    @NotBlank(message = "description is required")
    @SafeText
    private String description;

    /**
     * Difficulty level: 1=Easy (Dễ), 2=Medium (Trung bình), 3=Difficult (Khó).
     * Required and guides AI generation.
     */
    @NotNull(message = "difficultyLevel is required")
    @Min(value = 1, message = "difficultyLevel must be between 1 and 3")
    @Max(value = 3, message = "difficultyLevel must be between 1 and 3")
    private Integer difficultyLevel;

    @Builder.Default
    private Integer count = 1;
}

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
 * Request to AI-generate conversation suggestions under a topic.
 * Conversations are course-specific: the course's vocabulary and grammar are used as context.
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
     * Override JLPT level (defaults to topic's JLPT level if omitted).
     */
    private Course.JlptLevel jlptLevel;

    @Builder.Default
    @Min(1) @Max(5)
    private Integer count = 3;
}

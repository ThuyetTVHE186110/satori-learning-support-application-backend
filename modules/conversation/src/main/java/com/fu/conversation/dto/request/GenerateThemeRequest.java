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
 * Request to generate conversation theme suggestions using AI.
 * Manager selects a course and JLPT level; AI reads vocab/grammar and proposes themes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerateThemeRequest {

    @NotNull(message = "courseId is required")
    private UUID courseId;

    private Course.JlptLevel jlptLevel;

    /**
     * Number of theme suggestions to generate (1-5).
     */
    @Builder.Default
    @Min(1) @Max(5)
    private Integer count = 3;
}

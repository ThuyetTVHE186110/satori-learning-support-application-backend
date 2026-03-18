package com.fu.conversation.dto.request;

import com.fu.course.entity.Course;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to generate conversation theme suggestions using AI.
 * Themes are course-agnostic (shared across all courses).
 * Manager provides JLPT level and optional category/description hints.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerateThemeRequest {

    /**
     * Target JLPT level for the themes (optional — AI picks suitable level if omitted).
     */
    private Course.JlptLevel jlptLevel;

    /**
     * Category hint, e.g. "daily_life", "travel", "business", "school", "restaurant".
     * Optional — AI generates diverse categories if omitted.
     */
    @Size(max = 100)
    private String category;

    /**
     * Free-text hint to guide the AI (optional), e.g. "focus on polite speech situations".
     */
    @Size(max = 500)
    private String hint;

    /**
     * Number of theme suggestions to generate (1-5).
     */
    @Builder.Default
    @Min(1) @Max(5)
    private Integer count = 3;
}

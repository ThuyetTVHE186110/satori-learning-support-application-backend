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
 * Request to generate conversation topic suggestions using AI.
 * Topics are course-agnostic (shared across all courses).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerateTopicRequest {

    private Course.JlptLevel jlptLevel;

    @Size(max = 100)
    private String category;

    @Size(max = 500)
    private String hint;

    @Builder.Default
    @Min(1) @Max(5)
    private Integer count = 3;
}

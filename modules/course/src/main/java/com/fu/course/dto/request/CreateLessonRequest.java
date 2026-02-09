package com.fu.course.dto.request;

import com.fu.course.entity.Lesson.LessonType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Request DTO for creating a lesson within a course.
 * MANAGER only.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateLessonRequest {

    @NotNull(message = "Course ID is required")
    private UUID courseId;

    @NotBlank(message = "Lesson title is required")
    @Size(min = 3, max = 255, message = "Title must be between 3 and 255 characters")
    private String title;

    @Size(max = 255, message = "Japanese title must not exceed 255 characters")
    private String titleJapanese;

    @Size(max = 5000, message = "Description must not exceed 5000 characters")
    private String description;

    @NotNull(message = "Lesson type is required")
    private LessonType lessonType;

    /** JSONB content (flexible structure). */
    private String content;

    /** Learning objectives. */
    private List<String> objectives;

    /** File URL for uploaded curriculum content. */
    private String contentFileUrl;

    /** File name of uploaded curriculum content. */
    @Size(max = 255, message = "File name must not exceed 255 characters")
    private String contentFileName;

    /** Page range for imported curriculum. */
    private Integer pageStart;
    private Integer pageEnd;

    /** Display order within the course. */
    private Integer orderIndex;

    /** Estimated duration in minutes. */
    @Min(value = 1, message = "Duration must be at least 1 minute")
    private Integer estimatedDurationMinutes;
}

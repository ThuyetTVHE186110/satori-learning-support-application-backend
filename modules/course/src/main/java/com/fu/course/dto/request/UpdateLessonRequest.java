package com.fu.course.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request DTO for updating a lesson.
 * MANAGER only. Null fields are ignored (partial update).
 * lessonType and status cannot be changed through this DTO.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateLessonRequest {

    @Size(min = 3, max = 255, message = "Title must be between 3 and 255 characters")
    private String title;

    @Size(max = 255, message = "Japanese title must not exceed 255 characters")
    private String titleJapanese;

    @Size(max = 5000, message = "Description must not exceed 5000 characters")
    private String description;

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

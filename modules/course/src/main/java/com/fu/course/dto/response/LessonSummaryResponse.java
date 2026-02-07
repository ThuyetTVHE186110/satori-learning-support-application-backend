package com.fu.course.dto.response;

import com.fu.course.entity.Lesson.ContentStatus;
import com.fu.course.entity.Lesson.LessonType;
import com.fu.course.entity.Lesson.ProcessingStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Lightweight response DTO for lesson listings.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LessonSummaryResponse {

    private UUID id;
    private UUID courseId;

    private String title;
    private String titleJapanese;
    private LessonType lessonType;

    private Integer orderIndex;
    private Integer estimatedDurationMinutes;
    private ContentStatus status;
    private ProcessingStatus processingStatus;

    // Computed fields
    private Long vocabularyCount;
    private Long grammarPointCount;
}

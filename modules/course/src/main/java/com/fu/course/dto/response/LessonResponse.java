package com.fu.course.dto.response;

import com.fu.course.entity.Lesson.ContentStatus;
import com.fu.course.entity.Lesson.ProcessingStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Full response DTO for Lesson entity.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LessonResponse {

    private UUID id;
    private UUID courseId;
    private String courseName;

    private String title;
    private String titleJapanese;
    private String description;

    private String content;
    private List<String> objectives;

    private String contentFileUrl;
    private String contentFileName;
    private ProcessingStatus processingStatus;
    private LocalDateTime processedAt;
    private String processingError;

    private Integer pageStart;
    private Integer pageEnd;

    private Integer orderIndex;
    private Integer estimatedDurationMinutes;
    private ContentStatus status;

    private UUID createdBy;
    private UUID updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Computed fields
    private Long vocabularyCount;
    private Long grammarPointCount;
}

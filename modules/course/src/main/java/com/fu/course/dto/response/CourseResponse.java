package com.fu.course.dto.response;

import com.fu.course.entity.Course;
import com.fu.course.entity.Course.JlptLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for Course entity
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CourseResponse {

    private UUID id;
    private String name;
    private String description;
    private JlptLevel jlptLevel;
    private String thumbnailUrl;
    private Integer orderIndex;
    private Course.ContentStatus status;
    
    private UUID createdBy;
    private UUID updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    // Additional computed fields
    private Long lessonCount;
    private Long studentCount;
}

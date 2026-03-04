package com.fu.course.dto.response;

import com.fu.course.entity.Course.CourseStatus;
import com.fu.course.entity.Course.JlptLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Summary response for course list (lighter than full CourseResponse)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CourseSummaryResponse {

    private UUID id;
    private String name;
    private JlptLevel jlptLevel;
    private String thumbnailUrl;
    private CourseStatus status;
    private Integer orderIndex;
    private LocalDateTime createdAt;

    private Long lessonCount;
    private Long studentCount;
}

package com.fu.course.dto.request;

import com.fu.course.entity.Course;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for updating a course (MANAGER only).
 * All fields are optional — only non-null values are applied.
 * <p>
 * Status can be changed inline to ACTIVE or INACTIVE.
 * Setting status to DELETED is forbidden; use the delete endpoint instead.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateCourseRequest {

    @Size(min = 1, max = 100, message = "Course name must be between 1 and 100 characters")
    private String name;

    @Size(max = 5000, message = "Description must not exceed 5000 characters")
    private String description;

    private Integer orderIndex;

    /**
     * Course status: ACTIVE or INACTIVE.
     * DELETED is not allowed here — use DELETE /api/v1/manager/courses/{id} instead.
     */
    private Course.CourseStatus status;
}

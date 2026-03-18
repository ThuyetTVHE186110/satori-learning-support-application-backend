package com.fu.course.dto.request;

import com.fu.course.entity.Course.JlptLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for creating a course
 * MANAGER only
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateCourseRequest {

    @NotBlank(message = "Course name is required")
    @Size(min = 1, max = 100, message = "Course name must be between 1 and 100 characters")
    private String name;

    @Size(max = 5000, message = "Description must not exceed 5000 characters")
    private String description;

    @NotNull(message = "JLPT level is required")
    private JlptLevel jlptLevel;

    private Integer orderIndex;
}

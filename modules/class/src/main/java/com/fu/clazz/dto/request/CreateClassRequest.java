package com.fu.clazz.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Request DTO for creating a class
 * MANAGER only
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateClassRequest {

    @NotBlank(message = "Class name is required")
    @Size(min = 3, max = 255, message = "Class name must be between 3 and 255 characters")
    private String name;

    private String description;

    @NotNull(message = "Teacher ID is required")
    private UUID teacherId;

    @NotNull(message = "Course ID is required")
    private UUID courseId;
    @Builder.Default
    private Integer maxStudents = 8;

    private LocalDate startDate;
}

package com.fu.clazz.dto.request;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Request DTO for updating a class
 * MANAGER only - all fields optional (partial update)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateClassRequest {

    @Size(min = 3, max = 255, message = "Class name must be between 3 and 255 characters")
    private String name;

    @Size(max = 5000, message = "Description must not exceed 5000 characters")
    private String description;

    private Integer maxStudents;

    private LocalDate startDate;

    private LocalDate endDate;
}


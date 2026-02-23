package com.fu.clazz.dto.request;

import com.fu.clazz.entity.Clazz.JlptLevel;
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

    @Size(max = 5000, message = "Description must not exceed 5000 characters")
    private String description;

    @NotNull(message = "Teacher ID is required")
    private UUID teacherId;

    private JlptLevel jlptLevel;

    private Integer maxStudents;

    private LocalDate startDate;

    private LocalDate endDate;
}


package com.fu.clazz.dto.response;

import com.fu.clazz.entity.Clazz.JlptLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for Clazz entity (full detail)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassResponse {

    private UUID id;
    private String name;
    private String description;
    private String code;
    private UUID teacherId;
    private JlptLevel jlptLevel;
    private Integer maxStudents;
    private LocalDate startDate;
    private LocalDate endDate;
    private Boolean isActive;

    private UUID createdBy;
    private UUID updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Computed fields
    private Long memberCount;

    // Teacher info
    private String teacherName;
    private String teacherEmail;
    private String teacherAvatarUrl;
}

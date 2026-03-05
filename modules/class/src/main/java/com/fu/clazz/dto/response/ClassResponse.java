package com.fu.clazz.dto.response;

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
    private UUID teacherId;
    private UUID courseId;
    private String courseName;
    private String courseLevel; // e.g. "N5", "N4"
    private String courseStatus; // e.g. "PUBLISHED"
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
    private String teacherPhoneNumber;
}

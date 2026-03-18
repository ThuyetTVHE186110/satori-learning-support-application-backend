package com.fu.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO for learner's course enrollment history.
 * To be populated when course management module is available.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CourseEnrollmentResponse {
    
    private UUID enrollmentId;
    private UUID courseId;
    private String courseName;
    private String courseCode;
    private LocalDateTime enrolledAt;
    private String status; // ENROLLED, IN_PROGRESS, COMPLETED, DROPPED
    private Integer progress; // Percentage 0-100
    private Double grade;
}

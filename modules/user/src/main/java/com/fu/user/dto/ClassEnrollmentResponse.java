package com.fu.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO for class enrollment history (for both learner and teacher).
 * To be populated when class management module is available.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassEnrollmentResponse {
    
    private UUID classId;
    private String className;
    private String classCode;
    private UUID courseId;
    private String courseName;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private String status; // ACTIVE, COMPLETED, CANCELLED
    private String role; // For learner: STUDENT, for teacher: INSTRUCTOR
}

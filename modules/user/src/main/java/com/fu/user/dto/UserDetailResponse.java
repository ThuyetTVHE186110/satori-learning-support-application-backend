package com.fu.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Extended UserResponse with enrollment history.
 * Used for detailed user information view (UC-03).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDetailResponse {
    
    // Basic user information
    private UserResponse userInfo;
    
    // For LEARNER role
    private List<CourseEnrollmentResponse> courseEnrollments;
    private List<ClassEnrollmentResponse> classEnrollments;
    
    // For TEACHER role
    private List<ClassEnrollmentResponse> taughtClasses;
    
    // Statistics
    private Integer totalCourses;
    private Integer totalClasses;
    private Integer completedCourses;
    private Double averageGrade;
}

package com.fu.clazz.dto.response;

import com.fu.clazz.entity.ClassStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Preview data for a single class during import.
 * Contains all necessary information for creating or updating a class.
 * Used in both ImportClassesPreview (response) and ApproveImportClassesRequest (request).
 * 
 * For UPDATE classes, old/new comparison fields track what changed:
 * - oldClassName, oldDescription, etc. are populated from DB
 * - name, description, etc. are from Excel (new values)
 * 
 * Validation errors (teacher, course) prevent the class from being included in updates
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassPreview {
    // ===== Current/New values (from Excel) =====
    private String name;
    private String description;
    
    // Course info - both ID and name for preview
    private UUID courseId;
    private String courseName;         // e.g., "N5", "N4" (tên khóa học để hiển thị)
    
    // Teacher info - both ID and details for preview
    private UUID teacherId;
    private String teacherName;        // tên giáo viên để hiển thị
    private String teacherEmail;       // email giáo viên

    private LocalDate startDate;
    private LocalDate expectedEndDate;    // from Excel (new value)
    private ClassStatus status;
    
    // Capacity
    private Integer maxStudents;       // sức chứa lớp (default=8)
    
    // Learners with full details (not just UUIDs)
    private List<LearnerInfo> learnersToAdd;     // học viên sẽ được thêm vào lớp
    private List<LearnerInfo> learnersToRemove;  // học viên sẽ bị xóa khỏi lớp (UPDATE only — không có trong file)
    
    // ===== UPDATE classes: Old values from DB for comparison =====
    private String oldClassName;
    private String oldDescription;
    private LocalDate oldStartDate;
    private LocalDate oldExpectedEndDate;  // from DB (old value, UPDATE only)
    private ClassStatus oldStatus;
    private Integer oldMaxStudents;
    private UUID oldCourseId;
    private String oldCourseName;          // course name from DB for UPDATE preview
    private String oldTeacherEmail;
    private String oldTeacherFullName;     // full name from DB for UPDATE validation

    // ===== Validation errors (non-null = validation failed) =====
    // If set, class will NOT be included in updatedClasses
    private String teacherValidationError;
    private String courseValidationError;
    private String teacherInfoValidationError;  // mismatch between Excel and DB teacher info
}

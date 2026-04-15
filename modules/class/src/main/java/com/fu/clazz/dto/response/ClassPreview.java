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
 * Contains both IDs (for DB operations) and display names (for preview UI)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassPreview {
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
    private ClassStatus status;
    
    // Capacity
    private Integer maxStudents;       // sức chứa lớp (default=8)
    
    // Learners with full details (not just UUIDs)
    private List<LearnerInfo> learnersToAdd;  // danh sách học viên với email, tên, sinh nhật, điện thoại
}

package com.fu.clazz.dto.response;

import com.fu.clazz.entity.ClassStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Response DTO for teacher's class history.
 * Returns basic class information without assignment history dates.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeacherClassHistoryResponse {

    private UUID id;
    private String name;
    private String description;
    private UUID courseId;
    private String courseName;
    private String courseLevel;
    private ClassStatus status;
    private Integer maxStudents;
    private LocalDate startDate;
    private LocalDate endDate;
}

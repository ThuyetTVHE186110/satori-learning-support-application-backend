package com.fu.monitoring.dto.response;

import com.fu.clazz.entity.ClassStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A single class entry in the priority classes table.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriorityClassResponse {
    private UUID id;
    private String name;
    private String courseName;
    private Integer enrolledCount;
    private Integer maxStudents;
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalDate expectedEndDate;
    private ClassStatus status;
    /** Reasons this class is flagged: OVERDUE, ENDING_SOON, STARTING_SOON, INCOMPLETE */
    private List<String> reasons;
    /** CRITICAL, HIGH, MEDIUM, LOW */
    private String priorityLevel;
}

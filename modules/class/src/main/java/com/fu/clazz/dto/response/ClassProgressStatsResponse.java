package com.fu.clazz.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Response DTO for class-level progress statistics.
 * Contains enrollment info and aggregated metrics for the class.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassProgressStatsResponse {

    /**
     * Total number of active learners (not removed from class)
     */
    private Long totalLearners;

    /**
     * Maximum number of students allowed in this class
     */
    private Integer maxStudents;

    /**
     * Class enrollment percentage: (totalLearners / maxStudents) * 100
     */
    private Double enrollmentPercentage;

    /**
     * Class-level progress: (lessons with assignments / total course lessons) * 100
     */
    private Double classProgressPercentage;

    /**
     * Average score across all learners in class from graded assignments
     * Scale 0-10, null if no graded submissions exist
     */
    private BigDecimal averageScore;

    /**
     * Total number of assignments in this class
     */
    private Long totalAssignments;

    /**
     * Number of lessons with at least one assignment assigned in this class
     */
    private Long lessonsWithAssignments;

    /**
     * Total number of lessons in the course
     */
    private Long totalLessonsInCourse;
}

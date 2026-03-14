package com.fu.assignment.dto.response;

import lombok.*;

import java.util.UUID;

/**
 * Statistics for assignments in a specific class.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassAssignmentStatisticsResponse {
    private UUID classId;
    private long totalAssignments;
    private long draftAssignments;
    private long publishedAssignments;
    private long closedAssignments;
    private long totalSubmissions;
    private long ungradedSubmissions;
    private long gradedSubmissions;
    private long inProgressSubmissions;
    private long overdueSubmissions;
}

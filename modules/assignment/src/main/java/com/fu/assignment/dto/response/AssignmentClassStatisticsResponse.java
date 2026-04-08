package com.fu.assignment.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssignmentClassStatisticsResponse {

    private UUID assignmentId;
    private UUID classId;
    private String assignmentTitle;
    private long totalStudents;
    private long submittedCount;
    private long notSubmittedCount;
    private double averageScore;

}

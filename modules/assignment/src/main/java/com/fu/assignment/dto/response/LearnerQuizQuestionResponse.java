package com.fu.assignment.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fu.course.entity.Course;
import lombok.*;

import java.util.UUID;

/**
 * Learner-safe version of AssignmentQuestionResponse.
 * Excludes correct answers and explanations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LearnerQuizQuestionResponse {

    private UUID assignmentQuestionId;
    private Integer orderIndex;
    private Integer points;

    private UUID questionId;
    private String questionText;
    private String questionType;
    private JsonNode options;
    private Course.JlptLevel jlptLevel;
}

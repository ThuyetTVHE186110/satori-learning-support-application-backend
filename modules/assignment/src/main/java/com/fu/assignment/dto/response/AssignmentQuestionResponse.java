package com.fu.assignment.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fu.course.entity.Course;
import lombok.*;

import java.util.UUID;

/**
 * Response DTO for a question within an assignment (with points and order).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssignmentQuestionResponse {

    // Junction fields
    private UUID assignmentQuestionId;
    private Integer orderIndex;
    private Integer points;

    // Question fields
    private UUID questionId;
    private String questionText;
    private String questionJapanese;
    private String questionType;
    private JsonNode options;          // JSON tree
    private String explanation;
    private String explanationVi;
    private String imageUrl;
    private Course.JlptLevel jlptLevel;
    private Integer difficultyScore;

    // NOTE: correctAnswer / correctAnswers are NOT returned to learners
    // They are only included when showCorrectAnswers=true and submission is graded
    private String correctAnswer;
    private JsonNode correctAnswers;
}

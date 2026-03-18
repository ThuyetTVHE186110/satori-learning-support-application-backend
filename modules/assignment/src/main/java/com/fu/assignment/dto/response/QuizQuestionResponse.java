package com.fu.assignment.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fu.course.entity.Course;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for a QuizQuestion in the question bank.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuizQuestionResponse {

    private UUID id;
    private String questionText;
    private String questionType;
    private JsonNode options;
    private String correctAnswer;
    private JsonNode correctAnswers;
    private String explanation;

    private UUID vocabularyId;
    private UUID grammarId;
    private UUID lessonId;
    private Course.JlptLevel jlptLevel;
    private String[] tags;
    private String imageUrl;
    private UUID createdBy;
    private LocalDateTime createdAt;
}

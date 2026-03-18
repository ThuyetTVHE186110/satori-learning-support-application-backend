package com.fu.assignment.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.*;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuizSubmissionDetailResponse {
    private UUID questionId;
    private String questionText;
    private String questionType;
    private String selectedAnswer;
    private String correctAnswer;
    private String explanation;
    private boolean isCorrect;
    private JsonNode options;
}

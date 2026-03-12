package com.fu.assignment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

/**
 * Request DTO for creating a QuizQuestion.
 * Used by MANAGER to add questions to the question bank.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateQuizQuestionRequest {

    @NotBlank(message = "Question text is required")
    private String questionText;

    private String questionJapanese;

    @NotBlank(message = "Question type is required")
    private String questionType; // 'multiple_choice', 'fill_blank', 'matching', 'true_false', 'ordering'

    /**
     * JSON string for options.
     * Example: [{"id":"A","text":"食べる","isCorrect":true}, ...]
     */
    private String options;

    /** For fill_blank, true_false */
    private String correctAnswer;

    /** For matching, ordering — JSON string */
    private String correctAnswers;

    private String explanation;

    private String explanationVi;

    private java.util.UUID vocabularyId;
    private java.util.UUID grammarId;
    private java.util.UUID lessonId;

    private com.fu.course.entity.Course.JlptLevel jlptLevel;

    @Builder.Default
    private Integer difficultyScore = 1;

    private String[] tags;

    private String imageUrl;
}

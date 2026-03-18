package com.fu.assignment.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO to ask AI for an explanation based on draft question data from the frontend form.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiExplanationRequest {
    
    @NotBlank(message = "Question text is required to generate an explanation")
    private String questionText;
    
    @NotBlank(message = "Question type is required")
    private String questionType;
    
    /** JSON string representation of options */
    private String options;
    
    /** The correct answer text (for fill blank/true false) */
    private String correctAnswer;
    
    /** JSON string representation of correct answers (for matching/ordering) */
    private String correctAnswers;
}

package com.fu.assignment.dto.response;

import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for WritingAssignment content.
 * Returned when teacher views the writing/translation content of an assignment.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WritingAssignmentResponse {

    private UUID id;
    private UUID assignmentId;
    private String prompt;
    private String promptJapanese;
    private String sampleAnswer;          // Hidden from learner before grading
    private String sampleAnswerJapanese;
    private Integer minWords;
    private Integer maxWords;
    private String sourceText;            // For TRANSLATION: the passage to translate
    private String translationDirection;  // 'JA_TO_VI' or 'VI_TO_JA'
    private LocalDateTime createdAt;
}

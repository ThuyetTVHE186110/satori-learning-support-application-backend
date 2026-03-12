package com.fu.assignment.dto.request;

import com.fu.course.entity.Course;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Represents a single question draft sent from the frontend building form.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuizQuestionDraftDto {

    /** 
     * If null, this is a newly created question on the form.
     * If set, this is an existing question being updated/kept.
     */
    private UUID id;

    @NotBlank(message = "Question text is required")
    private String questionText;

    private String questionJapanese;

    @NotBlank(message = "Question type is required")
    private String questionType;

    /** JSON string representation of options */
    private String options;

    /** Correct answer for fill-blank/true-false */
    private String correctAnswer;

    /** JSON string for matching/ordering correct answers */
    private String correctAnswers;



    private String explanation;
    private String explanationVi;

    private String imageUrl;

    private Course.JlptLevel jlptLevel;
    
    @Builder.Default
    private Integer difficultyScore = 1;

    // ── Mapping fields for the Assignment ──────────────────────

    @Builder.Default
    private Integer points = 10;

    @Builder.Default
    private Integer orderIndex = 0;
}

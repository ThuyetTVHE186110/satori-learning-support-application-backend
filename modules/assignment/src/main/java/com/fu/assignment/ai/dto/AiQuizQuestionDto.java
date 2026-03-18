package com.fu.assignment.ai.dto;

import java.util.List;

/**
 * Minimal DTO that AI must produce for each quiz question.
 * <p>
 * Only contains fields that require AI "thinking" — all metadata
 * (status, timestamps, teacherId, jlptLevel, aiGenerated, etc.)
 * are set by code after receiving the result.
 * <p>
 * Used with Spring AI's {@code BeanOutputConverter} for automatic
 * JSON schema injection → guarantees valid output format.
 */
import java.util.Map;
public record AiQuizQuestionDto(
        /** Question text in Vietnamese or English */
        String questionText,


        /** Question type: "multiple_choice", "fill_blank", "true_false", "matching", "ordering" */
        String questionType,

        /**
         * The exact JSON object for options, matching the DB schema.
         * Example for multiple_choice: {"type": "multiple_choice", "options": [...], "shuffle": true}
         * Example for matching: {"type": "matching", "left_items": [...], "right_items": [...]}
         * Null or empty object for fill_blank and true_false.
         */
        Map<String, Object> options,

        /** Correct answer text — used for single-answer questions (e.g., "A", "True", or the filled word) */
        String correctAnswer,

        /** Correct answers JSON object for matching/multi-select — e.g., {"pairs": [{"left": "1", "right": "A"}]} */
        Map<String, Object> correctAnswers,

        /** Brief explanation of the correct answer (1-2 sentences, Vietnamese) */
        String explanation


) {}

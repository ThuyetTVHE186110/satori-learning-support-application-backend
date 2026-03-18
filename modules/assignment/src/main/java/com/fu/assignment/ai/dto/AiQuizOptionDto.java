package com.fu.assignment.ai.dto;

/**
 * Represents a single option in a multiple-choice quiz question.
 * Used by AI to return structured option data.
 */
public record AiQuizOptionDto(
        /** The display text of this option (e.g., "食べる" or "to eat") */
        String text,

        /** The reading of the text if it contains Kanji (e.g., "たべる"). Can be null. */
        @com.fasterxml.jackson.annotation.JsonProperty("text_reading")
        String textReading
) {}

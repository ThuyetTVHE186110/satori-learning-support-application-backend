package com.fu.conversation.dto.response;

import com.fu.course.entity.Course;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * AI-generated theme suggestion (not persisted yet - manager reviews and saves).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeneratedThemeSuggestionResponse {

    private String title;
    private String titleJapanese;
    private String description;
    private String category;
    private Course.JlptLevel jlptLevel;

    /**
     * Suggested vocabulary hints from the course.
     */
    private List<String> vocabularyHints;

    /**
     * Suggested grammar points from the course.
     */
    private List<String> grammarPoints;

    /**
     * Suggested conversation expressions.
     */
    private List<String> usefulExpressions;

    /**
     * Example sub-topic scenarios under this theme.
     */
    private List<String> scenarioSuggestions;
}

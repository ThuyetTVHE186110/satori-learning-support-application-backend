package com.fu.assignment.ai.dto;

import java.util.List;

/**
 * Wrapper record for AI quiz generation response.
 * <p>
 * Spring AI's {@code BeanOutputConverter} uses this to generate
 * a JSON schema that the AI must follow. The wrapper ensures the
 * AI returns a structured object with a "questions" array.
 */
public record AiQuizResponseWrapper(
        List<AiQuizQuestionDto> questions
) {}


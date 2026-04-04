package com.fu.assignment.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fu.assignment.AssignmentTestApplication;
import com.fu.assignment.dto.request.AiQuizGenerationRequest;
import com.fu.assignment.dto.response.QuizQuestionResponse;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Response Schema Validation for AI-generated quiz questions.
 * 
 * Purpose: Validate that AI responses match expected contract
 * - Not testing AI logic (done in QuizAiGenerationServiceTest)
 * - Not testing UI rendering (done in System tests)
 * - ONLY testing: Response schema matches requirements
 * 
 * This validates against the actual OpenAI API response when enabled.
 * 
 * Prerequisites to run this test:
 * 1. PostgreSQL database: satori_test (initialized with init-test-db.sql)
 * 2. Redis running on localhost:6379 (optional for cache, auto-disabled in test profile)
 * 3. OpenAI API Key via environment variable: set OPENAI_API_KEY=sk-...
 * 
 * Test Behavior:
 * - WITH valid API key: Tests run and call REAL OpenAI API (costs credits)
 * - WITHOUT API key: Tests are SKIPPED (not failed)
 * 
 * Setup:
 *   1. Create test database:
 *      docker exec satori-postgres psql -U satori_user -d satori_learning \
 *        -c "CREATE DATABASE satori_test OWNER satori_user;"
 *
 *   2. Initialize schema:
 *      docker exec -i satori-postgres psql -U satori_user -d satori_test \
 *        < start/src/test/resources/init-test-db.sql
 *
 *   3. Set OpenAI API key (Linux/Mac):
 *      export OPENAI_API_KEY=sk-your-actual-key-here
 *
 *   4. Or in PowerShell (Windows):
 *      $env:OPENAI_API_KEY="sk-your-actual-key-here"
 *
 *   5. Run tests:
 *      mvn -pl modules/assignment -am test -Dtest=AiQuizResponseSchemaValidationTest
 */
@Slf4j
@SpringBootTest(classes = AssignmentTestApplication.class)
@ActiveProfiles("test")
@EnabledIfOpenAiKeyExists
@DisplayName("AI Response Schema Contract Tests")
class AiQuizResponseSchemaValidationTest {

    @Autowired
    private QuizAiGenerationService quizAiGenerationService;

    @Autowired
    private ObjectMapper objectMapper;

    private AiQuizGenerationRequest testRequest;

    @BeforeEach
    void setUp() {
        testRequest = AiQuizGenerationRequest.builder()
                .teacherPrompt("Tạo câu hỏi trắc nghiệm về Kanji bài 1 N5")
                .questionCount(2)
                .questionTypes(List.of("multiple_choice"))
                .build();
    }

    @Nested
    @DisplayName("Response Schema Validation")
    class ResponseSchemaValidation {

        /**
         * Test: Verify AI-generated questions have all required fields
         * 
         * Contract:
         * - questionText: String (not null, not empty)
         * - options: JsonNode array (not empty)
         * - correctAnswer: String (exists in options)
         * - explanation: String (not empty - must have reasoning)
         * - questionType: String (one of: multiple_choice, fill_blank, matching)
         */
        @Test
        @DisplayName("Contract: All required fields present and valid")
        void validateRequiredFieldsContract() {
            List<QuizQuestionResponse> responses = quizAiGenerationService.generateQuestions(testRequest);

            assertThat(responses)
                    .isNotEmpty()
                    .allSatisfy(question -> {
                        // Field 1: questionText
                        assertThat(question.getQuestionText())
                                .as("questionText should not be null or empty")
                                .isNotNull()
                                .isNotBlank()
                                .as("questionText should have reasonable length (> 5 chars)")
                                .hasSizeGreaterThan(5);

                        // Field 2: options (must be valid JSON array)
                        assertThat(question.getOptions())
                                .as("options should not be null")
                                .isNotNull();
                        
                        JsonNode optionsNode = question.getOptions();
                        assertThat(optionsNode.isArray())
                                .as("options should be a JSON array")
                                .isTrue();

                        // Validate options is parseable JSON
                        assertThatCode(() -> {
                            JsonNode optionsJson = question.getOptions();
                            assertThat(optionsJson)
                                    .as("options should be a JSON array")
                                    .isNotNull();
                            int optionCount = 0;
                            for (@SuppressWarnings("unused") JsonNode ignored : optionsJson) {
                                optionCount++;
                            }
                            assertThat(optionCount)
                                    .as("options array should not be empty")
                                    .isGreaterThan(0)
                                    .as("options should have at least 2 choices")
                                    .isGreaterThanOrEqualTo(2);
                        }).as("options should be valid JSON").doesNotThrowAnyException();

                        // Field 3: correctAnswer
                        assertThat(question.getCorrectAnswer())
                                .as("correctAnswer should not be null")
                                .isNotNull()
                                .as("correctAnswer should not be empty")
                                .isNotBlank()
                                .as("correctAnswer should be single character (A, B, C, etc.) or number")
                                .matches("[A-D0-9]|answer_\\d+");

                        // Validate correctAnswer exists in options
                        assertThatCode(() -> {
                            JsonNode optionsJson = question.getOptions();
                            boolean answerExists = false;
                            for (JsonNode option : optionsJson) {
                                String optionId = option.get("id").asText();
                                if (optionId.equals(question.getCorrectAnswer())) {
                                    answerExists = true;
                                    break;
                                }
                            }
                            assertThat(answerExists)
                                    .as("correctAnswer '%s' should exist in options", question.getCorrectAnswer())
                                    .isTrue();
                        }).doesNotThrowAnyException();

                        // Field 4: explanation
                        assertThat(question.getExplanation())
                                .as("explanation should not be null")
                                .isNotNull()
                                .as("explanation should not be empty")
                                .isNotBlank()
                                .as("explanation should have sufficient detail (> 20 chars)")
                                .hasSizeGreaterThan(20);

                        // Field 5: questionType
                        assertThat(question.getQuestionType())
                                .as("questionType should match request types")
                                .isNotNull()
                                .isIn("multiple_choice", "fill_blank", "matching");
                    });
        }

        /**
         * Test: Verify Furigana format is correct (if furigana present)
         * 
         * Requirement:
         * - Kanji should be wrapped in <ruby> tags (not parentheses)
         * - Format: 日本 → <ruby>にほん</ruby>
         * - Exception: If no Kanji in text, ruby tags not required
         */
        @Test
        @DisplayName("Contract: Furigana format correct when Kanji present")
        void validateFuriganaFormatContract() {
            List<QuizQuestionResponse> responses = quizAiGenerationService.generateQuestions(testRequest);

            responses.forEach(question -> {
                String questionText = question.getQuestionText();
                
                // Check if formatted text contains ruby tags (indicating furigana)
                if (questionText.contains("<ruby>")) {
                    log.info("Question with Furigana detected: {}", questionText);
                    
                    // If ruby tags present, verify format is correct
                    assertThat(questionText)
                            .as("If furigana present, should use proper <ruby> tag format")
                            .matches(".*<ruby>.*</ruby>.*");
                    
                    // Should NOT use old format (parentheses)
                    assertThat(questionText)
                            .as("questionText should not use old format like 日本(にほん)")
                            .doesNotMatch("\\p{Lo}+\\([ぁ-ん]+\\)");
                }
            });
        }

        /**
         * Test: Verify data type consistency
         * 
         * Contract:
         * - correctAnswer should be string type (not number)
         * - score calculations should work correctly
         */
        @Test
        @DisplayName("Contract: Data types are consistent")
        void validateDataTypeContract() {
            List<QuizQuestionResponse> responses = quizAiGenerationService.generateQuestions(testRequest);

            responses.forEach(question -> {
                // correctAnswer must be string for comparison
                String correctAnswer = question.getCorrectAnswer();
                assertThat(correctAnswer)
                        .as("correctAnswer must be String type")
                        .isInstanceOf(String.class);

                // Should be usable in string comparisons (e.g., .equals("A"))
                assertThat(correctAnswer).isNotNull();
                assertThat(correctAnswer.equals("A") || correctAnswer.equals("B") 
                        || correctAnswer.equals("C") || correctAnswer.equals("D"))
                        .as("correctAnswer should be comparable as string")
                        .isTrue();
            });
        }

        /**
         * Test: Verify no unexpected fields or breaking changes
         * 
         * This catches when AI or API adds/removes fields unexpectedly
         */
        @Test
        @DisplayName("Contract: Expected fields present (breaking change detection)")
        void validateFieldPresenceForBreakingChanges() {
            List<QuizQuestionResponse> responses = quizAiGenerationService.generateQuestions(testRequest);

            assertThat(responses)
                    .isNotEmpty()
                    .allSatisfy(question -> {
                        // These must always exist (breaking change if missing)
                        assertThatCode(() -> {
                            assertThat(question.getQuestionText()).isNotNull();
                            assertThat(question.getOptions()).isNotNull();
                            assertThat(question.getCorrectAnswer()).isNotNull();
                            assertThat(question.getExplanation()).isNotNull();
                            assertThat(question.getQuestionType()).isNotNull();
                        }).as("No required field should be removed").doesNotThrowAnyException();
                    });
        }

        /**
         * Test: Edge case - Very long explanations
         * 
         * Verify system handles AI responses with lengthy explanations correctly
         */
        @Test
        @DisplayName("Contract: Handles edge case - long explanations")
        void validateLongExplanationEdgeCase() {
            List<QuizQuestionResponse> responses = quizAiGenerationService.generateQuestions(
                    AiQuizGenerationRequest.builder()
                            .teacherPrompt("Tạo câu hỏi phức tạp với giải thích chi tiết")
                            .questionCount(1)
                            .questionTypes(List.of("multiple_choice"))
                            .build()
            );

            responses.forEach(question -> {
                // Should handle long explanations (e.g., 200+ chars)
                assertThat(question.getExplanation())
                        .isNotNull()
                        .isNotBlank();
                
                // Should be storable in database (typical max 4000 chars for text field)
                assertThat(question.getExplanation().length())
                        .as("Explanation should fit in database field")
                        .isLessThan(4000);
            });
        }

        /**
         * Test: Verify no HTML injection in explanation
         * 
         * Contract: User-facing content should be safe
         */
        @Test
        @DisplayName("Contract: No XSS/injection in explanation")
        void validateNoXssInjectionContract() {
            List<QuizQuestionResponse> responses = quizAiGenerationService.generateQuestions(testRequest);

            responses.forEach(question -> {
                String explanation = question.getExplanation();
                
                // Should not have unescaped dangerous HTML
                assertThat(explanation)
                        .as("Explanation should not have dangerous JavaScript")
                        .doesNotContain("<script>", "javascript:", "onerror=", "onclick=");
            });
        }
    }

    @Nested
    @DisplayName("Error Case Schema Validation")
    class ErrorCaseSchemaValidation {

        /**
         * Test: When AI response is incomplete/truncated
         * 
         * Contract: System should either:
         * - Return only complete questions, or
         * - Throw clear BusinessException
         */
        @Test
        @DisplayName("Contract: Handles incomplete AI response gracefully")
        void validateIncompleteResponseHandling() {
            AiQuizGenerationRequest request = AiQuizGenerationRequest.builder()
                    .teacherPrompt("Bài 1")
                    .questionCount(10)  // High count to potentially trigger truncation
                    .questionTypes(List.of("multiple_choice"))
                    .build();

            // Should either return valid questions or throw exception
            try {
                List<QuizQuestionResponse> responses = quizAiGenerationService.generateQuestions(request);
                
                // If we get responses, they should all be valid
                responses.forEach(q -> {
                    assertThat(q.getQuestionText()).isNotBlank();
                    assertThat(q.getExplanation()).isNotBlank();
                });
            } catch (Exception e) {
                // Exception is acceptable (incomplete response)
                log.debug("Incomplete response handled: {}", e.getMessage());
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // End of Test Class
    // ═════════════════════════════════════════════════════════════════════════════
}






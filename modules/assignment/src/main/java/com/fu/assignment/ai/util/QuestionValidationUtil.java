package com.fu.assignment.ai.util;

import com.fu.assignment.ai.dto.AiQuizQuestionDto;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.regex.Pattern;

@Slf4j
public class QuestionValidationUtil {

    // Blocking validation rules (question fails if any violated)
    private static final List<String> BLOCKING_ERRORS = new ArrayList<>();
    
    // Advisory validation rules (logged as warnings but question still passes)
    private static final List<String> ADVISORY_WARNINGS = new ArrayList<>();

    /**
     * Main validation entry point - checks question against all rules
     */
    public static ValidationResult validate(AiQuizQuestionDto question) {
        BLOCKING_ERRORS.clear();
        ADVISORY_WARNINGS.clear();

        // Blocking validations (question fails if ANY violated)
        validateAnswerFormat(question);
        validateAnswerMatchesOption(question);
        validateFuriganaFormat(question);
        validateNoHtmlTags(question);
        validateOptionsUnique(question);
        validateTypeContentMatch(question);
        validateExplanationNotEmpty(question);
        
        // Advisory validations (logged but don't fail question)
        validateQuestionTextNotTooShort(question);
        validateOptionCount(question);

        boolean isValid = BLOCKING_ERRORS.isEmpty();
        return new ValidationResult(isValid, BLOCKING_ERRORS, ADVISORY_WARNINGS);
    }

    // ========== BLOCKING VALIDATION RULES ==========

    /**
     * Rule 1: correctAnswer must be a single character ID (A, B, C, D, etc.)
     */
    private static void validateAnswerFormat(AiQuizQuestionDto question) {
        String answer = question.correctAnswer();
        if (answer == null || answer.trim().isEmpty()) {
            BLOCKING_ERRORS.add("correctAnswer is empty or null");
            return;
        }
        
        // Should be single character A-D (or more if supporting >4 options)
        if (!answer.matches("^[A-Z]$")) {
            BLOCKING_ERRORS.add("correctAnswer must be single letter ID (A, B, C, D...), got: " + answer);
        }
        
        // Check it doesn't contain Japanese/spaces (would indicate full text, not ID)
        if (answer.contains("ですか") || answer.contains("です") || answer.contains("ました") ||
            answer.contains("[") || answer.contains("{") || answer.contains(" ")) {
            BLOCKING_ERRORS.add("correctAnswer should be ID only (A/B/C/D), not full text: " + answer);
        }
    }

    /**
     * Rule 2: correctAnswer ID must exist in options
     */
    private static void validateAnswerMatchesOption(AiQuizQuestionDto question) {
        if (question.options() == null || question.options().isEmpty()) {
            BLOCKING_ERRORS.add("No options found in question");
            return;
        }

        String answerId = question.correctAnswer();
        if (answerId == null) return;

        // Extract options array from the Map
        Object optionsObj = question.options().get("options");
        if (!(optionsObj instanceof List)) {
            ADVISORY_WARNINGS.add("Could not verify answer ID due to options structure");
            return;
        }

        List<?> optionsList = (List<?>) optionsObj;
        boolean found = false;
        
        for (Object opt : optionsList) {
            if (opt instanceof Map) {
                Map<?, ?> optMap = (Map<?, ?>) opt;
                if (answerId.equals(optMap.get("id"))) {
                    found = true;
                    break;
                }
            }
        }

        if (!found) {
            BLOCKING_ERRORS.add("correctAnswer ID '" + answerId + "' does not exist in options");
        }
    }

    /**
     * Rule 3: Furigana format must be [Kanji]{lb}Reading{rb}, not [Kanji]{Reading} or [Kanji]Reading
     */
    private static void validateFuriganaFormat(AiQuizQuestionDto question) {
        String questionText = question.questionText();
        
        // Check for incorrect patterns: [X]{reading} without {lb}/{rb}
        if (questionText.matches(".*\\[.*\\]\\{[^lb].*\\}.*") || 
            questionText.matches(".*\\[.*\\]\\{[lb].*[^rb]\\}.*")) {
            // Has brackets but malformed lb/rb
            if (questionText.matches(".*\\[.*\\]\\{[^}]*rb[^}]*\\}.*") && 
                !questionText.matches(".*\\[.*\\]\\{lb.*rb\\}.*")) {
                BLOCKING_ERRORS.add("Furigana format incorrect - use [Kanji]{lb}Reading{rb}, found: " + 
                    extractBadFurigana(questionText));
            }
        }

        // Check options
        if (question.options() != null && question.options().containsKey("options")) {
            Object optionsObj = question.options().get("options");
            if (optionsObj instanceof List) {
                for (Object opt : (List<?>) optionsObj) {
                    if (opt instanceof Map) {
                        Map<?, ?> optMap = (Map<?, ?>) opt;
                        Object text = optMap.get("text");
                        if (text instanceof String) {
                            String optText = (String) text;
                            if (optText.matches(".*\\[.*\\]\\{[^}]*\\}.*")) {
                                // Has furigana - check format
                                if (!optText.matches(".*\\[.*\\]\\{lb.*rb\\}.*")) {
                                    BLOCKING_ERRORS.add("Option " + optMap.get("id") + 
                                        " has incorrect furigana format: " + optText);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Rule 4: No HTML tags allowed (<ruby>, <span>, <br>, etc.)
     */
    private static void validateNoHtmlTags(AiQuizQuestionDto question) {
        String questionText = question.questionText();
        if (questionText != null && questionText.matches(".*<[a-zA-Z]+>.*")) {
            BLOCKING_ERRORS.add("HTML tags not allowed in questionText: " + questionText);
        }

        if (question.options() != null && question.options().containsKey("options")) {
            Object optionsObj = question.options().get("options");
            if (optionsObj instanceof List) {
                for (Object opt : (List<?>) optionsObj) {
                    if (opt instanceof Map) {
                        Map<?, ?> optMap = (Map<?, ?>) opt;
                        Object text = optMap.get("text");
                        if (text instanceof String && ((String) text).matches(".*<[a-zA-Z]+>.*")) {
                            BLOCKING_ERRORS.add("HTML tags not allowed in option " + optMap.get("id"));
                        }
                    }
                }
            }
        }

        String explanation = question.explanation();
        if (explanation != null && explanation.matches(".*<[a-zA-Z]+>.*")) {
            BLOCKING_ERRORS.add("HTML tags not allowed in explanation");
        }
    }

    /**
     * Rule 5: All options must be unique (no duplicates)
     */
    private static void validateOptionsUnique(AiQuizQuestionDto question) {
        if (question.options() == null || !question.options().containsKey("options")) {
            return;
        }

        Object optionsObj = question.options().get("options");
        if (!(optionsObj instanceof List)) {
            return;
        }

        Set<String> optionTexts = new HashSet<>();
        for (Object opt : (List<?>) optionsObj) {
            if (opt instanceof Map) {
                Map<?, ?> optMap = (Map<?, ?>) opt;
                Object text = optMap.get("text");
                if (text instanceof String) {
                    String optText = (String) text;
                    if (optionTexts.contains(optText)) {
                        BLOCKING_ERRORS.add("Duplicate option found: " + optText);
                        return;
                    }
                    optionTexts.add(optText);
                }
            }
        }
    }

    /**
     * Rule 6: Question type must match content pattern
     * - multiple_choice: should NOT have ____ blank
     * - fill_blank: MUST have exactly 1 ____ blank
     */
    private static void validateTypeContentMatch(AiQuizQuestionDto question) {
        String type = question.questionType();
        String text = question.questionText();

        if ("multiple_choice".equals(type)) {
            // MC should NOT have blanks
            if (text.contains("____")) {
                BLOCKING_ERRORS.add("multiple_choice should not have ____ blank - use fill_blank instead");
            }
            // MC should be a complete question
            if (!text.contains("？") && !text.contains("?")) {
                ADVISORY_WARNINGS.add("multiple_choice questionText should be a question (end with ？)");
            }
        } else if ("fill_blank".equals(type)) {
            // FB must have exactly 1 blank
            int blankCount = countOccurrences(text, "____");
            if (blankCount == 0) {
                BLOCKING_ERRORS.add("fill_blank must have ____ placeholder");
            } else if (blankCount > 1) {
                ADVISORY_WARNINGS.add("fill_blank has " + blankCount + " blanks (typically should be 1)");
            }
        }
    }

    /**
     * Rule 7: Explanation must not be empty
     */
    private static void validateExplanationNotEmpty(AiQuizQuestionDto question) {
        String explanation = question.explanation();
        if (explanation == null || explanation.trim().isEmpty()) {
            BLOCKING_ERRORS.add("explanation must not be empty");
        } else if (explanation.length() < 5) {
            BLOCKING_ERRORS.add("explanation too short (< 5 chars): " + explanation);
        }
    }

    // ========== ADVISORY VALIDATION RULES ==========

    /**
     * Advisory Rule 1: Question text should not be too short
     */
    private static void validateQuestionTextNotTooShort(AiQuizQuestionDto question) {
        String text = question.questionText();
        if (text != null && text.length() < 10) {
            ADVISORY_WARNINGS.add("questionText is very short: " + text);
        }
    }

    /**
     * Advisory Rule 2: Option count should typically be 4
     */
    private static void validateOptionCount(AiQuizQuestionDto question) {
        if (question.options() != null && question.options().containsKey("options")) {
            Object optionsObj = question.options().get("options");
            if (optionsObj instanceof List) {
                int count = ((List<?>) optionsObj).size();
                if (count != 4) {
                    ADVISORY_WARNINGS.add("Expected 4 options, got " + count);
                }
            }
        }
    }

    // ========== HELPER METHODS ==========

    private static int countOccurrences(String text, String pattern) {
        if (text == null) return 0;
        return (int) text.split(Pattern.quote(pattern)).length - 1;
    }

    private static String extractBadFurigana(String text) {
        // Extract first bad furigana pattern for logging
        Pattern p = Pattern.compile("\\[.*?\\]\\{[^}]*\\}");
        java.util.regex.Matcher m = p.matcher(text);
        if (m.find()) {
            return m.group();
        }
        return "unknown";
    }

    // ========== VALIDATION RESULT CLASS ==========

    public static class ValidationResult {
        private final boolean valid;
        private final List<String> errors;
        private final List<String> warnings;

        public ValidationResult(boolean valid, List<String> errors, List<String> warnings) {
            this.valid = valid;
            this.errors = new ArrayList<>(errors);
            this.warnings = new ArrayList<>(warnings);
        }

        public boolean isValid() {
            return valid;
        }

        public List<String> getErrors() {
            return new ArrayList<>(errors);
        }

        public List<String> getWarnings() {
            return new ArrayList<>(warnings);
        }

        public String getSummary() {
            if (valid) {
                return "VALID";
            }
            return "INVALID: " + String.join("; ", errors);
        }
    }
}

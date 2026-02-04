package com.fu.common.validator;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validator implementation for @NoEmoji annotation.
 * Checks if the input string contains any emoji characters using Unicode ranges.
 */
public class NoEmojiValidator implements ConstraintValidator<NoEmoji, String> {

    // Regex pattern to detect emojis across various Unicode blocks
    private static final String EMOJI_PATTERN = 
        "[" +
        "\u0000-\u001F" +              // Control characters
        "\u2600-\u27BF" +              // Miscellaneous Symbols and Dingbats
        "\uD83C[\uDF00-\uDFFF]" +      // Emoticons
        "\uD83D[\uDC00-\uDE4F]" +      // Additional emoticons
        "\uD83D[\uDE80-\uDEFF]" +      // Transport and map symbols
        "\uD83E[\uDD00-\uDDFF]" +      // Supplemental Symbols
        "\u2700-\u27FF" +              // Dingbats
        "\u2B50-\u2B55" +              // Stars
        "\u231A-\u231B" +              // Watches
        "\u23E9-\u23F3" +              // Media controls
        "\u23F8-\u23FA" +              // More media controls
        "\u25AA-\u25FE" +              // Geometric shapes
        "\u2934-\u2935" +              // Arrows
        "\u2B05-\u2B07" +              // More arrows
        "\u3030" +                     // Wavy dash
        "\u303D" +                     // Part alternation mark
        "\u3297" +                     // Circled ideograph
        "\u3299" +                     // Circled ideograph
        "\uD83C[\uDC00-\uDCFF]" +      // Playing cards
        "\uD83C[\uDD00-\uDDFF]" +      // Enclosed characters
        "\uD83C[\uDE00-\uDEFF]" +      // Regional indicators
        "\uD83D[\uDE00-\uDE4F]" +      // More emoticons
        "]+";

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isEmpty()) {
            return true; // null/empty values are validated by @NotBlank/@NotNull
        }
        
        // Check for emoji pattern
        return !value.matches(".*" + EMOJI_PATTERN + ".*");
    }
}

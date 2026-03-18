package com.fu.common.validator;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validator implementation for @SafeText annotation.
 * Checks for dangerous characters that could lead to security vulnerabilities.
 */
public class SafeTextValidator implements ConstraintValidator<SafeText, String> {

    // Dangerous patterns that could be used for XSS or injection attacks
    private static final String[] DANGEROUS_PATTERNS = {
        "<script[^>]*>.*?</script>",  // Script tags
        "javascript:",                 // JavaScript protocol
        "onerror\\s*=",               // onerror attribute
        "onclick\\s*=",               // onclick attribute
        "onload\\s*=",                // onload attribute
        "<iframe",                     // iframe tags
        "<object",                     // object tags
        "<embed",                      // embed tags
        "eval\\s*\\(",                // eval function
        "expression\\s*\\(",          // CSS expression
        "\\x00",                      // Null byte
        "\\u0000"                     // Unicode null
    };

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isEmpty()) {
            return true; // null/empty values are validated by @NotBlank/@NotNull
        }

        String lowerValue = value.toLowerCase();
        
        // Check for dangerous patterns
        for (String pattern : DANGEROUS_PATTERNS) {
            if (lowerValue.matches(".*" + pattern + ".*")) {
                return false;
            }
        }
        
        // Check for null bytes
        if (value.contains("\0")) {
            return false;
        }
        
        return true;
    }
}

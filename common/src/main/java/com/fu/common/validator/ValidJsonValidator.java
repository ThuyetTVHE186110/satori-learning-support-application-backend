package com.fu.common.validator;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validates that a string is valid JSON (object or array).
 * Null and blank strings are considered valid (use @NotBlank for required fields).
 */
public class ValidJsonValidator implements ConstraintValidator<ValidJson, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        try {
            MAPPER.readTree(value);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

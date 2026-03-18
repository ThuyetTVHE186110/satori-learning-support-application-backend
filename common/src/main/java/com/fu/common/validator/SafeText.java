package com.fu.common.validator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * Custom validation annotation to ensure text is safe from dangerous characters.
 * Prevents XSS attacks, SQL injection, and other security vulnerabilities.
 */
@Documented
@Constraint(validatedBy = SafeTextValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface SafeText {
    String message() default "Text contains potentially dangerous characters";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

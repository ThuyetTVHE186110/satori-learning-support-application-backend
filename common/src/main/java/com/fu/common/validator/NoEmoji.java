package com.fu.common.validator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * Custom validation annotation to ensure a string does not contain emoji characters.
 * Prevents security issues and data corruption from emoji characters.
 */
@Documented
@Constraint(validatedBy = NoEmojiValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface NoEmoji {
    String message() default "Text must not contain emoji characters";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

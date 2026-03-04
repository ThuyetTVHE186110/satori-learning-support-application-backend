package com.fu.common.validator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that the annotated string is either null/blank or valid JSON.
 */
@Documented
@Constraint(validatedBy = ValidJsonValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidJson {

    String message() default "Must be valid JSON";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

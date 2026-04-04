package com.fu.assignment.ai;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables test execution only if OPENAI_API_KEY environment variable is set.
 * 
 * Usage:
 * @EnabledIfOpenAiKeyExists
 * class MyAiTest { }
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(OpenAiKeyExistsCondition.class)
public @interface EnabledIfOpenAiKeyExists {
}


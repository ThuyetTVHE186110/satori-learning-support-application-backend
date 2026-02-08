package com.fu.course.exception;

import com.fu.common.exception.ResourceNotFoundException;

import java.util.UUID;

/**
 * Exception thrown when a lesson is not found.
 */
public class LessonNotFoundException extends ResourceNotFoundException {

    public LessonNotFoundException(UUID lessonId) {
        super("Lesson not found with ID: " + lessonId);
    }

    public LessonNotFoundException(String message) {
        super(message);
    }
}

package com.fu.course.exception;

import com.fu.common.exception.ResourceNotFoundException;

import java.util.UUID;

/**
 * Exception thrown when course is not found
 */
public class CourseNotFoundException extends ResourceNotFoundException {
    
    public CourseNotFoundException(UUID courseId) {
        super("Course not found with ID: " + courseId);
    }
    
    public CourseNotFoundException(String message) {
        super(message);
    }
}

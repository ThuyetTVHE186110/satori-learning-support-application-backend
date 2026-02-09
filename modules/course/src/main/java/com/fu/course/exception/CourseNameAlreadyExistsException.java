package com.fu.course.exception;

import com.fu.common.exception.BusinessException;

/**
 * Exception thrown when course name already exists
 */
public class CourseNameAlreadyExistsException extends BusinessException {
    
    public CourseNameAlreadyExistsException(String courseName) {
        super("Course with name '" + courseName + "' already exists");
    }
}

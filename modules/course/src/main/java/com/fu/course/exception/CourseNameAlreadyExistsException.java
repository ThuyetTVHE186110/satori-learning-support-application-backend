package com.fu.course.exception;

import com.fu.common.exception.BusinessException;

/**
 * Exception thrown when course name already exists
 */
public class CourseNameAlreadyExistsException extends BusinessException {
    
    public CourseNameAlreadyExistsException(String courseName) {
        super("Khóa học với tên '" + courseName + "' đã tồn tại.");
    }
}

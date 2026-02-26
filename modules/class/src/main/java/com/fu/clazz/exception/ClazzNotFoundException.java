package com.fu.clazz.exception;

import com.fu.common.exception.ResourceNotFoundException;

import java.util.UUID;

/**
 * Exception thrown when class is not found
 */
public class ClazzNotFoundException extends ResourceNotFoundException {

    public ClazzNotFoundException(UUID classId) {
        super("Không có lớp nào được tìm thấy với ID: " + classId);
    }

    public ClazzNotFoundException(String message) {
        super(message);
    }
}


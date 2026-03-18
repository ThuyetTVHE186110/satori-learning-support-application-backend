package com.fu.clazz.exception;

import com.fu.common.exception.ResourceNotFoundException;

import java.util.UUID;

/**
 * Exception thrown when class is not found
 */
public class ClassNotFoundException extends ResourceNotFoundException {

    public ClassNotFoundException(UUID classId) {
        super("Không có lớp nào được tìm thấy với ID: " + classId);
    }

    public ClassNotFoundException(String message) {
        super(message);
    }
}


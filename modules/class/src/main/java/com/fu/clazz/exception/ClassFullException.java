package com.fu.clazz.exception;

import com.fu.common.exception.BusinessException;

/**
 * Exception thrown when class is full (max_students reached)
 */
public class ClassFullException extends BusinessException {

    public ClassFullException(String className, int maxStudents) {
        super("CLASS_FULL", "Lớp đã đạt sĩ số tối đa. Không thể thêm học viên.");
    }
}

package com.fu.clazz.exception;

import com.fu.common.exception.BusinessException;

/**
 * Exception thrown when a member already exists in a class
 */
public class MemberAlreadyExistsException extends BusinessException {

    public MemberAlreadyExistsException() {
        super("MEMBER_ALREADY_EXISTS", "Người dùng đã là thành viên của lớp học này");
    }
}


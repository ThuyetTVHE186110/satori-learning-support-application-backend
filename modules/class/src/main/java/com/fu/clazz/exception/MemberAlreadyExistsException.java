package com.fu.clazz.exception;

import com.fu.common.exception.BusinessException;

/**
 * Exception thrown when a member already exists in a class
 */
public class MemberAlreadyExistsException extends BusinessException {

    public MemberAlreadyExistsException() {
        super("MEMBER_ALREADY_EXISTS", "User is already a member of this class");
    }
}


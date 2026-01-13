package com.fu.common.exception;

public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String resource, Object id) {
        super("NOT_FOUND", String.format("%s not found with id: %s", resource, id));
    }

    public ResourceNotFoundException(String message) {
        super("NOT_FOUND", message);
    }
}

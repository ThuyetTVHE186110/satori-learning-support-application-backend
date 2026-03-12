package com.fu.assignment.exception;

import com.fu.common.exception.ResourceNotFoundException;

/**
 * Thrown when an Assignment is not found or has been soft-deleted.
 */
public class AssignmentNotFoundException extends ResourceNotFoundException {

    public AssignmentNotFoundException(String message) {
        super(message);
    }

    public AssignmentNotFoundException() {
        super("Assignment not found.");
    }
}

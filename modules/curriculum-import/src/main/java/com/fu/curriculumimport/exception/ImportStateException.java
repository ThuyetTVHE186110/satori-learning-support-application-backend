package com.fu.curriculumimport.exception;

import com.fu.common.exception.BusinessException;

/**
 * Exception thrown when an import operation is in an invalid state
 * for the requested action (e.g., retrying a non-failed import)
 */
public class ImportStateException extends BusinessException {

    public ImportStateException(String message) {
        super("INVALID_IMPORT_STATE", message);
    }
}

package com.fu.curriculumimport.exception;

import com.fu.common.exception.BusinessException;

/**
 * Exception thrown when an invalid file is uploaded for curriculum import
 */
public class InvalidImportFileException extends BusinessException {

    public InvalidImportFileException(String message) {
        super("INVALID_FILE", message);
    }
}

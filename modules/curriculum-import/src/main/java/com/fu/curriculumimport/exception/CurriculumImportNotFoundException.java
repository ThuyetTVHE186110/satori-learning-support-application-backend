package com.fu.curriculumimport.exception;

import com.fu.common.exception.ResourceNotFoundException;

import java.util.UUID;

/**
 * Exception thrown when a curriculum import is not found
 */
public class CurriculumImportNotFoundException extends ResourceNotFoundException {

    public CurriculumImportNotFoundException(UUID importId) {
        super("Curriculum import not found with ID: " + importId);
    }
}

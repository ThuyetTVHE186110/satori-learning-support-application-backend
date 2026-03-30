package com.fu.user.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Result of an Excel bulk-import operation.
 * Separates newly created accounts, updated accounts, and validation errors.
 */
@Data
@Builder
public class ImportUsersResult {
    /** Users whose accounts were created for the first time. */
    private List<UserResponse> created;
    /** Users who already existed and had their profile updated. */
    private List<UserResponse> updated;
    /** Rows that failed validation and were not imported. */
    private List<ImportRowError> errors;

    private int totalRows;
    private int createdCount;
    private int updatedCount;
    private int errorCount;
}

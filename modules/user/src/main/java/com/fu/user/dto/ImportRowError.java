package com.fu.user.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Represents a single row that failed validation during Excel import.
 */
@Data
@Builder
public class ImportRowError {
    /**
     * 1-based row index within the sheet (header = row 0 is excluded).
     */
    private int rowNumber;
    /**
     * 0 = learners sheet, 1 = teachers sheet.
     */
    private int sheetIndex;
    /**
     * Raw email value from the cell (may be blank or invalid).
     */
    private String email;
    /**
     * Raw full name value from the cell.
     */
    private String fullName;
    /**
     * Raw phone number value from the cell.
     */
    private String phoneNumber;
    /**
     * Raw birthDay value from the cell
     */
    private String birthDay;
    /**
     * Human-readable reason why this row was rejected.
     */
    private String phoneNumberErrorMessage;

    private String birthDayErrorMessage;

    private String emailErrorMessage;

    private String fullNameErrorMessage;

    public boolean hasError() {
        return phoneNumberErrorMessage != null || birthDayErrorMessage != null || emailErrorMessage != null || fullNameErrorMessage != null;
    }
}

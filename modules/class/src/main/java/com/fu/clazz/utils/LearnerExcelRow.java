package com.fu.clazz.utils;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Internal model for learner data from Excel Sheet 1
 * Used during import to gather full learner details
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LearnerExcelRow {
    private String className;       // G column
    private String name;            // I column
    private String email;           // L column
    private LocalDate dateOfBirth;  // J column (estimated)
    private String phoneNumber;     // K column (estimated)
}

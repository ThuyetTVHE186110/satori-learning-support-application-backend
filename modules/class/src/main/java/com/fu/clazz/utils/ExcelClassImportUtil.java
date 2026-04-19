package com.fu.clazz.utils;

import com.fu.clazz.dto.response.ClassImportError;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Utility class for reading and parsing Excel files for class import.
 * Handles both .xlsx and .xls formats using Apache POI.
 */
@Slf4j
public class ExcelClassImportUtil {

    // Excel column indexes (0-based)
    public static final int COL_STT = 0;          // A
    public static final int COL_CLASS_NAME = 1;   // B
    public static final int COL_DESCRIPTION = 2;  // C
    public static final int COL_COURSE_NAME = 3;  // D
    public static final int COL_START_DATE = 4;   // E
    public static final int COL_TEACHER_NAME = 5; // F
    public static final int COL_TEACHER_EMAIL = 6; // G
    public static final int COL_MAX_STUDENTS = 7; // H - Số học sinh tối đa

    // Sheet names
    public static final String SHEET_NAME_CLASSES = "Sheet quản lý lớp học";
    public static final String SHEET_NAME_LEARNERS = "sheet tổng thông tin học viên";

    // Learner sheet columns (for auto-assignment)
    public static final int LEARNER_COL_CLASS_NAME = 6;  // G
    public static final int LEARNER_COL_LEARNER_NAME = 8; // I
    public static final int LEARNER_COL_EMAIL = 11;  // L
    public static final int LEARNER_COL_DOB = 9;     // J (estimated, may need adjustment)
    public static final int LEARNER_COL_PHONE = 10;  // K (estimated, may need adjustment)

    // Date formatters - support both Vietnamese (DD/MM/YYYY) and ISO (yyyy-MM-dd) formats
    private static final DateTimeFormatter[] DATE_FORMATTERS = {
        DateTimeFormatter.ofPattern("[d][dd]/[M][MM]/yyyy"),  // Vietnamese format (25/12/2003)
        DateTimeFormatter.ofPattern("yyyy-MM[M]-dd[d]")   // ISO format
    };

    /**
     * Read classes from Excel Sheet 3
     *
     * @param file Excel file
     * @return List of ClassExcelRow objects
     * @throws IOException if file read fails
     */
    public static List<ClassExcelRow> readClassesFromExcel(MultipartFile file) throws IOException {
        List<ClassExcelRow> classes = new ArrayList<>();

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheet(SHEET_NAME_CLASSES);
            if (sheet == null) {
                // If the sheet with the expected name is not found, try to get by index (Sheet 3 = index 2)
                sheet = workbook.getSheetAt(2); // Ensure we are reading the correct sheet index (Sheet 3)
                if (sheet == null) {
                    log.error("Sheet index 2 (Sheet 3) not found in workbook");
                    return classes;
                }
            }

            // Skip header row (row 0)
            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);

                if (row == null || isRowEmpty(row)) {
                    log.debug("Skipping empty row {}", rowIndex + 1);
                    continue;
                }

                ClassExcelRow classRow = parseClassRow(row, rowIndex + 1);
                if (classRow.isEmpty()) {
                    continue;
                }
                classes.add(classRow);
            }
        }

        return classes;
    }

    /**
     * Read learners from Excel Sheet 1 for auto-assignment.
     * Returns a map where key = class name, value = list of learner emails
     *
     * @param file Excel file
     * @return Map<ClassName, List<LearnerEmails>>
     * @throws IOException if file read fails
     */
    public static Map<String, List<String>> readLearnersFromExcel(MultipartFile file) throws IOException {
        Map<String, List<String>> learnersByClass = new HashMap<>();

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheet(SHEET_NAME_LEARNERS);
            if (sheet == null) {
                // If the sheet with the expected name is not found, try to get by index (Sheet 1 = index 0)
                sheet = workbook.getSheetAt(0); // Ensure we are reading the correct sheet index
                if (sheet == null) {
                    log.error("Sheet index 0 (Sheet 1) not found in workbook");
                    return learnersByClass;
                }
            }

            // Skip header row (row 0)
            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);

                if (row == null || isRowEmpty(row)) {
                    log.debug("Skipping empty learner row {}", rowIndex + 1);
                    continue;
                }

                String className = getCellValueAsString(row.getCell(LEARNER_COL_CLASS_NAME));
                String email = getCellValueAsString(row.getCell(LEARNER_COL_EMAIL));

                if (className != null && !className.trim().isEmpty() &&
                        email != null && !email.trim().isEmpty()) {
                    learnersByClass.computeIfAbsent(className.trim(), k -> new ArrayList<>())
                            .add(email.trim());
                }
            }
        }

        return learnersByClass;
    }

    /**
     * Read detailed learner info from Excel Sheet 1 for preview
     * Returns a map where key = class name, value = list of LearnerExcelRow with full details
     *
     * @param file Excel file
     * @return Map<ClassName, List<LearnerExcelRow>>
     * @throws IOException if file read fails
     */
    public static Map<String, List<LearnerExcelRow>> readLearnersDetailedFromExcel(MultipartFile file) throws IOException {
        Map<String, List<LearnerExcelRow>> learnersByClass = new HashMap<>();

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheet(SHEET_NAME_LEARNERS);
            if (sheet == null) {
                // If the sheet with the expected name is not found, try to get by index (Sheet 1 = index 0)
                sheet = workbook.getSheetAt(0); // Ensure we are reading the correct sheet index
                if (sheet == null) {

                    log.warn("Sheet '{}' not found in workbook", SHEET_NAME_LEARNERS);
                    return learnersByClass;
                }
            }

            // Skip header row (row 0)
            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);

                if (row == null || isRowEmpty(row)) {
                    continue;
                }

                LearnerExcelRow learnerRow = parseLearnerRow(row);
                if (learnerRow.getClassName() != null && !learnerRow.getClassName().trim().isEmpty() &&
                        learnerRow.getEmail() != null && !learnerRow.getEmail().trim().isEmpty()) {
                    learnersByClass.computeIfAbsent(learnerRow.getClassName().trim(), k -> new ArrayList<>())
                            .add(learnerRow);
                }
            }
        }

        return learnersByClass;
    }

    /**
     * Parse a single learner row from Excel
     *
     * @param row Excel row
     * @return LearnerExcelRow with parsed data
     */
    private static LearnerExcelRow parseLearnerRow(Row row) {
        return LearnerExcelRow.builder()
                .className(getCellValueAsString(row.getCell(LEARNER_COL_CLASS_NAME)))
                .name(getCellValueAsString(row.getCell(LEARNER_COL_LEARNER_NAME)))
                .email(getCellValueAsString(row.getCell(LEARNER_COL_EMAIL)))
                .dateOfBirth(getCellValueAsDate(row.getCell(LEARNER_COL_DOB)))
                .phoneNumber(getCellValueAsString(row.getCell(LEARNER_COL_PHONE)))
                .build();
    }

    /**
     * Validate a single class row
     *
     * @param row ClassExcelRow to validate
     * @return Optional containing ClassImportError if validation fails, empty if valid
     */
    public static Optional<ClassImportError> validateClassRow(ClassExcelRow row) {
        // Validate class name (required, 3-255 chars)
        if (row.getName() == null || row.getName().trim().isEmpty()) {
            return Optional.of(ClassImportError.builder()
                    .rowNumber(row.getRowNumber())
                    .className("")
                    .errorMessage("Tên lớp không được để trống")
                    .build());
        }

        String name = row.getName().trim();
        if (name.length() < 3) {
            return Optional.of(ClassImportError.builder()
                    .rowNumber(row.getRowNumber())
                    .className(name)
                    .errorMessage("Tên lớp phải có ít nhất 3 ký tự")
                    .build());
        }

        if (name.length() > 255) {
            return Optional.of(ClassImportError.builder()
                    .rowNumber(row.getRowNumber())
                    .className(name)
                    .errorMessage("Tên lớp không được vượt quá 255 ký tự")
                    .build());
        }

        // Validate course name (required)
        if (row.getCourseName() == null || row.getCourseName().trim().isEmpty()) {
            return Optional.of(ClassImportError.builder()
                    .rowNumber(row.getRowNumber())
                    .className(name)
                    .errorMessage("Khóa học không được để trống")
                    .build());
        }

        // Validate teacher email (required)
        if (row.getTeacherEmail() == null || row.getTeacherEmail().trim().isEmpty()) {
            return Optional.of(ClassImportError.builder()
                    .rowNumber(row.getRowNumber())
                    .className(name)
                    .errorMessage("Email giảng viên không được để trống")
                    .build());
        }

        // Validate date format if provided
        if (row.getStartDate() == null && row.getStartDate() != null) {
            // Note: POI should already parse dates correctly, but double-check
            return Optional.of(ClassImportError.builder()
                    .rowNumber(row.getRowNumber())
                    .className(name)
                    .errorMessage("Ngày bắt đầu không hợp lệ (định dạng: yyyy-MM-dd)")
                    .build());
        }

        return Optional.empty();
    }

    /**
     * Validate that a learner has not been deleted
     *
     * @param email          Learner email
     * @param className      Class name (for error reporting)
     * @param userRepository Repository to look up user
     * @return Optional containing ClassImportError if learner is deleted, empty if valid
     */
    public static Optional<ClassImportError> validateLearnerNotDeleted(String email, String className,
                                                                       Object userRepository, int rowNumber) {
        // Note: This method will be called from service layer where we have UserRepository injected
        // Return structure here, implementation in service
        return Optional.empty();
    }

    /**
     * Validate that learner count does not exceed max students
     *
     * @param learnersToAdd List of learners to add
     * @param maxStudents   Maximum student capacity
     * @param className     Class name (for error reporting)
     * @param rowNumber     Row number (for error reporting)
     * @return Optional containing ClassImportError if count exceeded, empty if valid
     */
    public static Optional<ClassImportError> validateMaxStudentsNotExceeded(int learnersToAdd, int maxStudents,
                                                                            String className, int rowNumber) {
        if (learnersToAdd > maxStudents) {
            return Optional.of(ClassImportError.builder()
                    .rowNumber(rowNumber)
                    .className(className)
                    .errorMessage(String.format("Số học sinh (%d) vượt quá sức chứa tối đa (%d)", learnersToAdd, maxStudents))
                    .build());
        }
        return Optional.empty();
    }

    /**
     * Validate that a learner is not assigned to multiple different classes in the same batch
     *
     * @param learnerEmail       Learner email
     * @param className          Current class name
     * @param allLearnersByClass Map of all learners by class (className -> learnerEmails)
     * @return Optional containing ClassImportError if learner is in multiple classes, empty if valid
     */
    public static Optional<ClassImportError> validateLearnerNotInMultipleClasses(String learnerEmail, String className,
                                                                                 Map<String, List<String>> allLearnersByClass,
                                                                                 int rowNumber) {
        int classCount = 0;
        for (Map.Entry<String, List<String>> entry : allLearnersByClass.entrySet()) {
            if (entry.getValue().contains(learnerEmail)) {
                classCount++;
                if (classCount > 1) {
                    // Found in multiple classes
                    List<String> classNames = allLearnersByClass.entrySet().stream()
                            .filter(e -> e.getValue().contains(learnerEmail))
                            .map(Map.Entry::getKey)
                            .toList();
                    return Optional.of(ClassImportError.builder()
                            .rowNumber(rowNumber)
                            .className(className)
                            .errorMessage(String.format("Học sinh %s được gán vào nhiều lớp: %s", learnerEmail, classNames))
                            .build());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Parse a single row from Excel into ClassExcelRow object
     *
     * @param row       Excel row
     * @param rowNumber 1-indexed row number for error reporting
     * @return ClassExcelRow with parsed data
     */
    private static ClassExcelRow parseClassRow(Row row, int rowNumber) {
        Integer maxStudents = getCellValueAsInt(row.getCell(COL_MAX_STUDENTS));
        if (maxStudents == null || maxStudents <= 0) {
            maxStudents = 8;  // Default to 8 if not specified or invalid
        }

        return ClassExcelRow.builder()
                .rowNumber(rowNumber)
                .stt(getCellValueAsInt(row.getCell(COL_STT)))
                .name(getCellValueAsString(row.getCell(COL_CLASS_NAME)))
                .description(getCellValueAsString(row.getCell(COL_DESCRIPTION)))
                .courseName(getCellValueAsString(row.getCell(COL_COURSE_NAME)))
                .startDate(getCellValueAsDate(row.getCell(COL_START_DATE)))
                .teacherName(getCellValueAsString(row.getCell(COL_TEACHER_NAME)))
                .teacherEmail(getCellValueAsString(row.getCell(COL_TEACHER_EMAIL)))
                .maxStudents(maxStudents)
                .build();
    }

    /**
     * Get cell value as String (handles various data types)
     */
    private static String getCellValueAsString(Cell cell) {
        if (cell == null) {
            return null;
        }

        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                return String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case BLANK:
                return null;
            default:
                return null;
        }
    }

    /**
     * Get cell value as Integer
     */
    private static Integer getCellValueAsInt(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }

        try {
            if (cell.getCellType() == CellType.NUMERIC) {
                return (int) cell.getNumericCellValue();
            } else if (cell.getCellType() == CellType.STRING) {
                return Integer.parseInt(cell.getStringCellValue().trim());
            }
        } catch (NumberFormatException e) {
            log.warn("Failed to parse integer from cell: {}", cell.getStringCellValue());
        }

        return null;
    }

    /**
     * Get cell value as LocalDate
     * Supports both Vietnamese (DD/MM/YYYY) and ISO (yyyy-MM-dd) date formats
     */
    private static LocalDate getCellValueAsDate(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }

        try {
            // POI can handle date cells natively
            if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                return cell.getDateCellValue().toInstant()
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDate();
            } else if (cell.getCellType() == CellType.STRING) {
                String dateStr = cell.getStringCellValue().trim();
                
                // Try multiple date formats
                for (DateTimeFormatter formatter : DATE_FORMATTERS) {
                    try {
                        return LocalDate.parse(dateStr, formatter);
                    } catch (DateTimeParseException ignored) {
                        // Try next formatter
                    }
                }
                
                // If all formatters fail, log warning
                log.warn("Failed to parse date from cell (tried all formats): {}", dateStr);
            }
        } catch (Exception e) {
            log.warn("Failed to parse date from cell: {}", cell);
        }

        return null;
    }

    /**
     * Check if a row is completely empty
     */
    private static boolean isRowEmpty(Row row) {
        if (row == null) {
            return true;
        }

        for (int i = row.getFirstCellNum(); i < row.getLastCellNum(); i++) {
            Cell cell = row.getCell(i);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                String value = getCellValueAsString(cell);
                if (value != null && !value.trim().isEmpty()) {
                    return false;
                }
            }
        }

        return true;
    }
}

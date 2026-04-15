package com.fu.clazz.utils;

import com.fu.clazz.dto.response.ClassImportError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ExcelClassImportUtil Tests")
class ExcelClassImportUtilTest {

    @Nested
    @DisplayName("Class Row Validation Tests")
    class ClassRowValidationTests {

        @Test
        @DisplayName("Reject row with missing class name")
        void testRejectMissingClassName() {
            ClassExcelRow row = ClassExcelRow.builder()
                    .name(null)
                    .courseName("N5")
                    .teacherEmail("teacher@example.com")
                    .rowNumber(1)
                    .build();

            Optional<ClassImportError> error = ExcelClassImportUtil.validateClassRow(row);

            assertTrue(error.isPresent());
            assertTrue(error.get().getErrorMessage().contains("trống") || 
                      error.get().getErrorMessage().contains("bắt buộc"));
        }

        @Test
        @DisplayName("Reject row with blank class name")
        void testRejectBlankClassName() {
            ClassExcelRow row = ClassExcelRow.builder()
                    .name("   ")
                    .courseName("N5")
                    .teacherEmail("teacher@example.com")
                    .rowNumber(1)
                    .build();

            Optional<ClassImportError> error = ExcelClassImportUtil.validateClassRow(row);

            assertTrue(error.isPresent());
        }

        @Test
        @DisplayName("Reject class name shorter than 3 characters")
        void testRejectClassNameTooShort() {
            ClassExcelRow row = ClassExcelRow.builder()
                    .name("AB")
                    .courseName("N5")
                    .teacherEmail("teacher@example.com")
                    .rowNumber(1)
                    .build();

            Optional<ClassImportError> error = ExcelClassImportUtil.validateClassRow(row);

            assertTrue(error.isPresent());
            assertTrue(error.get().getErrorMessage().contains("3") || 
                      error.get().getErrorMessage().contains("ký tự"));
        }

        @Test
        @DisplayName("Reject class name longer than 255 characters")
        void testRejectClassNameTooLong() {
            String longName = "A".repeat(256);
            ClassExcelRow row = ClassExcelRow.builder()
                    .name(longName)
                    .courseName("N5")
                    .teacherEmail("teacher@example.com")
                    .rowNumber(1)
                    .build();

            Optional<ClassImportError> error = ExcelClassImportUtil.validateClassRow(row);

            assertTrue(error.isPresent());
            assertTrue(error.get().getErrorMessage().contains("255") || 
                      error.get().getErrorMessage().contains("ký tự"));
        }

        @Test
        @DisplayName("Reject row with missing course name")
        void testRejectMissingCourseName() {
            ClassExcelRow row = ClassExcelRow.builder()
                    .name("ValidClass")
                    .courseName(null)
                    .teacherEmail("teacher@example.com")
                    .rowNumber(1)
                    .build();

            Optional<ClassImportError> error = ExcelClassImportUtil.validateClassRow(row);

            assertTrue(error.isPresent());
            assertTrue(error.get().getErrorMessage().contains("Khóa học"));
        }

        @Test
        @DisplayName("Reject row with missing teacher email")
        void testRejectMissingTeacherEmail() {
            ClassExcelRow row = ClassExcelRow.builder()
                    .name("ValidClass")
                    .courseName("N5")
                    .teacherEmail(null)
                    .rowNumber(1)
                    .build();

            Optional<ClassImportError> error = ExcelClassImportUtil.validateClassRow(row);

            assertTrue(error.isPresent());
            assertTrue(error.get().getErrorMessage().contains("Email"));
        }

        @Test
        @DisplayName("Accept valid class row")
        void testAcceptValidClassRow() {
            ClassExcelRow row = ClassExcelRow.builder()
                    .name("Valid Class Name")
                    .courseName("N5")
                    .teacherEmail("teacher@example.com")
                    .rowNumber(1)
                    .build();

            Optional<ClassImportError> error = ExcelClassImportUtil.validateClassRow(row);

            assertTrue(error.isEmpty());
        }

        @Test
        @DisplayName("Accept class name exactly 3 characters")
        void testAcceptClassNameExactly3Chars() {
            ClassExcelRow row = ClassExcelRow.builder()
                    .name("ABC")
                    .courseName("N5")
                    .teacherEmail("teacher@example.com")
                    .rowNumber(1)
                    .build();

            Optional<ClassImportError> error = ExcelClassImportUtil.validateClassRow(row);

            assertTrue(error.isEmpty());
        }

        @Test
        @DisplayName("Accept class name exactly 255 characters")
        void testAcceptClassNameExactly255Chars() {
            String name = "A".repeat(255);
            ClassExcelRow row = ClassExcelRow.builder()
                    .name(name)
                    .courseName("N5")
                    .teacherEmail("teacher@example.com")
                    .rowNumber(1)
                    .build();

            Optional<ClassImportError> error = ExcelClassImportUtil.validateClassRow(row);

            assertTrue(error.isEmpty());
        }

        @Test
        @DisplayName("Trim class name before validation")
        void testTrimClassName() {
            ClassExcelRow row = ClassExcelRow.builder()
                    .name("  Valid Class  ")
                    .courseName("N5")
                    .teacherEmail("teacher@example.com")
                    .rowNumber(1)
                    .build();

            Optional<ClassImportError> error = ExcelClassImportUtil.validateClassRow(row);

            assertTrue(error.isEmpty());
        }
    }

    @Nested
    @DisplayName("Learner-Class Multi-Assignment Validation Tests")
    class LearnerMultiClassValidationTests {

        @Test
        @DisplayName("Detect learner in multiple classes")
        void testDetectLearnerInMultipleClasses() {
            Map<String, List<String>> allLearnersByClass = new HashMap<>();
            allLearnersByClass.put("ClassA", Arrays.asList("john@example.com", "jane@example.com"));
            allLearnersByClass.put("ClassB", Arrays.asList("john@example.com", "bob@example.com"));

            Optional<ClassImportError> error = ExcelClassImportUtil.validateLearnerNotInMultipleClasses(
                    "john@example.com", "ClassA", allLearnersByClass, 5);

            assertTrue(error.isPresent());
            assertTrue(error.get().getErrorMessage().contains("nhiều lớp"));
        }

        @Test
        @DisplayName("Allow learner in single class")
        void testAllowLearnerInSingleClass() {
            Map<String, List<String>> allLearnersByClass = new HashMap<>();
            allLearnersByClass.put("ClassA", Arrays.asList("john@example.com", "jane@example.com"));
            allLearnersByClass.put("ClassB", Arrays.asList("bob@example.com", "alice@example.com"));

            Optional<ClassImportError> error = ExcelClassImportUtil.validateLearnerNotInMultipleClasses(
                    "john@example.com", "ClassA", allLearnersByClass, 5);

            assertTrue(error.isEmpty());
        }

        @Test
        @DisplayName("Handle learner not in any class")
        void testLearnerNotInAnyClass() {
            Map<String, List<String>> allLearnersByClass = new HashMap<>();
            allLearnersByClass.put("ClassA", Arrays.asList("jane@example.com"));
            allLearnersByClass.put("ClassB", Arrays.asList("bob@example.com"));

            Optional<ClassImportError> error = ExcelClassImportUtil.validateLearnerNotInMultipleClasses(
                    "john@example.com", "ClassA", allLearnersByClass, 5);

            assertTrue(error.isEmpty());
        }

        @Test
        @DisplayName("Detect learner in exactly 2 classes")
        void testDetectLearnerInExactlyTwoClasses() {
            Map<String, List<String>> allLearnersByClass = new HashMap<>();
            allLearnersByClass.put("ClassA", Arrays.asList("john@example.com"));
            allLearnersByClass.put("ClassB", Arrays.asList("john@example.com"));

            Optional<ClassImportError> error = ExcelClassImportUtil.validateLearnerNotInMultipleClasses(
                    "john@example.com", "ClassA", allLearnersByClass, 5);

            assertTrue(error.isPresent());
        }

        @Test
        @DisplayName("Return correct row number in error")
        void testErrorIncludesCorrectRowNumber() {
            Map<String, List<String>> allLearnersByClass = new HashMap<>();
            allLearnersByClass.put("ClassA", Arrays.asList("john@example.com"));
            allLearnersByClass.put("ClassB", Arrays.asList("john@example.com"));

            Optional<ClassImportError> error = ExcelClassImportUtil.validateLearnerNotInMultipleClasses(
                    "john@example.com", "ClassA", allLearnersByClass, 10);

            assertTrue(error.isPresent());
            assertEquals(10, error.get().getRowNumber());
        }
    }

    @Nested
    @DisplayName("Max Students Validation Tests")
    class MaxStudentsValidationTests {

        @Test
        @DisplayName("Reject when learner count exceeds maxStudents")
        void testRejectWhenCapacityExceeded() {
            Optional<ClassImportError> error = ExcelClassImportUtil.validateMaxStudentsNotExceeded(
                    5, 3, "TestClass", 5);

            assertTrue(error.isPresent());
            assertTrue(error.get().getErrorMessage().contains("vượt quá"));
        }

        @Test
        @DisplayName("Allow when learner count equals maxStudents")
        void testAllowWhenCapacityExact() {
            Optional<ClassImportError> error = ExcelClassImportUtil.validateMaxStudentsNotExceeded(
                    3, 3, "TestClass", 5);

            assertTrue(error.isEmpty());
        }

        @Test
        @DisplayName("Allow when learner count below maxStudents")
        void testAllowWhenBelowCapacity() {
            Optional<ClassImportError> error = ExcelClassImportUtil.validateMaxStudentsNotExceeded(
                    2, 3, "TestClass", 5);

            assertTrue(error.isEmpty());
        }

        @Test
        @DisplayName("Handle zero learners")
        void testZeroLearners() {
            Optional<ClassImportError> error = ExcelClassImportUtil.validateMaxStudentsNotExceeded(
                    0, 5, "TestClass", 1);

            assertTrue(error.isEmpty());
        }

        @Test
        @DisplayName("Return correct row number in error")
        void testErrorIncludesCorrectRowNumber() {
            Optional<ClassImportError> error = ExcelClassImportUtil.validateMaxStudentsNotExceeded(
                    5, 3, "TestClass", 10);

            assertTrue(error.isPresent());
            assertEquals(10, error.get().getRowNumber());
        }

        @Test
        @DisplayName("Return class name in error")
        void testErrorIncludesClassName() {
            Optional<ClassImportError> error = ExcelClassImportUtil.validateMaxStudentsNotExceeded(
                    5, 3, "TestClass", 5);

            assertTrue(error.isPresent());
            assertEquals("TestClass", error.get().getClassName());
        }

        @Test
        @DisplayName("One learner exceeds max of zero")
        void testOneLearnerExceedsZeroCapacity() {
            Optional<ClassImportError> error = ExcelClassImportUtil.validateMaxStudentsNotExceeded(
                    1, 0, "TestClass", 5);

            assertTrue(error.isPresent());
        }
    }

    @Nested
    @DisplayName("ClassExcelRow Structure Tests")
    class ClassExcelRowTests {

        @Test
        @DisplayName("Default maxStudents to 8 when null")
        void testMaxStudentsDefaultValue() {
            ClassExcelRow row = new ClassExcelRow();
            row.setName("TestClass");
            row.setCourseName("N5");
            row.setTeacherEmail("teacher@example.com");

            Integer maxStudents = row.getMaxStudents() == null ? 8 : row.getMaxStudents();
            assertEquals(8, maxStudents);
        }

        @Test
        @DisplayName("maxStudents accepts explicit value")
        void testMaxStudentsExplicitValue() {
            ClassExcelRow row = ClassExcelRow.builder()
                    .name("TestClass")
                    .maxStudents(15)
                    .build();

            assertEquals(15, row.getMaxStudents());
        }

        @Test
        @DisplayName("ClassExcelRow isEmpty() for empty object")
        void testClassExcelRowIsEmpty() {
            ClassExcelRow row = new ClassExcelRow();
            assertTrue(row.isEmpty());
        }

        @Test
        @DisplayName("ClassExcelRow isEmpty() returns false when has name")
        void testClassExcelRowIsNotEmpty() {
            ClassExcelRow row = ClassExcelRow.builder()
                    .name("TestClass")
                    .build();

            assertFalse(row.isEmpty());
        }

        @Test
        @DisplayName("Preserve all fields after builder")
        void testAllFieldsPreserved() {
            ClassExcelRow row = ClassExcelRow.builder()
                    .name("TestClass")
                    .description("Test Description")
                    .courseName("N5")
                    .startDate(java.time.LocalDate.of(2026, 4, 15))
                    .teacherName("Teacher Name")
                    .teacherEmail("teacher@example.com")
                    .maxStudents(10)
                    .rowNumber(5)
                    .build();

            assertEquals("TestClass", row.getName());
            assertEquals("Test Description", row.getDescription());
            assertEquals("N5", row.getCourseName());
            assertEquals(java.time.LocalDate.of(2026, 4, 15), row.getStartDate());
            assertEquals("Teacher Name", row.getTeacherName());
            assertEquals("teacher@example.com", row.getTeacherEmail());
            assertEquals(10, row.getMaxStudents());
            assertEquals(5, row.getRowNumber());
        }
    }

    @Nested
    @DisplayName("Error Reporting Tests")
    class ErrorReportingTests {

        @Test
        @DisplayName("ClassImportError includes row number")
        void testErrorIncludesRowNumber() {
            ClassExcelRow row = ClassExcelRow.builder()
                    .name(null)
                    .courseName("N5")
                    .teacherEmail("teacher@example.com")
                    .rowNumber(10)
                    .build();

            Optional<ClassImportError> error = ExcelClassImportUtil.validateClassRow(row);

            assertTrue(error.isPresent());
            assertEquals(10, error.get().getRowNumber());
        }

        @Test
        @DisplayName("ClassImportError includes class name")
        void testErrorIncludesClassName() {
            ClassExcelRow row = ClassExcelRow.builder()
                    .name("TestClass")
                    .courseName(null)
                    .teacherEmail("teacher@example.com")
                    .rowNumber(5)
                    .build();

            Optional<ClassImportError> error = ExcelClassImportUtil.validateClassRow(row);

            assertTrue(error.isPresent());
            assertEquals("TestClass", error.get().getClassName());
        }

        @Test
        @DisplayName("ClassImportError includes detailed message")
        void testErrorIncludesDetailedMessage() {
            ClassExcelRow row = ClassExcelRow.builder()
                    .name("AB")
                    .courseName("N5")
                    .teacherEmail("teacher@example.com")
                    .rowNumber(3)
                    .build();

            Optional<ClassImportError> error = ExcelClassImportUtil.validateClassRow(row);

            assertTrue(error.isPresent());
            assertNotNull(error.get().getErrorMessage());
            assertTrue(error.get().getErrorMessage().length() > 0);
        }
    }
}

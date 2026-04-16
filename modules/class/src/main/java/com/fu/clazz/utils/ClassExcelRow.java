package com.fu.clazz.utils;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Internal model for class data read from Excel (Sheet 3).
 * Used temporarily for parsing and validation before converting to DTO.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassExcelRow {
    private int rowNumber;  // 1-indexed row number in Excel (for error reporting)
    private int stt;        // cột A: STT (số thứ tự)
    private String name;    // cột B: Tên Lớp
    private String description;  // cột C: Mô tả
    private String courseName;   // cột D: Khóa học (tên khóa học, VD: N5, N4, N3)
    private LocalDate startDate; // cột E: Ngày bắt đầu (yyyy-MM-dd)
    private String teacherName;  // cột F: Giảng Viên (display only)
    private String teacherEmail; // cột G: Email Giảng Viên
    private Integer maxStudents;  // cột H: Số học sinh tối đa (optional, default=8)

    public boolean isEmpty() {
        return (name == null || name.isBlank()) &&
               (description == null || description.isBlank()) &&
               (courseName == null || courseName.isBlank()) &&
               startDate == null &&
               (teacherName == null || teacherName.isBlank()) &&
               (teacherEmail == null || teacherEmail.isBlank());
    }
}

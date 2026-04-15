package com.fu.clazz.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Final result of class import after approval and creation.
 * Shows counts of successfully created and updated classes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportClassesResult {
    private int totalRows;
    private int createdCount;   // số lớp được tạo mới
    private int updatedCount;   // số lớp được cập nhật
    private int failureCount;   // số lớp bị lỗi
    private List<ClassImportError> errors;
}

package com.fu.clazz.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response for Step 1 of class import: Preview
 * Contains validation results and separated lists of classes to create (NEW) and update (UPDATE).
 * Client should store this data and send it back in approveImportClasses request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportClassesPreview {
    private int totalRows;
    private int validRows;
    private int invalidRows;
    private int newClasses;      // số lớp sẽ được tạo mới
    private int updateClasses;   // số lớp sẽ được cập nhật
    private List<ClassPreview> createdClasses;  // danh sách lớp NEW - để client gửi lại ở step 2
    private List<ClassPreview> updatedClasses;  // danh sách lớp UPDATE - để client gửi lại ở step 2
    private List<ClassImportError> errors;       // danh sách lỗi
}

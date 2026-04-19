package com.fu.clazz.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fu.clazz.dto.response.ClassPreview;

import java.util.List;

/**
 * Request for Step 2 of class import: Approve/Confirm
 * Client sends back the preview data from step 1, separated into classes to create (NEW) and update (UPDATE).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApproveImportClassesRequest {
    private List<ClassPreview> classesToCreate;  // lớp mới (type=NEW từ bước 1)
    private List<ClassPreview> classesToUpdate;  // lớp cập nhật (type=UPDATE từ bước 1)
}

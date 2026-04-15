package com.fu.clazz.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassImportError {
    private int rowNumber;
    private String className;
    private String errorMessage;
}

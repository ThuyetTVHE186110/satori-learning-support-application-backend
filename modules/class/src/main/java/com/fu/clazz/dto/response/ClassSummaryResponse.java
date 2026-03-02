package com.fu.clazz.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Summary response for class list (lighter than full ClassResponse)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassSummaryResponse {

    private UUID id;
    private String name;
    private String code;
    private UUID teacherId;
    private UUID courseId;
    private String courseName;
    private String courseLevel;
    private String courseStatus;
    private Boolean isActive;

    private Long memberCount;
}

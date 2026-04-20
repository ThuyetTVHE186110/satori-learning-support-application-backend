package com.fu.clazz.dto.response;

import com.fu.clazz.entity.ClassStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
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
    private UUID teacherId;
    private String teacherName;
    private UUID courseId;
    private String courseName;
    private String courseLevel;
    private String courseStatus;
    private ClassStatus status;
    private Long memberCount;
    private LocalDate expectedEndDate;

}

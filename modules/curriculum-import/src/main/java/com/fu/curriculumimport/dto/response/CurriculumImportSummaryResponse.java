package com.fu.curriculumimport.dto.response;

import com.fu.curriculumimport.entity.CurriculumImport.ImportStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Summary DTO for listing imports (lighter than full response)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CurriculumImportSummaryResponse {

    private UUID id;
    private String originalFilename;
    private ImportStatus status;
    private BigDecimal progressPercent;
    private UUID courseId;
    private String courseName;
    private Integer lessonsCreated;
    private Integer vocabularyCreated;
    private Integer grammarPointsCreated;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}

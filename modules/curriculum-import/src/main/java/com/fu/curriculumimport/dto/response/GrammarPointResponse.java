package com.fu.curriculumimport.dto.response;

import com.fu.course.entity.Course.JlptLevel;
import com.fu.course.entity.GrammarPoint.GrammarCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Response DTO for grammar points extracted from an import
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GrammarPointResponse {

    private UUID id;
    private String pattern;
    private String patternReading;
    private String meaningVi;
    private String meaningEn;
    private String shortExplanation;
    private JlptLevel jlptLevel;
    private GrammarCategory category;
    private Integer difficultyScore;
    private List<String> tags;
}

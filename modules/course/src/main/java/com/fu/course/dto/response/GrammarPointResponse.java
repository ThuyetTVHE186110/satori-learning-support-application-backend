package com.fu.course.dto.response;

import com.fu.course.entity.Course.ContentStatus;
import com.fu.course.entity.Course.JlptLevel;
import com.fu.course.entity.GrammarPoint.GrammarCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Full response DTO for a GrammarPoint entry.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GrammarPointResponse {

    private UUID id;
    private String pattern;
    private String patternReading;
    private String patternRomaji;
    private String meaningVi;
    private String meaningEn;
    private String shortExplanation;
    private String detailedExplanation;
    private JlptLevel jlptLevel;
    private GrammarCategory category;
    private Integer difficultyScore;
    private String structure;
    private String conjugationRules;
    private String formationRules;
    private String exampleSentences;
    private String commonMistakes;
    private String tips;
    private List<String> relatedGrammar;
    private List<String> tags;
    private String audioUrl;
    private ContentStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

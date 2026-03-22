package com.fu.course.dto.request;

import com.fu.course.entity.Course.JlptLevel;
import com.fu.course.entity.GrammarPoint.GrammarCategory;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request DTO for updating a grammar point.
 * All fields are optional — only non-null fields are applied.
 * MANAGER only.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateGrammarPointRequest {

    @Size(max = 255, message = "Pattern must not exceed 255 characters")
    private String pattern;

    private JlptLevel jlptLevel;

    @Size(max = 255, message = "Pattern reading must not exceed 255 characters")
    private String patternReading;

    @Size(max = 255, message = "Pattern romaji must not exceed 255 characters")
    private String patternRomaji;

    private String meaningVi;
    private String meaningEn;
    private String shortExplanation;
    private String detailedExplanation;
    private GrammarCategory category;
    private String structure;
    private String conjugationRules;
    private String formationRules;
    private String exampleSentences;
    private String commonMistakes;
    private String tips;
    private List<String> relatedGrammar;
    private List<String> tags;
    private String audioUrl;
}

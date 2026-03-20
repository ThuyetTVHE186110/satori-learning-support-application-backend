package com.fu.course.dto.request;

import com.fu.course.entity.Course.JlptLevel;
import com.fu.course.entity.GrammarPoint.GrammarCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Request DTO for creating a new grammar point manually.
 * MANAGER only.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateGrammarPointRequest {

    /** Optional: link grammar point to a lesson on creation. */
    private UUID lessonId;

    @NotBlank(message = "Pattern is required")
    @Size(max = 255, message = "Pattern must not exceed 255 characters")
    private String pattern;

    @NotNull(message = "JLPT level is required")
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

    /** JSON object string — grammar structure breakdown */
    private String structure;

    /** JSON object string — conjugation rules */
    private String conjugationRules;

    private String formationRules;

    /** JSON array string of example sentences */
    private String exampleSentences;

    /** JSON array string of common mistakes */
    private String commonMistakes;

    private String tips;
    private List<String> relatedGrammar;
    private List<String> tags;
    private String audioUrl;
}

package com.fu.assignment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.UUID;

/**
 * Request DTO for creating/updating writing content for a WRITING or TRANSLATION assignment.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WriteAssignmentContentRequest {

    /**
     * The assignment's main prompt / question (shown to learner).
     */
    @NotBlank(message = "Prompt is required")
    @Size(max = 5000)
    private String prompt;

    /** Japanese version of the prompt (optional) */
    private String promptJapanese;

    /** Sample/model answer (NOT shown to learner before grading) */
    private String sampleAnswer;
    private String sampleAnswerJapanese;

    /** Min/max word constraints */
    private Integer minWords;
    private Integer maxWords;

    // ── Translation-specific ──────────────────────────────────────────────────

    /**
     * Source text to translate (only for TRANSLATION type).
     * The passage to be translated by the learner.
     */
    private String sourceText;

    /**
     * Translation direction (only for TRANSLATION type).
     * Allowed values: 'JA_TO_VI' or 'VI_TO_JA'
     */
    private String translationDirection;
}

package com.fu.assignment.dto.request;

import com.fu.assignment.entity.AssignmentStatus;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Unified request DTO for creating or updating a full WRITING or TRANSLATION assignment.
 * Contains both assignment metadata and writing content in a single request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SaveFullWritingRequest implements AssignmentMetadataRequest {

    // ── Assignment Metadata ───────────────────────────────────────────────────

    @NotBlank(message = "Title is required")
    @Size(max = 255)
    private String title;

    private String description;
    private String instructions;

    @NotNull(message = "Due date is required")
    @Future(message = "Due date must be in the future")
    private LocalDateTime dueDate;

    private Integer timeLimitMinutes;

    @Builder.Default
    private Boolean lateSubmissionAllowed = false;

    @Builder.Default
    private Integer maxScore = 100;

    @Builder.Default
    private Integer passingScore = 60;

    @Builder.Default
    private Boolean allowRetry = false;

    @Builder.Default
    private Integer maxRetries = 1;

    /**
     * Target status. Use DRAFT for "Ủu bản nháp", PUBLISHED for "Xác nhận giao bài", CLOSED to close.
     * When null, existing status is preserved (no transition).
     */
    private AssignmentStatus status;

    // ── Writing Content ───────────────────────────────────────────────────────

    /** The assignment's main prompt / question (shown to learner) */
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

    /** Source text to translate (only for TRANSLATION type) */
    private String sourceText;

    /**
     * Translation direction (only for TRANSLATION type).
     * Allowed values: 'JA_TO_VI' or 'VI_TO_JA'
     */
    private String translationDirection;
}


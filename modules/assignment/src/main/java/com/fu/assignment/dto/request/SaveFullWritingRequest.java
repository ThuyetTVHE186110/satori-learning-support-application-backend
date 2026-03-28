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

    /**
     * Target status. Use DRAFT for "Ủu bản nháp", PUBLISHED for "Xác nhận giao bài", CLOSED to close.
     * When null, existing status is preserved (no transition).
     */
    private AssignmentStatus status;

    // ── Writing Content ───────────────────────────────────────────────────────

    /**
     * The assignment's main prompt / question (shown to learner)
     */
    @NotBlank(message = "Prompt is required")
    @Size(max = 5000)
    private String prompt;

}


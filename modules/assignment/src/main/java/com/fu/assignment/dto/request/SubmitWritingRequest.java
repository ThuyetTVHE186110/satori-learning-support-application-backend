package com.fu.assignment.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;
import java.util.UUID;

/**
 * Request DTO for learner to submit a writing/translation answer.
 * Learner can provide text answer and/or upload images (e.g. photos of handwritten answers).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmitWritingRequest {
    /**
     * Learner's written answer (for WRITING or TRANSLATION).
     * Optional if images are provided.
     */
    private String writtenAnswer;

    /** Time spent (seconds) on this attempt */
    private Integer timeSpentSeconds;
}

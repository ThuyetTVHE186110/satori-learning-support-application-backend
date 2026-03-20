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
    /** Time spent (seconds) on this attempt */
    private Integer timeSpentSeconds;
}

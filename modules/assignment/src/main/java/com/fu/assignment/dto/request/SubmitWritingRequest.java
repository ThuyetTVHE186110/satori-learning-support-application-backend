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

    @NotNull(message = "Assignment ID is required")
    private UUID assignmentId;

    /**
     * Learner's written answer (for WRITING or TRANSLATION).
     * Optional if images are provided.
     */
    private String writtenAnswer;

    /**
     * URLs of uploaded images (populated by service after Cloudinary upload).
     * Not sent by client directly — images are uploaded as multipart files.
     */
    private List<String> uploadedImageUrls;

    /** Time spent (seconds) on this attempt */
    private Integer timeSpentSeconds;
}

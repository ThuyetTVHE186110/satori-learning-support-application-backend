package com.fu.assignment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Request DTO for pre-submission writing evaluation by AI.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WritingEvaluationRequest {

    /**
     * The assignment ID to get target JLPT level automatically.
     */
    @NotNull(message = "Assignment ID is required")
    private UUID assignmentId;

    /**
     * The prompt text (teacher's prompt) passed from FE.
     */
    @NotBlank(message = "Prompt is required")
    private String prompt;

}

package com.fu.conversation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request for learner to create a personal conversation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateUserConversationRequest {

    @NotBlank(message = "Title is required")
    @Size(max = 255)
    private String title;

    private String scenario;

    private Course.JlptLevel jlptLevel;

    private UUID courseId;
}

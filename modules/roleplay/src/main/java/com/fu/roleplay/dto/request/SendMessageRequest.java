package com.fu.roleplay.dto.request;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendMessageRequest {

    /** Required for normal messages; may be blank when {@code hintRequest = true}. */
    @Size(max = 2000, message = "Message content must not exceed 2000 characters")
    private String content;

    @Builder.Default
    private boolean hintRequest = false;
}

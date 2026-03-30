package com.fu.practice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request gửi câu trả lời cho một câu hỏi trong phiên luyện tập.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmitAnswerRequest {

    @NotBlank(message = "Câu trả lời không được để trống.")
    @Size(max = 500, message = "Câu trả lời không được vượt quá 500 ký tự.")
    private String userAnswer;
}

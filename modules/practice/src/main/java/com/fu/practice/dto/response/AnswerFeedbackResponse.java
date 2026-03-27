package com.fu.practice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Phản hồi sau khi học viên nộp câu trả lời.
 * Lúc này mới reveal correctAnswer và explanation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnswerFeedbackResponse {

    private boolean isCorrect;
    private int score;

    /** Nhận xét ngắn gọn bằng tiếng Việt. */
    private String feedback;

    /** Đáp án đúng — chỉ reveal sau khi nộp. */
    private String correctAnswer;

    /** Giải thích chi tiết bằng tiếng Việt (từ AI hoặc rule-based). */
    private String explanation;

    /** Liệu phiên đã hoàn thành sau câu này không. */
    private boolean sessionCompleted;
}

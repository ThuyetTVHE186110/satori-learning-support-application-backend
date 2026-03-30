package com.fu.practice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Kết quả chi tiết của một câu hỏi trong tổng kết phiên.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ItemResultDto {

    private int itemIndex;
    private String question;
    private String userAnswer;
    private String correctAnswer;
    private Boolean isCorrect;
    private int score;
    /** Giải thích tại sao đáp án đúng (pre-generated khi tạo câu hỏi). */
    private String explanation;
    private String aiFeedback;
}

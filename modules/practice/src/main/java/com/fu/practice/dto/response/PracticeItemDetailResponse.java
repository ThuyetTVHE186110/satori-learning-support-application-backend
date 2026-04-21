package com.fu.practice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.util.List;
import java.util.UUID;

/**
 * Chi tiết một câu hỏi trong phiên luyện tập — bao gồm đáp án, kết quả, feedback.
 * Dùng khi xem lại lịch sử phiên đã hoàn thành.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PracticeItemDetailResponse {

    private UUID itemId;
    private int itemIndex;
    private String itemType;
    private String question;
    private String questionJapanese;
    private String questionJapaneseWithFurigana;
    private List<OptionDto> options;
    private String correctAnswer;
    private String userAnswer;
    private Boolean isCorrect;
    private int score;
    private String hint;
    private String explanation;
    private String aiFeedback;
    private UUID refContentId;
    private String refContentType;
}

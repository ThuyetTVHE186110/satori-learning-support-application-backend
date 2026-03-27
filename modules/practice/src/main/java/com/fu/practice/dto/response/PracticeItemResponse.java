package com.fu.practice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fu.practice.entity.PracticeItemType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Thông tin một câu hỏi luyện tập — KHÔNG expose correctAnswer trước khi nộp.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PracticeItemResponse {

    private UUID id;
    private int itemIndex;
    private PracticeItemType itemType;
    private String question;
    private String questionJapanese;
    private String hint;

    /**
     * Danh sách lựa chọn — isCorrect = null (chỉ set sau khi submit).
     */
    private List<OptionDto> options;

    // correctAnswer KHÔNG có ở đây — chỉ expose qua AnswerFeedbackResponse
}

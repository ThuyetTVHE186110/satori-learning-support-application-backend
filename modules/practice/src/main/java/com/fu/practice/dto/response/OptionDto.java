package com.fu.practice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Một lựa chọn trong câu hỏi trắc nghiệm.
 * isCorrect chỉ được set (true/false) sau khi học viên đã nộp câu trả lời.
 * Trước khi nộp, isCorrect = null.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OptionDto {

    /** Index-based ID (0, 1, 2, ...) để frontend tham chiếu khi submit đáp án. */
    private Integer id;

    private String text;

    /**
     * null trước khi nộp câu trả lời; true/false sau khi nộp.
     */
    private Boolean isCorrect;
}

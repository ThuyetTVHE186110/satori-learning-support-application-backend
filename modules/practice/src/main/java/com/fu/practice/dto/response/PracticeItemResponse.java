package com.fu.practice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fu.practice.entity.PracticeItemType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
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
     * MATCHING: options chứa tất cả LEFT (id 0..N-1) + RIGHT shuffled (id N..2N-1), mỗi item có "side".
     */
    private List<OptionDto> options;

    /**
     * Chỉ non-null cho MATCHING items.
     * Map leftId → rightId (đáp án đúng), cho phép frontend validate client-side
     * mà không cần gọi verify-pair API cho mỗi lần kéo.
     * Key = id của option bên LEFT, Value = id của option bên RIGHT tương ứng.
     */
    private Map<Integer, Integer> correctMapping;
}

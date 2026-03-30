package com.fu.practice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Tổng kết phiên luyện tập sau khi hoàn thành.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionSummaryResponse {

    private UUID sessionId;
    private BigDecimal score;
    private int correctItems;
    private int totalItems;

    /**
     * AI feedback dạng chuỗi văn bản (parse từ JSON aiFeedback).
     * Gồm: tổng kết, điểm mạnh, điểm yếu, gợi ý.
     */
    private String aiFeedback;

    /** Danh sách kết quả chi tiết từng câu. */
    private List<ItemResultDto> items;
}

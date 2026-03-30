package com.fu.practice.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response khi bắt đầu phiên luyện tập mới.
 * Trả về thông tin phiên và danh sách câu hỏi.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StartPracticeResponse {

    private PracticeSessionResponse session;
    private List<PracticeItemResponse> items;
}

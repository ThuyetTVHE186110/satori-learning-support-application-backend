package com.fu.practice.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Phản hồi kiểm tra một cặp MATCHING.
 * Trả về ngay khi learner kéo nối một cặp — không thay đổi trạng thái session.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerifyPairResponse {

    /** Cặp này có đúng không. */
    private boolean correct;

    /**
     * Đã nối đúng TẤT CẢ cặp chưa.
     * true → UI nên tự động gọi submitAnswer để hoàn thành item.
     */
    private boolean allPairsMatched;
}

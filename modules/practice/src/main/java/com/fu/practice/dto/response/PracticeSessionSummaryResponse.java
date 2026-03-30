package com.fu.practice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Tóm tắt phiên luyện tập — dùng trong danh sách lịch sử.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PracticeSessionSummaryResponse {

    private UUID sessionId;
    private UUID lessonId;
    private UUID courseId;
    private String sessionType;
    private String status;
    private BigDecimal score;
    private int correctItems;
    private int totalItems;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}

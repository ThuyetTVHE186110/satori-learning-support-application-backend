package com.fu.practice.dto.response;

import com.fu.practice.entity.PracticeSessionType;
import com.fu.practice.entity.PracticeStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Thông tin tổng quan về phiên luyện tập.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PracticeSessionResponse {

    private UUID sessionId;
    private UUID lessonId;
    private UUID courseId;
    private PracticeSessionType sessionType;
    private PracticeStatus status;
    private int totalItems;
    private int completedItems;
    private int correctItems;
    private BigDecimal score;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}

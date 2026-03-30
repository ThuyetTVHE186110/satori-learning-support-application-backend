package com.fu.conversation.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Thông tin phiên chat giáo trình.
 */
@Data
@Builder
public class ChatSessionResponse {

    private UUID sessionId;
    private String status;       // ACTIVE | COMPLETED | ABANDONED
    private UUID courseId;
    private UUID lessonId;       // null nếu search toàn khóa học
    private String jlptLevel;
    private int messageCount;
    private LocalDateTime createdAt;
    private LocalDateTime lastMessageAt;
}

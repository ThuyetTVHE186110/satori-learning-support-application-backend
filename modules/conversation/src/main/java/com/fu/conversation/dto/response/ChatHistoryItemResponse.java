package com.fu.conversation.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Một tin nhắn trong lịch sử chat.
 */
@Data
@Builder
public class ChatHistoryItemResponse {

    private UUID messageId;
    /** USER hoặc ASSISTANT */
    private String role;
    private String content;
    private LocalDateTime createdAt;
}

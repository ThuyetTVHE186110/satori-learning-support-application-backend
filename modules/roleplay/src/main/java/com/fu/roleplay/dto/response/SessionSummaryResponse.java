package com.fu.roleplay.dto.response;

import com.fu.roleplay.entity.ConversationSession;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionSummaryResponse {

    private UUID id;
    private UUID conversationId;
    private ConversationSession.SessionType sessionType;
    private ConversationSession.SessionStatus status;
    private Integer messageCount;
    private BigDecimal overallScore;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}

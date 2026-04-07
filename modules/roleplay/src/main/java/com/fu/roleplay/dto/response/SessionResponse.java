package com.fu.roleplay.dto.response;

import com.fu.roleplay.entity.ConversationSession;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionResponse {

    private UUID id;
    private UUID userId;
    private UUID conversationId;
    private ConversationSession.SessionType sessionType;
    private ConversationSession.SessionStatus status;
    private Integer messageCount;
    private Integer userMessageCount;
    private BigDecimal missionScore;
    private BigDecimal pronunciationScore;
    private BigDecimal languageScore;
    private BigDecimal overallScore;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    private List<MessageResponse> messages;
    private List<SessionMissionResponse> missions;
}

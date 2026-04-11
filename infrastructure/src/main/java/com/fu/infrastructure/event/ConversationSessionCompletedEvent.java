package com.fu.infrastructure.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

@Getter
public class ConversationSessionCompletedEvent extends ApplicationEvent {

    private final UUID sessionId;
    private final UUID userId;
    private final int messageCount;
    private final int userMessageCount;
    private final int hintsUsed;
    private final int correctionsCount;

    public ConversationSessionCompletedEvent(Object source, UUID sessionId, UUID userId,
                                              int messageCount, int userMessageCount,
                                              int hintsUsed, int correctionsCount) {
        super(source);
        this.sessionId = sessionId;
        this.userId = userId;
        this.messageCount = messageCount;
        this.userMessageCount = userMessageCount;
        this.hintsUsed = hintsUsed;
        this.correctionsCount = correctionsCount;
    }
}

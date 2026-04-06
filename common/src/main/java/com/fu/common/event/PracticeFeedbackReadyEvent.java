package com.fu.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Event fired after AI feedback is generated for a completed practice session.
 */
@Getter
public class PracticeFeedbackReadyEvent extends ApplicationEvent {

    private final UUID sessionId;
    private final UUID userId;
    private final UUID lessonId;
    private final double score;

    public PracticeFeedbackReadyEvent(Object source, UUID sessionId, UUID userId,
                                       UUID lessonId, double score) {
        super(source);
        this.sessionId = sessionId;
        this.userId = userId;
        this.lessonId = lessonId;
        this.score = score;
    }
}

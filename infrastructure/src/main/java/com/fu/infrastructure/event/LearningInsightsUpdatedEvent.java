package com.fu.infrastructure.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

@Getter
public class LearningInsightsUpdatedEvent extends ApplicationEvent {

    private final UUID userId;

    public LearningInsightsUpdatedEvent(Object source, UUID userId) {
        super(source);
        this.userId = userId;
    }
}

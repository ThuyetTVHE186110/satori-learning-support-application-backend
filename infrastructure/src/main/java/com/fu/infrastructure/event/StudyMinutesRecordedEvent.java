package com.fu.infrastructure.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Event fired when a practice session is completed, carrying the duration in minutes.
 * Consumed by the achievement module to track daily study goal progress.
 */
@Getter
public class StudyMinutesRecordedEvent extends ApplicationEvent {

    private final UUID userId;
    private final int durationMinutes;

    public StudyMinutesRecordedEvent(Object source, UUID userId, int durationMinutes) {
        super(source);
        this.userId = userId;
        this.durationMinutes = durationMinutes;
    }
}

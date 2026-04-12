package com.fu.infrastructure.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.LocalTime;
import java.util.UUID;

/**
 * Event fired when a user updates their reminder settings via the learning-preferences API.
 * The notification module listens to this event and syncs dailyReminderTime /
 * dailyReminderEnabled in UserNotificationPreferences (the authoritative source for
 * the daily reminder scheduler).
 *
 * Fields are nullable: null means "no change requested for this field".
 */
@Getter
public class ReminderSettingsUpdatedEvent extends ApplicationEvent {

    private final UUID userId;

    /** New reminder time, or {@code null} if not changed. */
    private final LocalTime reminderTime;

    /** New reminder enabled flag, or {@code null} if not changed. */
    private final Boolean reminderEnabled;

    public ReminderSettingsUpdatedEvent(Object source, UUID userId,
                                        LocalTime reminderTime, Boolean reminderEnabled) {
        super(source);
        this.userId = userId;
        this.reminderTime = reminderTime;
        this.reminderEnabled = reminderEnabled;
    }
}

package com.fu.infrastructure.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Event fired when a user joins a class.
 * Triggers welcome notification.
 */
@Getter
public class ClassJoinedEvent extends ApplicationEvent {

    private final UUID userId;
    private final UUID classId;
    private final String className;
    private final String teacherName;

    public ClassJoinedEvent(Object source, UUID userId, UUID classId,
                            String className, String teacherName) {
        super(source);
        this.userId = userId;
        this.classId = classId;
        this.className = className;
        this.teacherName = teacherName;
    }
}

package com.fu.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.List;
import java.util.UUID;

/**
 * Event fired when an assignment is created and published.
 * Triggers notification to class members.
 */
@Getter
public class AssignmentCreatedEvent extends ApplicationEvent {

    private final UUID assignmentId;
    private final UUID classId;
    private final String assignmentTitle;
    private final String className;
    private final UUID createdBy;
    private final List<UUID> memberIds;

    public AssignmentCreatedEvent(Object source, UUID assignmentId, UUID classId,
                                   String assignmentTitle, String className, UUID createdBy,
                                   List<UUID> memberIds) {
        super(source);
        this.assignmentId = assignmentId;
        this.classId = classId;
        this.assignmentTitle = assignmentTitle;
        this.className = className;
        this.createdBy = createdBy;
        this.memberIds = memberIds != null ? memberIds : List.of();
    }
}

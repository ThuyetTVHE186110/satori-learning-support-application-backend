package com.fu.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Event fired when a student submits an assignment.
 * Triggers notification to the teacher.
 */
@Getter
public class AssignmentSubmittedEvent extends ApplicationEvent {

    private final UUID assignmentId;
    private final UUID submissionId;
    private final UUID studentId;
    private final String studentName;
    private final String assignmentTitle;
    private final UUID teacherId;

    public AssignmentSubmittedEvent(Object source, UUID assignmentId, UUID submissionId,
                                     UUID studentId, String studentName,
                                     String assignmentTitle, UUID teacherId) {
        super(source);
        this.assignmentId = assignmentId;
        this.submissionId = submissionId;
        this.studentId = studentId;
        this.studentName = studentName;
        this.assignmentTitle = assignmentTitle;
        this.teacherId = teacherId;
    }
}

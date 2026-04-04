package com.fu.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Event fired when an assignment submission is graded.
 * Triggers notification to the student.
 */
@Getter
public class AssignmentGradedEvent extends ApplicationEvent {

    private final UUID assignmentId;
    private final UUID submissionId;
    private final UUID studentId;
    private final String assignmentTitle;
    private final Double score;
    private final Double maxScore;

    public AssignmentGradedEvent(Object source, UUID assignmentId, UUID submissionId,
                                  UUID studentId, String assignmentTitle, 
                                  Double score, Double maxScore) {
        super(source);
        this.assignmentId = assignmentId;
        this.submissionId = submissionId;
        this.studentId = studentId;
        this.assignmentTitle = assignmentTitle;
        this.score = score;
        this.maxScore = maxScore;
    }
}

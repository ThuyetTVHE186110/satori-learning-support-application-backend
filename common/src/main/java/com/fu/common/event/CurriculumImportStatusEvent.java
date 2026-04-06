package com.fu.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Event fired when a curriculum import changes status.
 * Currently handles PENDING_APPROVAL and FAILED transitions.
 */
@Getter
public class CurriculumImportStatusEvent extends ApplicationEvent {

    public enum Status { PENDING_APPROVAL, FAILED }

    private final UUID importId;
    private final UUID managerId;
    private final String importName;
    private final Status status;
    /** Non-null when status=FAILED */
    private final String errorMessage;

    public CurriculumImportStatusEvent(Object source, UUID importId, UUID managerId,
                                        String importName, Status status, String errorMessage) {
        super(source);
        this.importId = importId;
        this.managerId = managerId;
        this.importName = importName;
        this.status = status;
        this.errorMessage = errorMessage;
    }

    public static CurriculumImportStatusEvent pendingApproval(Object source, UUID importId,
                                                               UUID managerId, String importName) {
        return new CurriculumImportStatusEvent(source, importId, managerId, importName,
                Status.PENDING_APPROVAL, null);
    }

    public static CurriculumImportStatusEvent failed(Object source, UUID importId,
                                                      UUID managerId, String importName,
                                                      String errorMessage) {
        return new CurriculumImportStatusEvent(source, importId, managerId, importName,
                Status.FAILED, errorMessage);
    }
}

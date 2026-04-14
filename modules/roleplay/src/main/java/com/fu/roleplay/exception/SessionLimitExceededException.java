package com.fu.roleplay.exception;

import com.fu.common.exception.BusinessException;
import com.fu.roleplay.dto.response.SessionSummaryResponse;
import lombok.Getter;

import java.util.List;

/**
 * Thrown when a user attempts to start a new roleplay session but has already
 * reached the maximum active-session limit. Carries the list of active sessions
 * so the frontend can display a dialog letting the user choose which to abandon.
 */
@Getter
public class SessionLimitExceededException extends BusinessException {

    private final List<SessionSummaryResponse> activeSessions;

    public SessionLimitExceededException(int maxSessions, List<SessionSummaryResponse> activeSessions) {
        super("SESSION_LIMIT", "Maximum active sessions reached (" + maxSessions + "). Abandon or complete an existing session to start a new one.");
        this.activeSessions = activeSessions;
    }
}

package com.fu.common.port;

import java.util.UUID;

/**
 * Port interface allowing non-user modules to query basic user information
 * without creating a direct module-to-module dependency.
 *
 * Implemented in the user module ({@code UserQueryPortImpl}).
 */
public interface UserQueryPort {

    /**
     * Returns the email address of a user, or {@code null} if the user does not exist.
     */
    String getUserEmail(UUID userId);

    /**
     * Returns the user's daily study goal in minutes.
     * Returns 30 (default) if the user has no learning preferences row yet.
     */
    Integer getDailyStudyGoalMinutes(UUID userId);
}

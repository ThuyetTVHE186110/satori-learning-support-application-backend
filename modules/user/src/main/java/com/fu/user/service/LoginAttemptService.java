package com.fu.user.service;

import com.fu.user.entity.User;

/**
 * Service to handle login attempts, isolated to ensure transactions commit
 * even if the main authentication process throws an exception.
 */
public interface LoginAttemptService {

    /**
     * Handles a failed login attempt for a user.
     * Increments the failed login count and locks the account if the threshold is
     * reached.
     *
     * @param user The user who failed to login
     */
    void handleFailedLogin(User user);
}

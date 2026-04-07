package com.fu.achievement.service;

import java.util.UUID;

/**
 * Interface for modules to provide activity counts to the achievement system.
 * This prevents circular dependencies between achievement and other modules.
 */
public interface ActivityCountProvider {
    
    /**
     * Get the total count of completed practice sessions for a user.
     * 
     * @param userId the user ID
     * @return the count of completed practice sessions, or 0 if not applicable
     */
    default int getCompletedPracticeCount(UUID userId) {
        return 0;
    }
    
    /**
     * Get the total count of completed speaking sessions for a user.
     * 
     * @param userId the user ID
     * @return the count of completed speaking sessions, or 0 if not applicable
     */
    default int getCompletedSpeakingCount(UUID userId) {
        return 0;
    }
}

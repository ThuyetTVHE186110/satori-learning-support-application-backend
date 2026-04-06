package com.fu.roleplay.service.impl;

import com.fu.achievement.service.ActivityCountProvider;
import com.fu.roleplay.entity.ConversationSession;
import com.fu.roleplay.repository.ConversationSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Provides speaking session counts to the achievement system.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoleplayActivityCountProvider implements ActivityCountProvider {
    
    private final ConversationSessionRepository sessionRepository;
    
    @Override
    public int getCompletedSpeakingCount(UUID userId) {
        try {
            long count = sessionRepository.countByUserIdAndStatus(
                    userId, 
                    ConversationSession.SessionStatus.COMPLETED.name());
            log.debug("Retrieved speaking count for user {}: {}", userId, count);
            return (int) count;
        } catch (Exception e) {
            log.error("Error getting speaking count for user {}", userId, e);
            return 0;
        }
    }
}

package com.fu.practice.service.impl;

import com.fu.achievement.service.ActivityCountProvider;
import com.fu.practice.entity.PracticeStatus;
import com.fu.practice.repository.PracticeSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Provides practice session counts to the achievement system.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PracticeActivityCountProvider implements ActivityCountProvider {
    
    private final PracticeSessionRepository sessionRepository;
    
    @Override
    public int getCompletedPracticeCount(UUID userId) {
        try {
            long count = sessionRepository.countByUserIdAndStatus(userId, PracticeStatus.COMPLETED);
            log.debug("Retrieved practice count for user {}: {}", userId, count);
            return (int) count;
        } catch (Exception e) {
            log.error("Error getting practice count for user {}", userId, e);
            return 0;
        }
    }
}

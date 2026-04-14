package com.fu.user.service.impl;

import com.fu.common.port.UserQueryPort;
import com.fu.user.entity.UserLearningPreferences;
import com.fu.user.repository.UserLearningPreferencesRepository;
import com.fu.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Implementation of {@link UserQueryPort} for use by other modules
 * that need basic user information without a direct module dependency.
 */
@Service
@RequiredArgsConstructor
public class UserQueryPortImpl implements UserQueryPort {

    private final UserRepository userRepository;
    private final UserLearningPreferencesRepository learningPreferencesRepository;

    @Override
    @Transactional(readOnly = true)
    public String getUserEmail(UUID userId) {
        return userRepository.findById(userId)
                .map(user -> user.getEmail())
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public Integer getDailyStudyGoalMinutes(UUID userId) {
        return learningPreferencesRepository.findByUserId(userId)
                .map(UserLearningPreferences::getDailyStudyGoalMinutes)
                .orElse(30);
    }
}

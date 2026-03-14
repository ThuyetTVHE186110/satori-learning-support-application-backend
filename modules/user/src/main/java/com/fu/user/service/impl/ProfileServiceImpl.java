package com.fu.user.service.impl;

import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.user.dto.*;
import com.fu.user.entity.User;
import com.fu.user.entity.UserLearningPreferences;
import com.fu.user.entity.UserStatus;
import com.fu.user.mapper.UserMapper;
import com.fu.user.repository.UserLearningPreferencesRepository;
import com.fu.user.repository.UserRepository;
import com.fu.user.service.ProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProfileServiceImpl implements ProfileService {

    private final UserRepository userRepository;
    private final UserLearningPreferencesRepository preferencesRepository;
    private final UserMapper userMapper;

    // ── GET /api/v1/profile ──────────────────────────────────────────────────

    @Override
    public UserProfileResponse getMyProfile(UUID userId) {
        log.debug("Building enriched profile for user: {}", userId);

        User user = findActiveUser(userId);
        Optional<UserLearningPreferences> prefsOpt = preferencesRepository.findByUserId(userId);

        LearningPreferencesResponse prefs = prefsOpt
                .map(LearningPreferencesResponse::from)
                .orElse(LearningPreferencesResponse.defaultPreferences());

        return UserProfileResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .displayName(user.getDisplayName())
                .fullName(user.getFullName())
                .avatarUrl(user.getAvatarUrl())
                .phoneNumber(user.getPhoneNumber())
                .dateOfBirth(user.getDateOfBirth())
                .gender(user.getGender())
                .role(user.getRole())
                .status(user.getStatus())
                .authProvider(user.getAuthProvider())
                .emailVerified(user.getEmailVerifiedAt() != null)
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .lastLoginAt(user.getLastLoginAt())
                .learningPreferences(prefs)
                // TODO: populate when class management module is available
                .enrolledClasses(Collections.emptyList())
                .build();
    }

    // ── PUT /api/v1/profile ──────────────────────────────────────────────────

    @Override
    @Transactional
    public UserResponse updateMyProfile(UUID userId, UpdateProfileRequest request) {
        log.info("User {} updating their own profile", userId);

        User user = findActiveUser(userId);

        if (request.getEmail() != null && !request.getEmail().equals(user.getEmail())) {
            userRepository.findByEmail(request.getEmail()).ifPresent(existing -> {
                if (!existing.getId().equals(userId)) {
                    throw new BusinessException("EMAIL_EXISTS", "Email này đã được sử dụng. Vui lòng nhập email khác");
                }
            });
            user.setEmail(request.getEmail());
        }
        if (request.getFirstName() != null)   user.setFirstName(request.getFirstName());
        if (request.getLastName() != null)    user.setLastName(request.getLastName());
        if (request.getDisplayName() != null) user.setDisplayName(request.getDisplayName());
        if (request.getPhoneNumber() != null) user.setPhoneNumber(request.getPhoneNumber());
        if (request.getDateOfBirth() != null) user.setDateOfBirth(request.getDateOfBirth());
        if (request.getGender() != null)      user.setGender(request.getGender());
        if (request.getAvatarUrl() != null)   user.setAvatarUrl(request.getAvatarUrl());

        return userMapper.toResponse(userRepository.save(user));
    }

    // ── GET /api/v1/profile/learning-preferences ─────────────────────────────

    @Override
    public LearningPreferencesResponse getLearningPreferences(UUID userId) {
        findActiveUser(userId); // validate user exists
        return preferencesRepository.findByUserId(userId)
                .map(LearningPreferencesResponse::from)
                .orElse(LearningPreferencesResponse.defaultPreferences());
    }

    // ── PUT /api/v1/profile/learning-preferences ─────────────────────────────

    @Override
    @Transactional
    public LearningPreferencesResponse updateLearningPreferences(UUID userId,
                                                                  UpdateLearningPreferencesRequest request) {
        log.info("User {} updating learning preferences", userId);

        User user = findActiveUser(userId);

        UserLearningPreferences prefs = preferencesRepository.findByUserId(userId)
                .orElseGet(() -> {
                    log.debug("Creating new preferences record for user: {}", userId);
                    return UserLearningPreferences.builder()
                            .user(user)
                            .dailyStudyGoalMinutes(30)
                            .learningPace("normal")
                            .preferredFormality("polite")
                            .streakReminderEnabled(true)
                            .reminderTime(LocalTime.of(20, 0))
                            .build();
                });

        if (request.getTargetJlptLevel() != null)     prefs.setTargetJlptLevel(request.getTargetJlptLevel());
        if (request.getDailyStudyGoalMinutes() != null) prefs.setDailyStudyGoalMinutes(request.getDailyStudyGoalMinutes());
        if (request.getPreferredStudyTime() != null)  prefs.setPreferredStudyTime(request.getPreferredStudyTime());
        if (request.getLearningPace() != null)        prefs.setLearningPace(request.getLearningPace());
        if (request.getPreferredFormality() != null)  prefs.setPreferredFormality(request.getPreferredFormality());
        if (request.getConversationStyle() != null)   prefs.setConversationStyle(request.getConversationStyle());
        if (request.getStreakReminderEnabled() != null) prefs.setStreakReminderEnabled(request.getStreakReminderEnabled());
        if (request.getReminderTime() != null)        prefs.setReminderTime(request.getReminderTime());
        if (request.getPreferredTopics() != null) {
            prefs.setPreferredTopics(request.getPreferredTopics().toArray(String[]::new));
        }

        UserLearningPreferences saved = preferencesRepository.save(prefs);
        log.info("Learning preferences updated for user: {}", userId);

        return LearningPreferencesResponse.from(saved);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private User findActiveUser(UUID userId) {
        return userRepository.findById(userId)
                .filter(u -> u.getDeletedAt() == null && u.getStatus() != UserStatus.DELETED)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
    }
}

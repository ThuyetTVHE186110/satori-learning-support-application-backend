package com.fu.user.service.impl;

import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.common.port.NotificationPreferencesQueryPort;
import com.fu.infrastructure.cloudinary.CloudinaryService;
import com.fu.infrastructure.event.ReminderSettingsUpdatedEvent;
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
import org.springframework.web.multipart.MultipartFile;

import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
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
    private final CloudinaryService cloudinaryService;
    private final NotificationPreferencesQueryPort notificationPreferencesQueryPort;
    private final ApplicationEventPublisher eventPublisher;

    private static final String AVATAR_FOLDER = "satori/avatars";

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
                .emailVerified(user.getEmailVerifiedAt() != null)
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .lastLoginAt(user.getLastLoginAt())
                .learningPreferences(prefs)
                .enrolledClasses(resolveActiveEnrollment(userId))
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
                            .preferredFormality("polite")
                            .build();
                });

        if (request.getDailyStudyGoalMinutes() != null) prefs.setDailyStudyGoalMinutes(request.getDailyStudyGoalMinutes());
        if (request.getPreferredFormality() != null)  prefs.setPreferredFormality(request.getPreferredFormality());
        if (request.getPreferredTopics() != null) {
            prefs.setPreferredTopics(request.getPreferredTopics().toArray(String[]::new));
        }

        UserLearningPreferences saved = preferencesRepository.save(prefs);
        log.info("Learning preferences updated for user: {}", userId);

        // Forward reminder settings to notification_prefs (source of truth for the scheduler)
        if (request.getReminderTime() != null || request.getStreakReminderEnabled() != null) {
            eventPublisher.publishEvent(new ReminderSettingsUpdatedEvent(
                    this, userId, request.getReminderTime(), request.getStreakReminderEnabled()));
        }

        return LearningPreferencesResponse.from(saved);
    }

    // ── POST /api/v1/profile/avatar ────────────────────────────────────────

    @Override
    @Transactional
    public UserResponse uploadAvatar(UUID userId, MultipartFile file) {
        log.info("User {} uploading avatar", userId);

        User user = findActiveUser(userId);

        // Delete old avatar from Cloudinary if exists
        deleteOldAvatar(user.getAvatarUrl());

        String newUrl = cloudinaryService.uploadImage(file, AVATAR_FOLDER);
        user.setAvatarUrl(newUrl);

        return userMapper.toResponse(userRepository.save(user));
    }

    // ── DELETE /api/v1/profile/avatar ───────────────────────────────────────

    @Override
    @Transactional
    public void deleteAvatar(UUID userId) {
        log.info("User {} deleting avatar", userId);

        User user = findActiveUser(userId);

        if (user.getAvatarUrl() == null) {
            return; // nothing to delete
        }

        deleteOldAvatar(user.getAvatarUrl());
        user.setAvatarUrl(null);
        userRepository.save(user);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void deleteOldAvatar(String avatarUrl) {
        if (avatarUrl != null && !avatarUrl.isBlank()) {
            String publicId = cloudinaryService.extractPublicId(avatarUrl);
            if (publicId != null) {
                cloudinaryService.deleteImage(publicId);
            }
        }
    }

    /**
     * Maps the single active class enrollment (if any) to ClassEnrollmentResponse.
     * Business rule: 1 learner has at most 1 active class at any point in time.
     * Returns a one-element list (or empty) to keep the enrolledClasses field type stable.
     */
    private List<ClassEnrollmentResponse> resolveActiveEnrollment(UUID userId) {
        List<Object[]> rows = userRepository.findActiveEnrollment(userId);
        if (rows.isEmpty()) return Collections.emptyList();

        Object[] row = rows.get(0);
        ClassEnrollmentResponse enrollment = ClassEnrollmentResponse.builder()
                .classId(toUuid(row[0]))
                .className((String) row[1])
                .courseId(toUuid(row[2]))
                .courseName((String) row[3])
                .startDate(toLocalDateTime(row[4]))
                .endDate(toLocalDateTime(row[5]))
                .status((String) row[6])
                .jlptLevel(row.length > 7 ? (String) row[7] : null)
                .role(null)
                .build();
        return List.of(enrollment);
    }

    private static UUID toUuid(Object val) {
        if (val == null) return null;
        if (val instanceof UUID u) return u;
        return UUID.fromString(val.toString());
    }

    private static LocalDateTime toLocalDateTime(Object val) {
        if (val == null) return null;
        if (val instanceof LocalDateTime ldt) return ldt;
        if (val instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        return null;
    }

    // ── GET /api/v1/profile/settings ─────────────────────────────────────────

    @Override
    public UserSettingsResponse getSettings(UUID userId) {
        UserProfileResponse profile = getMyProfile(userId);
        return UserSettingsResponse.builder()
                .profile(profile)
                .notificationPreferences(notificationPreferencesQueryPort.getSnapshot(userId))
                .build();
    }

    private User findActiveUser(UUID userId) {
        return userRepository.findById(userId)
                .filter(u -> u.getDeletedAt() == null && u.getStatus() != UserStatus.DELETED)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
    }
}

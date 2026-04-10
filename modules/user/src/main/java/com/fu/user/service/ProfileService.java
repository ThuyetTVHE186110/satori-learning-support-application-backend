package com.fu.user.service;

import com.fu.user.dto.LearningPreferencesResponse;
import com.fu.user.dto.UpdateLearningPreferencesRequest;
import com.fu.user.dto.UpdateProfileRequest;
import com.fu.user.dto.UserProfileResponse;
import com.fu.user.dto.UserResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Service for self-service profile operations (authenticated user acting on their own account).
 */
public interface ProfileService {

    /**
     * Get the enriched profile of the currently authenticated user.
     * Includes learning preferences and enrolled classes.
     */
    UserProfileResponse getMyProfile(UUID userId);

    /**
     * Update basic profile fields (name, phone, avatar, gender, etc.).
     */
    UserResponse updateMyProfile(UUID userId, UpdateProfileRequest request);

    /**
     * Get the learning preferences for the authenticated user.
     * Returns defaults if no preferences have been saved yet.
     */
    LearningPreferencesResponse getLearningPreferences(UUID userId);

    /**
     * Create or update learning preferences.
     * Only provided (non-null) fields are applied.
     */
    LearningPreferencesResponse updateLearningPreferences(UUID userId, UpdateLearningPreferencesRequest request);

    /**
     * Upload or replace the user's avatar image.
     * Old avatar is deleted from Cloudinary before uploading the new one.
     *
     * @return updated UserResponse with new avatarUrl
     */
    UserResponse uploadAvatar(UUID userId, MultipartFile file);

    /**
     * Delete the user's avatar and reset avatarUrl to null.
     */
    void deleteAvatar(UUID userId);
}

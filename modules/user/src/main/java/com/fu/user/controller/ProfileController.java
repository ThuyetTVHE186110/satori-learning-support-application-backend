package com.fu.user.controller;

import com.fu.common.response.ApiResponse;
import com.fu.user.dto.*;
import com.fu.user.service.ProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST Controller for authenticated user's own profile.
 *
 * All endpoints require a valid JWT (isAuthenticated()).
 *
 * Endpoints:
 *   GET  /api/v1/profile                          → enriched profile (user info + learning prefs)
 *   PUT  /api/v1/profile                          → update basic profile fields
 *   GET  /api/v1/profile/learning-preferences     → learning preferences only
 *   PUT  /api/v1/profile/learning-preferences     → create / update learning preferences
 */
@RestController
@RequestMapping("/api/v1/profile")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ProfileController {

    private final ProfileService profileService;

    /**
     * GET /api/v1/profile
     * Returns the full profile including learning preferences and enrolled classes.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<UserProfileResponse>> getMyProfile(
            @AuthenticationPrincipal UserDetails userDetails) {
        UUID userId = extractUserId(userDetails);
        return ResponseEntity.ok(ApiResponse.success(profileService.getMyProfile(userId)));
    }

    /**
     * PUT /api/v1/profile
     * Update basic profile information (name, avatar, phone, gender, etc.).
     */
    @PutMapping
    public ResponseEntity<ApiResponse<UserResponse>> updateMyProfile(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody UpdateProfileRequest request) {
        UUID userId = extractUserId(userDetails);
        UserResponse response = profileService.updateMyProfile(userId, request);
        return ResponseEntity.ok(ApiResponse.success("Cập nhật thông tin thành công", response));
    }

    /**
     * GET /api/v1/profile/learning-preferences
     * Returns learning preferences only (useful for settings screens).
     */
    @GetMapping("/learning-preferences")
    public ResponseEntity<ApiResponse<LearningPreferencesResponse>> getLearningPreferences(
            @AuthenticationPrincipal UserDetails userDetails) {
        UUID userId = extractUserId(userDetails);
        return ResponseEntity.ok(ApiResponse.success(profileService.getLearningPreferences(userId)));
    }

    /**
     * PUT /api/v1/profile/learning-preferences
     * Create or update learning preferences. Only non-null fields are applied.
     */
    @PutMapping("/learning-preferences")
    public ResponseEntity<ApiResponse<LearningPreferencesResponse>> updateLearningPreferences(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody UpdateLearningPreferencesRequest request) {
        UUID userId = extractUserId(userDetails);
        LearningPreferencesResponse response = profileService.updateLearningPreferences(userId, request);
        return ResponseEntity.ok(ApiResponse.success("Cập nhật tùy chỉnh học tập thành công", response));
    }

    private UUID extractUserId(UserDetails userDetails) {
        return UUID.fromString(userDetails.getUsername());
    }
}

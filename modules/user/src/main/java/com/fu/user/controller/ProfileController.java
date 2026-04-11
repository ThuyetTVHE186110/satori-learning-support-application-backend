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
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * REST Controller for authenticated user's own profile.
 *
 * All endpoints require a valid JWT (isAuthenticated()).
 *
 * Endpoints:
 *   GET    /api/v1/profile                          → enriched profile (user info + learning prefs)
 *   PUT    /api/v1/profile                          → update basic profile fields
 *   POST   /api/v1/profile/avatar                   → upload avatar image (multipart)
 *   DELETE /api/v1/profile/avatar                   → remove avatar
 *   GET    /api/v1/profile/learning-preferences     → learning preferences only
 *   PUT    /api/v1/profile/learning-preferences     → create / update learning preferences
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
     * POST /api/v1/profile/avatar
     * Upload or replace the user's avatar image.
     */
    @PostMapping(value = "/avatar", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<UserResponse>> uploadAvatar(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam("file") MultipartFile file) {
        UUID userId = extractUserId(userDetails);
        UserResponse response = profileService.uploadAvatar(userId, file);
        return ResponseEntity.ok(ApiResponse.success("Cập nhật ảnh đại diện thành công", response));
    }

    /**
     * DELETE /api/v1/profile/avatar
     * Remove the user's avatar (reset to default).
     */
    @DeleteMapping("/avatar")
    public ResponseEntity<ApiResponse<Void>> deleteAvatar(
            @AuthenticationPrincipal UserDetails userDetails) {
        UUID userId = extractUserId(userDetails);
        profileService.deleteAvatar(userId);
        return ResponseEntity.ok(ApiResponse.success("Xóa ảnh đại diện thành công", null));
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

package com.fu.user.controller;

import com.fu.common.response.ApiResponse;
import com.fu.user.dto.UpdateProfileRequest;
import com.fu.user.dto.UserResponse;
import com.fu.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST Controller for User Profile management.
 * Allows authenticated users to view and update their own profile.
 * 
 * All endpoints require authentication.
 */
@RestController
@RequestMapping("/api/v1/profile")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ProfileController {

    private final UserService userService;

    /**
     * Get current user's profile.
     * 
     * GET /api/v1/profile
     * 
     * @return Current user's profile
     */
    @GetMapping
    public ResponseEntity<ApiResponse<UserResponse>> getMyProfile(
            @AuthenticationPrincipal UserDetails userDetails) {
        UUID userId = extractUserId(userDetails);
        UserResponse response = userService.getMyProfile(userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Update current user's profile.
     * 
     * PUT /api/v1/profile
     * 
     * @param request Updated profile information
     * @return Updated user profile
     */
    @PutMapping
    public ResponseEntity<ApiResponse<UserResponse>> updateMyProfile(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody UpdateProfileRequest request) {
        UUID userId = extractUserId(userDetails);
        UserResponse response = userService.updateMyProfile(userId, request);
        return ResponseEntity.ok(ApiResponse.success("Cập nhật thông tin thành công", response));
    }

    /**
     * Extract UUID from UserDetails.
     * The username is expected to be the user's UUID string.
     */
    private UUID extractUserId(UserDetails userDetails) {
        return UUID.fromString(userDetails.getUsername());
    }
}

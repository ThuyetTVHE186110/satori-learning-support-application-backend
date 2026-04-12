package com.fu.notification.controller;

import com.fu.common.response.ApiResponse;
import com.fu.notification.dto.*;
import com.fu.notification.service.DeviceTokenService;
import com.fu.notification.service.NotificationPreferencesService;
import com.fu.notification.service.NotificationService;
import com.fu.notification.sse.NotificationSseEmitterRegistry;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

/**
 * REST Controller for notification management.
 * All endpoints require authentication.
 *
 * Endpoints:
 *   POST   /api/v1/notifications/device-token           → Register FCM device token
 *   DELETE /api/v1/notifications/device-token           → Unregister device token
 *   GET    /api/v1/notifications/devices                → List active devices
 *   GET    /api/v1/notifications                        → Get notifications (paginated)
 *   GET    /api/v1/notifications/unread                 → Get unread notifications
 *   GET    /api/v1/notifications/unread-count           → Get unread count
 *   POST   /api/v1/notifications/mark-read              → Mark notifications as read
 *   POST   /api/v1/notifications/mark-all-read          → Mark all as read
 *   DELETE /api/v1/notifications/{id}                   → Delete a notification
 *   GET    /api/v1/notifications/preferences            → Get notification preferences
 *   PUT    /api/v1/notifications/preferences            → Update notification preferences
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class NotificationController {

    private final NotificationService notificationService;
    private final DeviceTokenService deviceTokenService;
    private final NotificationPreferencesService preferencesService;
    private final NotificationSseEmitterRegistry sseRegistry;

    // ── Device Token Management ───────────────────────────────────────────────

    /**
     * Register a device token for push notifications.
     */
    @PostMapping("/device-token")
    public ResponseEntity<ApiResponse<DeviceTokenResponse>> registerDeviceToken(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody RegisterDeviceTokenRequest request) {
        UUID userId = extractUserId(userDetails);
        DeviceTokenResponse response = deviceTokenService.registerToken(userId, request);
        return ResponseEntity.ok(ApiResponse.success("Device registered successfully", response));
    }

    /**
     * Unregister a device token.
     */
    @DeleteMapping("/device-token")
    public ResponseEntity<ApiResponse<Void>> unregisterDeviceToken(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam String fcmToken) {
        UUID userId = extractUserId(userDetails);
        deviceTokenService.unregisterToken(userId, fcmToken);
        return ResponseEntity.ok(ApiResponse.success("Device unregistered successfully", null));
    }

    /**
     * Get all active devices for the current user.
     */
    @GetMapping("/devices")
    public ResponseEntity<ApiResponse<List<DeviceTokenResponse>>> getActiveDevices(
            @AuthenticationPrincipal UserDetails userDetails) {
        UUID userId = extractUserId(userDetails);
        List<DeviceTokenResponse> devices = deviceTokenService.getActiveTokens(userId);
        return ResponseEntity.ok(ApiResponse.success(devices));
    }

    // ── Notification Management ───────────────────────────────────────────────

    /**
     * Get all notifications for the current user (paginated).
     */
    @GetMapping
    public ResponseEntity<ApiResponse<Page<NotificationResponse>>> getNotifications(
            @AuthenticationPrincipal UserDetails userDetails,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        UUID userId = extractUserId(userDetails);
        Page<NotificationResponse> notifications = notificationService.getNotifications(userId, pageable);
        return ResponseEntity.ok(ApiResponse.success(notifications));
    }

    /**
     * Get unread notifications for the current user (paginated).
     */
    @GetMapping("/unread")
    public ResponseEntity<ApiResponse<Page<NotificationResponse>>> getUnreadNotifications(
            @AuthenticationPrincipal UserDetails userDetails,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        UUID userId = extractUserId(userDetails);
        Page<NotificationResponse> notifications = notificationService.getUnreadNotifications(userId, pageable);
        return ResponseEntity.ok(ApiResponse.success(notifications));
    }

    /**
     * Get unread notification count.
     */
    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<UnreadCountResponse>> getUnreadCount(
            @AuthenticationPrincipal UserDetails userDetails) {
        UUID userId = extractUserId(userDetails);
        long count = notificationService.getUnreadCount(userId);
        return ResponseEntity.ok(ApiResponse.success(UnreadCountResponse.of(count)));
    }

    /**
     * Mark specific notifications as read.
     */
    @PostMapping("/mark-read")
    public ResponseEntity<ApiResponse<Integer>> markAsRead(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody MarkAsReadRequest request) {
        UUID userId = extractUserId(userDetails);
        int count = notificationService.markAsRead(userId, request.getNotificationIds());
        return ResponseEntity.ok(ApiResponse.success("Marked " + count + " notifications as read", count));
    }

    /**
     * Mark all notifications as read.
     */
    @PostMapping("/mark-all-read")
    public ResponseEntity<ApiResponse<Integer>> markAllAsRead(
            @AuthenticationPrincipal UserDetails userDetails) {
        UUID userId = extractUserId(userDetails);
        int count = notificationService.markAllAsRead(userId);
        return ResponseEntity.ok(ApiResponse.success("Marked " + count + " notifications as read", count));
    }

    /**
     * Delete a notification.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteNotification(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable UUID id) {
        UUID userId = extractUserId(userDetails);
        notificationService.deleteNotification(userId, id);
        return ResponseEntity.ok(ApiResponse.success("Notification deleted", null));
    }

    // ── Preferences Management ────────────────────────────────────────────────

    /**
     * Get notification preferences.
     */
    @GetMapping("/preferences")
    public ResponseEntity<ApiResponse<NotificationPreferencesResponse>> getPreferences(
            @AuthenticationPrincipal UserDetails userDetails) {
        UUID userId = extractUserId(userDetails);
        NotificationPreferencesResponse response = preferencesService.getPreferences(userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * Update notification preferences.
     */
    @PutMapping("/preferences")
    public ResponseEntity<ApiResponse<NotificationPreferencesResponse>> updatePreferences(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody UpdateNotificationPreferencesRequest request) {
        UUID userId = extractUserId(userDetails);
        NotificationPreferencesResponse response = preferencesService.updatePreferences(userId, request);
        return ResponseEntity.ok(ApiResponse.success("Preferences updated successfully", response));
    }

    // ── Real-time SSE ─────────────────────────────────────────────────────────

    /**
     * GET /api/v1/notifications/stream
     * Opens a Server-Sent Events stream. The client receives a {@code notification}
     * event for each new notification saved to the database while the connection
     * is open. Reconnect automatically on timeout (every 5 minutes).
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamNotifications(
            @AuthenticationPrincipal UserDetails userDetails) {
        UUID userId = extractUserId(userDetails);
        return sseRegistry.register(userId);
    }

    // ── Helper Methods ────────────────────────────────────────────────────────

    private UUID extractUserId(UserDetails userDetails) {
        return UUID.fromString(userDetails.getUsername());
    }
}

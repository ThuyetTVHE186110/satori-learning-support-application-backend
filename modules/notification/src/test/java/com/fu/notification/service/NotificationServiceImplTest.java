package com.fu.notification.service;

import com.fu.notification.dto.SendNotificationRequest;
import com.fu.notification.entity.Notification;
import com.fu.notification.entity.NotificationPriority;
import com.fu.notification.entity.NotificationType;
import com.fu.notification.entity.UserDeviceToken;
import com.fu.notification.entity.UserNotificationPreferences;
import com.fu.notification.repository.NotificationRepository;
import com.fu.notification.repository.UserDeviceTokenRepository;
import com.fu.notification.service.impl.NotificationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationServiceImpl")
class NotificationServiceImplTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private UserDeviceTokenRepository deviceTokenRepository;
    @Mock private DeviceTokenService deviceTokenService;
    @Mock private NotificationPreferencesService preferencesService;
    @Mock private FcmService fcmService;

    @InjectMocks
    private NotificationServiceImpl notificationService;

    private UUID userId;
    private SendNotificationRequest baseRequest;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        baseRequest = SendNotificationRequest.builder()
                .userId(userId)
                .type(NotificationType.ASSIGNMENT_CREATED)
                .priority(NotificationPriority.NORMAL)
                .title("Test Title")
                .body("Test body")
                .saveToHistory(true)
                .build();
    }

    // ── sendToUser ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("sendToUser")
    class SendToUser {

        @Test
        @DisplayName("saves notification and sends push when device token present")
        void savesAndSendsPush() {
            // Preferences allow this type
            when(preferencesService.isNotificationEnabled(userId, baseRequest.getType())).thenReturn(true);
            when(preferencesService.isInQuietHours(userId)).thenReturn(false);
            // No recent duplicate
            when(notificationRepository.findRecentByUserAndType(eq(userId), eq(baseRequest.getType()), any()))
                    .thenReturn(Collections.emptyList());

            // Notification saved
            Notification saved = Notification.builder().id(UUID.randomUUID()).userId(userId).build();
            when(notificationRepository.save(any(Notification.class))).thenReturn(saved);

            // One device token available
            UserDeviceToken token = UserDeviceToken.builder()
                    .fcmToken("fcm-token-abc")
                    .userId(userId)
                    .build();
            when(deviceTokenRepository.findByUserIdAndIsActiveTrue(userId)).thenReturn(List.of(token));
            when(fcmService.isAvailable()).thenReturn(true);
            when(fcmService.sendToDevice(eq("fcm-token-abc"), any(), any(), any(), any())).thenReturn(true);

            boolean result = notificationService.sendToUser(userId, baseRequest);

            assertThat(result).isTrue();
            verify(notificationRepository).save(any(Notification.class));
            verify(fcmService).sendToDevice(eq("fcm-token-abc"), any(), any(), any(), any());
        }

        @Test
        @DisplayName("skips when notification type disabled in preferences")
        void skipsWhenTypeDisabled() {
            when(preferencesService.isNotificationEnabled(userId, baseRequest.getType())).thenReturn(false);

            boolean result = notificationService.sendToUser(userId, baseRequest);

            assertThat(result).isFalse();
            verifyNoInteractions(notificationRepository);
            verifyNoInteractions(deviceTokenRepository);
        }

        @Test
        @DisplayName("skips non-critical notification during quiet hours")
        void skipsNonCriticalDuringQuietHours() {
            when(preferencesService.isNotificationEnabled(userId, baseRequest.getType())).thenReturn(true);
            when(preferencesService.isInQuietHours(userId)).thenReturn(true);
            // NORMAL priority — should be skipped

            boolean result = notificationService.sendToUser(userId, baseRequest);

            assertThat(result).isFalse();
            verifyNoInteractions(deviceTokenRepository);
        }

        @Test
        @DisplayName("sends CRITICAL notification even during quiet hours")
        void sendsCriticalDuringQuietHours() {
            SendNotificationRequest criticalRequest = baseRequest.toBuilder()
                    .priority(NotificationPriority.CRITICAL)
                    .build();
            when(preferencesService.isNotificationEnabled(userId, criticalRequest.getType())).thenReturn(true);
            when(preferencesService.isInQuietHours(userId)).thenReturn(true);
            when(notificationRepository.findRecentByUserAndType(eq(userId), any(), any()))
                    .thenReturn(Collections.emptyList());
            Notification saved = Notification.builder().id(UUID.randomUUID()).userId(userId).build();
            when(notificationRepository.save(any())).thenReturn(saved);
            when(deviceTokenRepository.findByUserIdAndIsActiveTrue(userId)).thenReturn(Collections.emptyList());

            boolean result = notificationService.sendToUser(userId, criticalRequest);

            // Returns true because notification was saved (even without push)
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("deduplication: skips when same type sent within 5 minutes")
        void deduplicatesRecentNotification() {
            when(preferencesService.isNotificationEnabled(userId, baseRequest.getType())).thenReturn(true);
            when(preferencesService.isInQuietHours(userId)).thenReturn(false);

            // Recent duplicate exists
            Notification recent = Notification.builder().id(UUID.randomUUID()).userId(userId).build();
            when(notificationRepository.findRecentByUserAndType(eq(userId), eq(baseRequest.getType()), any()))
                    .thenReturn(List.of(recent));

            boolean result = notificationService.sendToUser(userId, baseRequest);

            assertThat(result).isFalse();
            verify(notificationRepository, never()).save(any());
        }

        @Test
        @DisplayName("bypassPreferences skips preference and dedup checks")
        void bypassPreferencesSkipsAllChecks() {
            SendNotificationRequest bypassRequest = baseRequest.toBuilder()
                    .bypassPreferences(true)
                    .build();

            Notification saved = Notification.builder().id(UUID.randomUUID()).userId(userId).build();
            when(notificationRepository.save(any())).thenReturn(saved);
            when(deviceTokenRepository.findByUserIdAndIsActiveTrue(userId)).thenReturn(Collections.emptyList());

            boolean result = notificationService.sendToUser(userId, bypassRequest);

            assertThat(result).isTrue();
            verifyNoInteractions(preferencesService);
            verify(notificationRepository).save(any());
        }

        @Test
        @DisplayName("returns true even when no device tokens (notification still saved)")
        void returnsTrueWhenNoDeviceTokens() {
            when(preferencesService.isNotificationEnabled(userId, baseRequest.getType())).thenReturn(true);
            when(preferencesService.isInQuietHours(userId)).thenReturn(false);
            when(notificationRepository.findRecentByUserAndType(any(), any(), any()))
                    .thenReturn(Collections.emptyList());

            Notification saved = Notification.builder().id(UUID.randomUUID()).userId(userId).build();
            when(notificationRepository.save(any())).thenReturn(saved);
            when(deviceTokenRepository.findByUserIdAndIsActiveTrue(userId)).thenReturn(Collections.emptyList());

            boolean result = notificationService.sendToUser(userId, baseRequest);

            assertThat(result).isTrue(); // saved to history
            verify(fcmService, never()).sendToDevice(any(), any(), any(), any(), any());
        }
    }

    // ── sendToUsers ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("sendToUsers")
    class SendToUsers {

        @Test
        @DisplayName("returns 0 for null userIds")
        void returnsZeroForNullUserIds() {
            SendNotificationRequest req = baseRequest.toBuilder().userId(null).userIds(null).build();
            int result = notificationService.sendToUsers(null, req);
            assertThat(result).isZero();
        }

        @Test
        @DisplayName("returns 0 for empty userIds")
        void returnsZeroForEmptyUserIds() {
            int result = notificationService.sendToUsers(Collections.emptyList(), baseRequest);
            assertThat(result).isZero();
        }

        @Test
        @DisplayName("batch-fetches preferences in single call (not per-user)")
        void batchFetchesPreferences() {
            UUID u1 = UUID.randomUUID();
            UUID u2 = UUID.randomUUID();
            List<UUID> userIds = List.of(u1, u2);

            SendNotificationRequest req = baseRequest.toBuilder()
                    .userId(null)
                    .userIds(userIds)
                    .saveToHistory(true)
                    .build();

            // Preferences allow both users
            UserNotificationPreferences prefs1 = mock(UserNotificationPreferences.class);
            UserNotificationPreferences prefs2 = mock(UserNotificationPreferences.class);
            when(prefs1.isNotificationTypeEnabled(any())).thenReturn(true);
            when(prefs2.isNotificationTypeEnabled(any())).thenReturn(true);
            when(prefs1.isQuietTime(any())).thenReturn(false);
            when(prefs2.isQuietTime(any())).thenReturn(false);

            when(preferencesService.getPreferencesByUserIds(userIds))
                    .thenReturn(Map.of(u1, prefs1, u2, prefs2));
            when(notificationRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));
            when(deviceTokenRepository.findActiveTokensByUserIds(anyList())).thenReturn(Collections.emptyList());

            int result = notificationService.sendToUsers(userIds, req);

            assertThat(result).isEqualTo(2);
            // preferences must be fetched in ONE batch call, never once per user
            verify(preferencesService, times(1)).getPreferencesByUserIds(userIds);
            verify(preferencesService, never()).isNotificationEnabled(any(), any());
        }

        @Test
        @DisplayName("filters out users in quiet hours for non-critical notifications")
        void filtersQuietHoursUsers() {
            UUID u1 = UUID.randomUUID();
            UUID u2 = UUID.randomUUID();
            List<UUID> userIds = List.of(u1, u2);

            SendNotificationRequest req = baseRequest.toBuilder()
                    .userId(null)
                    .userIds(userIds)
                    .priority(NotificationPriority.NORMAL)
                    .saveToHistory(true)
                    .build();

            UserNotificationPreferences prefs1 = mock(UserNotificationPreferences.class);
            UserNotificationPreferences prefs2 = mock(UserNotificationPreferences.class);
            when(prefs1.isNotificationTypeEnabled(any())).thenReturn(true);
            when(prefs2.isNotificationTypeEnabled(any())).thenReturn(true);
            when(prefs1.isQuietTime(any())).thenReturn(false); // u1 NOT in quiet hours
            when(prefs2.isQuietTime(any())).thenReturn(true);  // u2 IS in quiet hours

            when(preferencesService.getPreferencesByUserIds(userIds))
                    .thenReturn(Map.of(u1, prefs1, u2, prefs2));

            ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.forClass(List.class);
            when(notificationRepository.saveAll(captor.capture())).thenAnswer(i -> i.getArgument(0));
            when(deviceTokenRepository.findActiveTokensByUserIds(anyList())).thenReturn(Collections.emptyList());

            int result = notificationService.sendToUsers(userIds, req);

            // Only u1 should receive notification
            assertThat(result).isEqualTo(1);
            assertThat(captor.getValue()).hasSize(1);
            assertThat(captor.getValue().get(0).getUserId()).isEqualTo(u1);
        }
    }

    // ── send (dispatch) ────────────────────────────────────────────────────

    @Nested
    @DisplayName("send dispatch")
    class Send {

        @Test
        @DisplayName("routes to sendToUser when userId set")
        void routesToSingleUser() {
            when(preferencesService.isNotificationEnabled(any(), any())).thenReturn(false);

            int result = notificationService.send(baseRequest);

            assertThat(result).isZero(); // sendToUser returns false → 0
        }

        @Test
        @DisplayName("routes to sendToUsers when userIds set")
        void routesToMultipleUsers() {
            SendNotificationRequest req = baseRequest.toBuilder()
                    .userId(null)
                    .userIds(List.of(UUID.randomUUID(), UUID.randomUUID()))
                    .build();

            when(preferencesService.getPreferencesByUserIds(anyList())).thenReturn(Map.of());
            when(notificationRepository.saveAll(anyList())).thenReturn(Collections.emptyList());
            when(deviceTokenRepository.findActiveTokensByUserIds(anyList())).thenReturn(Collections.emptyList());

            notificationService.send(req);

            verify(preferencesService).getPreferencesByUserIds(anyList());
        }

        @Test
        @DisplayName("returns 0 when no target specified")
        void returnsZeroWhenNoTarget() {
            SendNotificationRequest req = baseRequest.toBuilder()
                    .userId(null)
                    .userIds(null)
                    .topic(null)
                    .build();

            int result = notificationService.send(req);

            assertThat(result).isZero();
            verifyNoInteractions(notificationRepository);
        }
    }
}

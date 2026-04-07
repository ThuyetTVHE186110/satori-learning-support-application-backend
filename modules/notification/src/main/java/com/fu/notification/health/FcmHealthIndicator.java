package com.fu.notification.health;

import com.fu.notification.service.FcmService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health indicator for Firebase Cloud Messaging (FCM).
 *
 * Reports:
 *   UP      — FCM is enabled and FirebaseMessaging bean is initialized
 *   DOWN    — FCM is enabled but bean failed to initialize (check credentials)
 *   UNKNOWN — FCM is intentionally disabled (FCM_ENABLED=false); not an error
 */
@Component("fcm")
@RequiredArgsConstructor
public class FcmHealthIndicator implements HealthIndicator {

    private final FcmService fcmService;

    @Override
    public Health health() {
        if (fcmService.isAvailable()) {
            return Health.up()
                    .withDetail("status", "Firebase Messaging initialized")
                    .build();
        }

        // isAvailable() == false can mean either disabled or failed init.
        // FcmServiceImpl.isAvailable() returns firebaseMessaging.isPresent(),
        // which is false when FCM_ENABLED=false (Optional.empty() injected by config).
        return Health.unknown()
                .withDetail("status", "FCM is disabled or credentials not configured")
                .withDetail("hint", "Set FCM_ENABLED=true and provide FIREBASE_CREDENTIALS_FILE to enable push notifications")
                .build();
    }
}

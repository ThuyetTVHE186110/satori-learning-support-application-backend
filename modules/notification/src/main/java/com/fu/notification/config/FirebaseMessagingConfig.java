package com.fu.notification.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Configuration for Firebase Cloud Messaging.
 * Initializes Firebase Admin SDK and provides FirebaseMessaging bean.
 */
@Slf4j
@Configuration
public class FirebaseMessagingConfig {

    @Value("${app.notification.firebase.enabled:false}")
    private boolean firebaseEnabled;

    @Value("${app.notification.firebase.credentials-file:}")
    private String credentialsFile;

    @Value("${app.notification.firebase.project-id:}")
    private String projectId;

    @Bean
    public Optional<FirebaseMessaging> firebaseMessaging() {
        if (!firebaseEnabled) {
            log.info("Firebase Cloud Messaging is disabled. Push notifications will not be available.");
            return Optional.empty();
        }

        try {
            FirebaseApp firebaseApp;
            
            // Check if FirebaseApp is already initialized (e.g., by user module)
            if (!FirebaseApp.getApps().isEmpty()) {
                firebaseApp = FirebaseApp.getInstance();
                log.info("Using existing Firebase App instance for FCM");
            } else {
                // Initialize Firebase App
                FirebaseOptions options = buildFirebaseOptions();
                firebaseApp = FirebaseApp.initializeApp(options);
                log.info("Firebase Admin SDK initialized for FCM with project: {}", projectId);
            }

            return Optional.of(FirebaseMessaging.getInstance(firebaseApp));
        } catch (IOException e) {
            log.error("Failed to initialize Firebase Messaging: {}", e.getMessage());
            log.warn("Push notifications will not be available");
            return Optional.empty();
        }
    }

    private FirebaseOptions buildFirebaseOptions() throws IOException {
        if (credentialsFile != null && !credentialsFile.isEmpty()) {
            Resource resource;
            if (credentialsFile.startsWith("classpath:")) {
                resource = new ClassPathResource(credentialsFile.substring(10));
            } else {
                resource = new FileSystemResource(credentialsFile);
            }

            try (InputStream serviceAccount = resource.getInputStream()) {
                return FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                        .setProjectId(projectId)
                        .build();
            }
        } else {
            // Use default credentials (for GCP environments)
            return FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.getApplicationDefault())
                    .setProjectId(projectId)
                    .build();
        }
    }
}

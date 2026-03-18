package com.fu.user.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
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
 * Configuration for Firebase Admin SDK.
 * Used for Google OAuth token verification.
 */
@Slf4j
@Configuration
public class FirebaseConfig {

    @Value("${app.google.firebase.enabled:false}")
    private boolean firebaseEnabled;

    @Value("${app.google.firebase.credentials-file:}")
    private String credentialsFile;

    @Value("${app.google.firebase.project-id:}")
    private String projectId;

    @Bean
    public Optional<FirebaseAuth> firebaseAuth() {
        if (!firebaseEnabled) {
            log.info("Firebase is disabled. Google OAuth login will not be available.");
            return Optional.empty();
        }

        try {
            if (FirebaseApp.getApps().isEmpty()) {
                FirebaseOptions options;
                
                if (credentialsFile != null && !credentialsFile.isEmpty()) {
                    // Load from file
                    Resource resource;
                    if (credentialsFile.startsWith("classpath:")) {
                        resource = new ClassPathResource(credentialsFile.substring(10));
                    } else {
                        resource = new FileSystemResource(credentialsFile);
                    }
                    
                    try (InputStream serviceAccount = resource.getInputStream()) {
                        options = FirebaseOptions.builder()
                                .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                                .setProjectId(projectId)
                                .build();
                    }
                } else {
                    // Use default credentials (for GCP environments)
                    options = FirebaseOptions.builder()
                            .setCredentials(GoogleCredentials.getApplicationDefault())
                            .setProjectId(projectId)
                            .build();
                }

                FirebaseApp.initializeApp(options);
                log.info("Firebase Admin SDK initialized successfully with project: {}", projectId);
            }
            
            return Optional.of(FirebaseAuth.getInstance());
        } catch (IOException e) {
            log.error("Failed to initialize Firebase Admin SDK: {}", e.getMessage());
            log.warn("Google OAuth login will not be available");
            return Optional.empty();
        }
    }
}

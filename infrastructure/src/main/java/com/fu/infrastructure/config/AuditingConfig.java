package com.fu.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

/**
 * Configuration for Spring Data JPA Auditing.
 * Provides the current user's UUID for @CreatedBy and @LastModifiedBy annotations.
 * 
 * This integrates with Spring Security to automatically populate:
 * - createdBy: When a new entity is persisted
 * - updatedBy: When an existing entity is updated
 */
@Configuration
public class AuditingConfig {

    /**
     * Provides the current auditor (user) for JPA Auditing.
     * Extracts the user ID from Spring Security context.
     * 
     * @return AuditorAware implementation that returns the current user's UUID
     */
    @Bean
    public AuditorAware<UUID> auditorProvider() {
        return new SecurityAuditorAware();
    }

    /**
     * AuditorAware implementation using Spring Security.
     * Returns the UUID of the currently authenticated user.
     */
    static class SecurityAuditorAware implements AuditorAware<UUID> {

        @Override
        public Optional<UUID> getCurrentAuditor() {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

            // Return empty if no authentication or not authenticated
            if (authentication == null || !authentication.isAuthenticated()) {
                return Optional.empty();
            }

            // Handle anonymous users
            if (authentication.getPrincipal().equals("anonymousUser")) {
                return Optional.empty();
            }

            // Extract user ID from authentication principal
            // This assumes your UserDetails implementation has a method to get UUID
            // Adjust this based on your actual UserDetails implementation
            Object principal = authentication.getPrincipal();
            
            if (principal instanceof UserPrincipal userPrincipal) {
                return Optional.ofNullable(userPrincipal.getId());
            }
            
            // Fallback: try to parse principal as UUID string (for JWT tokens)
            if (principal instanceof String principalStr) {
                try {
                    return Optional.of(UUID.fromString(principalStr));
                } catch (IllegalArgumentException e) {
                    return Optional.empty();
                }
            }

            return Optional.empty();
        }
    }

    /**
     * Interface for user principal that provides user ID.
     * Your UserDetails implementation should implement this interface.
     */
    public interface UserPrincipal {
        UUID getId();
    }
}

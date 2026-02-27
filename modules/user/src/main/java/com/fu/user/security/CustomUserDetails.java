package com.fu.user.security;

import com.fu.infrastructure.config.AuditingConfig;
import com.fu.user.entity.User;
import com.fu.user.entity.UserStatus;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Collections;
import java.util.UUID;

/**
 * Custom UserDetails implementation for Spring Security.
 * Wraps User entity with security-related information.
 *
 * Implements AuditingConfig.UserPrincipal so that Spring Data JPA Auditing
 * can extract the user UUID for @CreatedBy and @LastModifiedBy fields.
 */
@Getter
public class CustomUserDetails implements UserDetails, AuditingConfig.UserPrincipal {

    private final UUID id;
    private final String email;
    private final String password;
    private final String fullName;
    private final String role;
    private final UserStatus status;
    private final Collection<? extends GrantedAuthority> authorities;
    private final boolean accountLocked;
    private final UUID sessionId;

    public CustomUserDetails(User user) {
        this(user, null);
    }

    public CustomUserDetails(User user, UUID sessionId) {
        this.id = user.getId();
        this.email = user.getEmail();
        this.password = user.getPasswordHash();
        this.fullName = user.getFullName();
        this.role = user.getRole().name();
        this.status = user.getStatus();
        this.sessionId = sessionId;
        
        // Role-based authority: ROLE_LEARNER, ROLE_TEACHER, ROLE_MANAGER, ROLE_ADMIN
        this.authorities = Collections.singletonList(
                new SimpleGrantedAuthority("ROLE_" + user.getRole().name())
        );
        
        // Account is locked if lockedUntil is in the future
        this.accountLocked = user.getLockedUntil() != null && 
                             user.getLockedUntil().isAfter(java.time.LocalDateTime.now());
    }

    @Override
    public String getUsername() {
        return id.toString();
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public boolean isAccountNonExpired() {
        // Account doesn't expire based on time
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        // Account is locked if status is SUSPENDED or lockedUntil is in future
        return status != UserStatus.SUSPENDED && !accountLocked;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        // Credentials don't expire
        return true;
    }

    @Override
    public boolean isEnabled() {
        // Account is enabled only if status is ACTIVE
        return status == UserStatus.ACTIVE;
    }
}

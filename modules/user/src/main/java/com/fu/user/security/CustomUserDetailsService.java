package com.fu.user.security;

import com.fu.user.entity.User;
import com.fu.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Custom UserDetailsService implementation for Spring Security.
 * Loads user from database by email.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        log.debug("Loading user by email: {}", email);
        
        User user = userRepository.findActiveByEmail(email)
                .orElseThrow(() -> {
                    log.warn("User not found with email: {}", email);
                    return new UsernameNotFoundException("Không tìm thấy người dùng với email: " + email);
                });

        return new CustomUserDetails(user);
    }

    /**
     * Load user by ID (for JWT validation).
     */
    @Transactional(readOnly = true)
    public UserDetails loadUserById(java.util.UUID userId) throws UsernameNotFoundException {
        log.debug("Loading user by ID: {}", userId);
        
        User user = userRepository.findById(userId)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> {
                    log.warn("User not found with ID: {}", userId);
                    return new UsernameNotFoundException("Không tìm thấy người dùng với ID: " + userId);
                });

        return new CustomUserDetails(user);
    }

    /**
     * Load user by ID with session ID (for JWT validation with session context).
     */
    @Transactional(readOnly = true)
    public UserDetails loadUserByIdWithSession(java.util.UUID userId, java.util.UUID sessionId) throws UsernameNotFoundException {
        log.debug("Loading user by ID: {} with session: {}", userId, sessionId);
        
        User user = userRepository.findById(userId)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> {
                    log.warn("User not found with ID: {}", userId);
                    return new UsernameNotFoundException("Không tìm thấy người dùng với ID: " + userId);
                });

        return new CustomUserDetails(user, sessionId);
    }
}

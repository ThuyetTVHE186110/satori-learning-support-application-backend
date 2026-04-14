package com.fu.user.service.impl;

import com.fu.user.config.SecurityProperties;
import com.fu.user.entity.User;
import com.fu.user.repository.UserRepository;
import com.fu.user.service.LoginAttemptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAttemptServiceImpl implements LoginAttemptService {

    private final UserRepository userRepository;
    private final SecurityProperties securityProperties;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleFailedLogin(User user) {
        user.setFailedLoginAttempts(user.getFailedLoginAttempts() + 1);

        if (user.getFailedLoginAttempts() >= securityProperties.getMaxFailedAttempts()) {
            user.setLockedUntil(LocalDateTime.now().plusMinutes(securityProperties.getLockDurationMinutes()));
            log.warn("Account locked due to {} failed attempts: {}", securityProperties.getMaxFailedAttempts(), user.getEmail());
        }

        userRepository.saveAndFlush(user);
    }
}

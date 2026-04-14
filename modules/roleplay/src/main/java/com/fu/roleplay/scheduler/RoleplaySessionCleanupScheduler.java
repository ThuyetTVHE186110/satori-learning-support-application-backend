package com.fu.roleplay.scheduler;

import com.fu.roleplay.config.RoleplayProperties;
import com.fu.roleplay.repository.ConversationSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Hourly job that auto-abandons stale ACTIVE roleplay sessions.
 *
 * A session is considered stale when its last message (or created_at if no
 * messages exist) is older than {@code roleplay.session-inactivity-hours}.
 * This prevents "zombie" sessions from permanently blocking the user's
 * active-session quota.
 *
 * Also marks pending/in-progress missions as FAILED for abandoned sessions
 * via a single bulk UPDATE (missions table has no FK cascade on status).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RoleplaySessionCleanupScheduler {

    private final ConversationSessionRepository sessionRepository;
    private final RoleplayProperties properties;

    @Scheduled(cron = "0 0 * * * *") // every hour
    @Transactional
    public void cleanupStaleSessions() {
        LocalDateTime cutoff = LocalDateTime.now()
                .minusHours(properties.getSessionInactivityHours());

        int count = sessionRepository.markStaleSessionsAbandoned(cutoff);

        if (count > 0) {
            log.info("Roleplay cleanup: auto-abandoned {} stale session(s) (inactive since before {})",
                    count, cutoff);
        } else {
            log.debug("Roleplay cleanup: no stale sessions found (cutoff={})", cutoff);
        }
    }
}

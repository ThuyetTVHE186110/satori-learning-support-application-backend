package com.fu.conversation.scheduler;

import com.fu.conversation.config.ChatProperties;
import com.fu.conversation.repository.ChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Nightly job that marks stale ACTIVE chat sessions as ABANDONED.
 *
 * Sessions that stay ACTIVE indefinitely accumulate in the database — learners
 * close the browser without calling DELETE /sessions/{id}. This scheduler
 * reclaims those sessions so the listing API stays clean and storage stays bounded.
 *
 * Runs at 02:00 AM server time every day.
 * Retention period is configurable via {@code conversation.chat.session-cleanup-retention-days}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatSessionCleanupScheduler {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatProperties chatProperties;

    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void cleanupStaleSessions() {
        LocalDateTime cutoff = LocalDateTime.now()
                .minusDays(chatProperties.getSessionCleanupRetentionDays());

        int count = chatSessionRepository.markAbandonedBefore(cutoff);

        if (count > 0) {
            log.info("Chat session cleanup: marked {} ACTIVE session(s) as ABANDONED (created before {})",
                    count, cutoff.toLocalDate());
        } else {
            log.debug("Chat session cleanup: no stale sessions found (cutoff={})", cutoff.toLocalDate());
        }
    }
}

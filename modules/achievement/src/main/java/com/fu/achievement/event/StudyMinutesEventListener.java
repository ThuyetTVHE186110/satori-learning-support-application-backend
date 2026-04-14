package com.fu.achievement.event;

import com.fu.achievement.entity.UserStats;
import com.fu.achievement.repository.UserStatsRepository;
import com.fu.achievement.service.StreakService;
import com.fu.infrastructure.event.StudyMinutesRecordedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Listens for completed practice sessions and increments the daily study minutes counter.
 * Resets the counter automatically when the date changes (midnight rollover).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StudyMinutesEventListener {

    private final StreakService streakService;
    private final UserStatsRepository userStatsRepository;

    @EventListener
    @Transactional
    public void onStudyMinutesRecorded(StudyMinutesRecordedEvent event) {
        try {
            UserStats stats = streakService.getUserStats(event.getUserId());
            LocalDate today = LocalDate.now();

            // Reset counter if the stored date is from a previous day
            if (!today.equals(stats.getStudyDate())) {
                stats.setStudiedMinutesToday(0);
                stats.setStudyDate(today);
            }

            stats.setStudiedMinutesToday(stats.getStudiedMinutesToday() + event.getDurationMinutes());
            userStatsRepository.save(stats);

            log.debug("Updated studiedMinutesToday to {} min for user {}",
                    stats.getStudiedMinutesToday(), event.getUserId());
        } catch (Exception e) {
            log.error("Failed to update studiedMinutesToday for user {}: {}",
                    event.getUserId(), e.getMessage(), e);
        }
    }
}

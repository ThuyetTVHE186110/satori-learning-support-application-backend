package com.fu.clazz.scheduler;

import com.fu.clazz.entity.ClassStatus;
import com.fu.clazz.entity.Clazz;
import com.fu.clazz.repository.ClassRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Manages dynamic scheduling for activating classes at their start dates.
 * Replaces the old cron-based ClassSchedulerService.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClassScheduleManager {

    private final TaskScheduler taskScheduler;
    private final ClassRepository classRepository;
    private final TransactionTemplate transactionTemplate;

    // In-memory storage for scheduled tasks to allow cancellation/rescheduling
    private final Map<UUID, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    /**
     * Reschedules all NOT_STARTED classes on application startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void rescheduleAllOnStartup() {
        log.info("App started — Re-scheduling all NOT_STARTED classes for activation...");
        // Use the repository method to find classes that are NOT_STARTED and not deleted
        classRepository.findByStatusAndDeletedAtIsNull(ClassStatus.NOT_STARTED)
                .forEach(this::scheduleActivation);
    }

    /**
     * Schedule or re-schedule a class to activate at its startDate.
     */
    public void scheduleActivation(Clazz clazz) {
        if (clazz.getStartDate() == null || clazz.getStatus() != ClassStatus.NOT_STARTED) {
            cancelScheduledActivation(clazz.getId());
            return;
        }

        // Cancel existing task if any
        cancelScheduledActivation(clazz.getId());

        // If start date is today or already passed, activate immediately
        if (!clazz.getStartDate().isAfter(java.time.LocalDate.now())) {
            log.info("Class {} startDate ({}) has reached or passed, activating immediately.", 
                    clazz.getId(), clazz.getStartDate());
            activateClass(clazz.getId());
            return;
        }

        // Schedule at start of the startDate day
        Instant activationTime = clazz.getStartDate().atStartOfDay(ZoneId.systemDefault()).toInstant();
        log.info("Scheduling activation for class {} at {}", clazz.getId(), activationTime);

        ScheduledFuture<?> future = taskScheduler.schedule(() -> activateClass(clazz.getId()), activationTime);
        if (future != null) {
            scheduledTasks.put(clazz.getId(), future);
        }
    }

    /**
     * Cancel a scheduled activation.
     */
    public void cancelScheduledActivation(UUID classId) {
        if (classId == null) return;
        ScheduledFuture<?> future = scheduledTasks.remove(classId);
        if (future != null && !future.isDone()) {
            future.cancel(false);
            log.info("Cancelled scheduled activation for class {}", classId);
        }
    }

    /**
     * Logic to update class status to ACTIVE.
     */
    private void activateClass(UUID classId) {
        transactionTemplate.executeWithoutResult(status -> {
            classRepository.findByIdAndNotDeleted(classId).ifPresent(c -> {
                if (c.getStatus() == ClassStatus.NOT_STARTED) {
                    c.setStatus(ClassStatus.ACTIVE);
                    c.setUpdatedAt(LocalDateTime.now());
                    classRepository.save(c);
                    log.info("Class {} has been ACTIVATED automatically by scheduler.", classId);
                }
            });
            scheduledTasks.remove(classId);
        });
    }
}

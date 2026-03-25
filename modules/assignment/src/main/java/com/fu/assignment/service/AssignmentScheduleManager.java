package com.fu.assignment.service;

import com.fu.assignment.entity.Assignment;
import com.fu.assignment.entity.AssignmentStatus;
import com.fu.assignment.repository.AssignmentRepository;
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
 * Manages dynamic scheduling for closing assignments at their due dates.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssignmentScheduleManager {

    private final TaskScheduler taskScheduler;
    private final AssignmentRepository assignmentRepository;
    private final TransactionTemplate transactionTemplate;
    
    // In-memory storage for scheduled tasks to allow cancellation
    private final Map<UUID, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    /**
     * Reschedules all PUBLISHED assignments on application startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void rescheduleAllOnStartup() {
        log.info("App started — Re-scheduling all PUBLISHED assignments...");
        assignmentRepository.findAllByStatusAndDeletedAtIsNull(AssignmentStatus.PUBLISHED).forEach(this::scheduleClosing);
    }

    /**
     * Schedule or re-schedule an assignment to close at its dueDate.
     */
    public void scheduleClosing(Assignment assignment) {
        if (assignment.getDueDate() == null || assignment.getStatus() != AssignmentStatus.PUBLISHED) {
            cancelScheduledClosing(assignment.getId());
            return;
        }

        // Cancel old task if exists
        cancelScheduledClosing(assignment.getId());

        if (assignment.getDueDate().isBefore(LocalDateTime.now())) {
            log.info("Assignment {} dueDate passed, closing immediately.", assignment.getId());
            closeAssignment(assignment.getId());
            return;
        }

        Instant instant = assignment.getDueDate().atZone(ZoneId.systemDefault()).toInstant();
        log.info("Scheduling closure for assignment {} at {}", assignment.getId(), assignment.getDueDate());

        ScheduledFuture<?> future = taskScheduler.schedule(() -> closeAssignment(assignment.getId()), instant);
        scheduledTasks.put(assignment.getId(), future);
    }

    /**
     * Cancel a scheduled closure.
     */
    public void cancelScheduledClosing(UUID assignmentId) {
        ScheduledFuture<?> future = scheduledTasks.remove(assignmentId);
        if (future != null && !future.isDone()) {
            future.cancel(false);
            log.info("Cancelled scheduled closure for assignment {}", assignmentId);
        }
    }

    /**
     * Logic to update assignment status to CLOSED.
     */
    private void closeAssignment(UUID assignmentId) {
        transactionTemplate.executeWithoutResult(status -> {
            assignmentRepository.findById(assignmentId).ifPresent(a -> {
                if (a.getStatus() == AssignmentStatus.PUBLISHED) {
                    a.setStatus(AssignmentStatus.CLOSED);
                    a.setUpdatedAt(LocalDateTime.now());
                    assignmentRepository.save(a);
                    log.info("Assignment {} has been CLOSED automatically by scheduler.", assignmentId);
                }
            });
            scheduledTasks.remove(assignmentId);
        });
    }
}

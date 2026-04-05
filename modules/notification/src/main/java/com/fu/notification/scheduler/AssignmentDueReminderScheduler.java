package com.fu.notification.scheduler;

import com.fu.notification.dto.SendNotificationRequest;
import com.fu.notification.entity.NotificationPriority;
import com.fu.notification.entity.NotificationType;
import com.fu.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Scheduled job for sending assignment due reminders.
 * Checks for assignments due within 24 hours and notifies students.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssignmentDueReminderScheduler {

    private final NotificationService notificationService;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Send assignment due reminders.
     * Runs every 6 hours.
     */
    @Scheduled(cron = "0 0 */6 * * ?")
    public void sendAssignmentDueReminders() {
        log.debug("Checking for assignments due within 24 hours...");

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime dueSoon = now.plusHours(24);

        // Query for assignments due within 24 hours that students haven't submitted
        String sql = """
            SELECT DISTINCT 
                a.id as assignment_id,
                a.title as assignment_title,
                a.due_date,
                cm.user_id as student_id,
                c.name as class_name
            FROM assignment.assignments a
            JOIN assignment.assignment_targets at ON a.id = at.assignment_id
            JOIN user_management.class_members cm ON at.class_id = cm.class_id
            JOIN user_management.classes c ON at.class_id = c.id
            WHERE a.status = 'PUBLISHED'
              AND a.due_date > ?
              AND a.due_date <= ?
              AND a.deleted_at IS NULL
              AND NOT EXISTS (
                  SELECT 1 FROM assignment.assignment_submissions s 
                  WHERE s.assignment_id = a.id 
                    AND s.student_id = cm.user_id
                    AND s.status IN ('SUBMITTED', 'GRADED')
              )
            """;

        List<Map<String, Object>> pendingAssignments = jdbcTemplate.queryForList(sql, now, dueSoon);

        if (pendingAssignments.isEmpty()) {
            log.debug("No assignments due soon requiring reminders");
            return;
        }

        log.info("Found {} pending assignment submissions due within 24 hours", pendingAssignments.size());

        for (Map<String, Object> row : pendingAssignments) {
            UUID assignmentId = (UUID) row.get("assignment_id");
            UUID studentId = (UUID) row.get("student_id");
            String assignmentTitle = (String) row.get("assignment_title");
            String className = (String) row.get("class_name");

            Map<String, Object> data = new HashMap<>();
            data.put("assignmentId", assignmentId.toString());

            SendNotificationRequest request = SendNotificationRequest.builder()
                    .userId(studentId)
                    .type(NotificationType.ASSIGNMENT_DUE_SOON)
                    .priority(NotificationPriority.HIGH)
                    .title("⏰ Bài tập sắp hết hạn!")
                    .body("Bài \"" + assignmentTitle + "\" (lớp " + className + ") sẽ hết hạn trong 24 giờ.")
                    .data(data)
                    .actionUrl("/assignments/" + assignmentId)
                    .build();

            notificationService.send(request);
        }

        log.info("Sent {} assignment due reminders", pendingAssignments.size());
    }
}

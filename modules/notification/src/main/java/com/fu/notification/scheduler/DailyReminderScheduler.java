package com.fu.notification.scheduler;

import com.fu.notification.dto.SendNotificationRequest;
import com.fu.notification.entity.NotificationPriority;
import com.fu.notification.entity.NotificationType;
import com.fu.notification.repository.UserNotificationPreferencesRepository;
import com.fu.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Scheduled job for sending daily learning reminders.
 * Runs every hour to check for users with reminders at that time.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyReminderScheduler {

    private final UserNotificationPreferencesRepository preferencesRepository;
    private final NotificationService notificationService;

    /**
     * Send daily learning reminders.
     * Runs at the start of every hour.
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void sendDailyReminders() {
        LocalTime currentHour = LocalTime.now().withMinute(0).withSecond(0).withNano(0);
        
        List<UUID> userIds = preferencesRepository.findUsersWithDailyReminderAt(currentHour);
        
        if (userIds.isEmpty()) {
            log.debug("No users with daily reminder at {}", currentHour);
            return;
        }

        log.info("Sending daily reminders to {} users at {}", userIds.size(), currentHour);

        Map<String, Object> data = new HashMap<>();
        data.put("reminderType", "DAILY");

        SendNotificationRequest request = SendNotificationRequest.builder()
                .userIds(userIds)
                .type(NotificationType.DAILY_REMINDER)
                .priority(NotificationPriority.NORMAL)
                .title("📚 Đến giờ học rồi!")
                .body("Hãy dành ít phút học tiếng Hàn mỗi ngày để duy trì streak nhé!")
                .data(data)
                .actionUrl("/learn")
                .build();

        int sent = notificationService.send(request);
        log.info("Sent {} daily reminder notifications", sent);
    }
}

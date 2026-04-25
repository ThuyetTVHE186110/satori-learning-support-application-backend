package com.fu.notification.event;

import com.fu.infrastructure.event.*;
import com.fu.notification.dto.SendNotificationRequest;
import com.fu.notification.repository.UserNotificationPreferencesRepository;
import com.fu.notification.entity.UserNotificationPreferences;
import com.fu.notification.entity.NotificationPriority;
import com.fu.notification.entity.NotificationType;
import com.fu.notification.service.DeviceTokenService;
import com.fu.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Event listeners for application events that trigger notifications.
 * All listeners run asynchronously to avoid blocking the main thread.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApplicationEventNotificationListener {

    private final NotificationService notificationService;
    private final DeviceTokenService deviceTokenService;
    private final UserNotificationPreferencesRepository notificationPreferencesRepository;

    /**
     * Handles AssignmentCreatedEvent - notifies class members about new assignment.
     */
    @Async
    @EventListener
    public void handleAssignmentCreated(AssignmentCreatedEvent event) {
        log.debug("Processing AssignmentCreatedEvent: assignmentId={}", event.getAssignmentId());

        List<UUID> memberIds = event.getMemberIds();
        if (memberIds == null || memberIds.isEmpty()) {
            log.debug("AssignmentCreatedEvent has no member IDs, skipping notification");
            return;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("assignmentId", event.getAssignmentId().toString());
        data.put("classId", event.getClassId().toString());

        SendNotificationRequest request = SendNotificationRequest.builder()
                .userIds(memberIds)
                .type(NotificationType.ASSIGNMENT_CREATED)
                .priority(NotificationPriority.HIGH)
                .title("Bài tập mới: " + event.getAssignmentTitle())
                .body("Lớp " + event.getClassName() + " có bài tập mới. Hãy hoàn thành trước hạn!")
                .data(data)
                .actionUrl("/assignments/" + event.getAssignmentId())
                .saveToHistory(true)
                .build();

        int sent = notificationService.send(request);
        log.info("Assignment created notification sent to {} members: {}", sent, event.getAssignmentTitle());
    }

    /**
     * Handles AssignmentGradedEvent - notifies student about graded assignment.
     */
    @Async
    @EventListener
    public void handleAssignmentGraded(AssignmentGradedEvent event) {
        log.debug("Processing AssignmentGradedEvent: submissionId={}", event.getSubmissionId());

        Map<String, Object> data = new HashMap<>();
        data.put("assignmentId", event.getAssignmentId().toString());
        data.put("submissionId", event.getSubmissionId().toString());
        data.put("score", event.getScore());

        String scoreText = String.format("%.1f điểm", event.getScore());

        SendNotificationRequest request = SendNotificationRequest.builder()
                .userId(event.getStudentId())
                .type(NotificationType.ASSIGNMENT_GRADED)
                .priority(NotificationPriority.NORMAL)
                .title("Bài tập đã được chấm điểm")
                .body("Bài \"" + event.getAssignmentTitle() + "\" của bạn đã được chấm: " + scoreText)
                .data(data)
                .actionUrl("/assignments/" + event.getAssignmentId() + "/result")
                .build();

        int sent = notificationService.send(request);
        if (sent > 0) {
            log.info("Assignment graded notification dispatched to student: {}", event.getStudentId());
        } else {
            log.warn("Assignment graded notification DROPPED for student {} — check prefs/quiet hours/dedup/tokens (DEBUG c.f.n.s.impl.NotificationServiceImpl for reason)", event.getStudentId());
        }
    }

    /**
     * Handles AssignmentSubmittedEvent - notifies teacher about new submission.
     */
    @Async
    @EventListener
    public void handleAssignmentSubmitted(AssignmentSubmittedEvent event) {
        log.debug("Processing AssignmentSubmittedEvent: submissionId={}", event.getSubmissionId());

        Map<String, Object> data = new HashMap<>();
        data.put("assignmentId", event.getAssignmentId().toString());
        data.put("submissionId", event.getSubmissionId().toString());
        data.put("studentId", event.getStudentId().toString());

        SendNotificationRequest request = SendNotificationRequest.builder()
                .userId(event.getTeacherId())
                .type(NotificationType.SUBMISSION_RECEIVED)
                .priority(NotificationPriority.NORMAL)
                .title("📥 Bài nộp mới")
                .body(event.getStudentName() + " đã nộp bài \"" + event.getAssignmentTitle() + "\"")
                .data(data)
                .actionUrl("/teacher/assignments/" + event.getAssignmentId() + "/submissions")
                .build();

        notificationService.send(request);
        log.info("Submission notification sent to teacher: {}", event.getTeacherId());
    }

    /**
     * Handles ClassJoinedEvent - welcomes user to the class.
     */
    @Async
    @EventListener
    public void handleClassJoined(ClassJoinedEvent event) {
        log.debug("Processing ClassJoinedEvent: userId={}, classId={}", 
                event.getUserId(), event.getClassId());

        Map<String, Object> data = new HashMap<>();
        data.put("classId", event.getClassId().toString());

        SendNotificationRequest request = SendNotificationRequest.builder()
                .userId(event.getUserId())
                .type(NotificationType.CLASS_JOINED)
                .priority(NotificationPriority.NORMAL)
                .title("🎉 Chào mừng đến lớp " + event.getClassName())
                .body("Bạn đã tham gia lớp học của " + event.getTeacherName() + ". Chúc bạn học tập hiệu quả!")
                .data(data)
                .actionUrl("/classes/" + event.getClassId())
                .build();

        notificationService.send(request);
        log.info("Class joined notification sent: {} -> {}", event.getUserId(), event.getClassName());
    }

    /**
     * Handles CurriculumImportStatusEvent - notifies manager about import ready for approval or failure.
     */
    @Async
    @EventListener
    public void handleCurriculumImportStatus(CurriculumImportStatusEvent event) {
        log.debug("Processing CurriculumImportStatusEvent: importId={}, status={}", event.getImportId(), event.getStatus());

        Map<String, Object> data = new HashMap<>();
        data.put("importId", event.getImportId().toString());

        SendNotificationRequest request;
        if (event.getStatus() == CurriculumImportStatusEvent.Status.PENDING_APPROVAL) {
            request = SendNotificationRequest.builder()
                    .userId(event.getManagerId())
                    .type(NotificationType.CURRICULUM_IMPORT_PENDING_APPROVAL)
                    .priority(NotificationPriority.HIGH)
                    .title("Tài liệu chờ duyệt: " + event.getImportName())
                    .body("Import tài liệu đã hoàn tất và đang chờ bạn phê duyệt.")
                    .data(data)
                    .actionUrl("/manager/imports/" + event.getImportId())
                    .saveToHistory(true)
                    .bypassPreferences(true)
                    .build();
        } else {
            request = SendNotificationRequest.builder()
                    .userId(event.getManagerId())
                    .type(NotificationType.CURRICULUM_IMPORT_FAILED)
                    .priority(NotificationPriority.HIGH)
                    .title("Import thất bại: " + event.getImportName())
                    .body("Import tài liệu gặp lỗi: " + event.getErrorMessage())
                    .data(data)
                    .actionUrl("/manager/imports/" + event.getImportId())
                    .saveToHistory(true)
                    .bypassPreferences(true)
                    .build();
        }

        notificationService.send(request);
        log.info("Curriculum import {} notification sent to manager: {}", event.getStatus(), event.getManagerId());
    }

    /**
     * Handles PasswordChangedEvent - security notification.
     */
    @Async
    @EventListener
    public void handlePasswordChanged(PasswordChangedEvent event) {
        log.debug("Processing PasswordChangedEvent: userId={}", event.getUserId());

        Map<String, Object> data = new HashMap<>();
        data.put("deviceInfo", event.getDeviceInfo());
        data.put("ipAddress", event.getIpAddress());

        SendNotificationRequest request = SendNotificationRequest.builder()
                .userId(event.getUserId())
                .type(NotificationType.PASSWORD_CHANGED)
                .priority(NotificationPriority.HIGH)
                .title("🔐 Mật khẩu đã được thay đổi")
                .body("Mật khẩu tài khoản " + event.getUserEmail() + " đã được thay đổi. Nếu không phải bạn, hãy liên hệ hỗ trợ ngay.")
                .data(data)
                .actionUrl("/settings/security")
                .bypassPreferences(true) // Security notifications always sent
                .build();

        notificationService.send(request);

        // Deactivate all FCM tokens for security (force re-login on all devices)
        deviceTokenService.deactivateAllTokens(event.getUserId());

        log.info("Password changed notification sent and tokens deactivated: {}", event.getUserId());
    }

    /**
     * Handles DeviceReassignedEvent — notifies the evicted user that their device
     * is now signed into another account (ACCOUNT_SECURITY alert).
     */
    @Async
    @EventListener
    public void handleDeviceReassigned(DeviceReassignedEvent event) {
        log.info("Device reassigned — alerting evicted user {}, device: {}",
                event.getEvictedUserId(), event.getDeviceName());

        SendNotificationRequest request = SendNotificationRequest.builder()
                .userId(event.getEvictedUserId())
                .type(NotificationType.ACCOUNT_SECURITY)
                .priority(NotificationPriority.HIGH)
                .title("Cảnh báo bảo mật: Thiết bị đã đăng nhập tài khoản khác")
                .body("Thiết bị \"" + event.getDeviceName()
                        + "\" của bạn vừa được đăng nhập bởi tài khoản khác. "
                        + "Nếu không phải bạn, hãy đổi mật khẩu ngay.")
                .saveToHistory(true)
                .bypassPreferences(true)
                .build();

        notificationService.send(request);
    }

    /**
     * Syncs reminder settings from learning-preferences into notification_prefs
     * so the DailyReminderScheduler uses the value set by the user in the settings screen.
     */
    @EventListener
    @Transactional
    public void handleReminderSettingsUpdated(ReminderSettingsUpdatedEvent event) {
        log.debug("Syncing reminder settings for user {} — time={}, enabled={}",
                event.getUserId(), event.getReminderTime(), event.getReminderEnabled());

        UserNotificationPreferences prefs = notificationPreferencesRepository
                .findByUserId(event.getUserId())
                .orElseGet(() -> {
                    log.debug("Creating default notification prefs for user {}", event.getUserId());
                    return UserNotificationPreferences.createDefault(event.getUserId());
                });

        if (event.getReminderTime() != null) {
            prefs.setDailyReminderTime(event.getReminderTime());
        }
        if (event.getReminderEnabled() != null) {
            prefs.setDailyReminderEnabled(event.getReminderEnabled());
        }

        notificationPreferencesRepository.save(prefs);
        log.info("Notification prefs synced for user {}", event.getUserId());
    }
}

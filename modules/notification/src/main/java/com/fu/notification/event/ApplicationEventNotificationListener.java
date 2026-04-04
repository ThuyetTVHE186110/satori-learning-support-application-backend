package com.fu.notification.event;

import com.fu.common.event.*;
import com.fu.notification.dto.SendNotificationRequest;
import com.fu.notification.entity.NotificationPriority;
import com.fu.notification.entity.NotificationType;
import com.fu.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

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

    /**
     * Handles AssignmentCreatedEvent - notifies class members about new assignment.
     */
    @Async
    @EventListener
    public void handleAssignmentCreated(AssignmentCreatedEvent event) {
        log.debug("Processing AssignmentCreatedEvent: assignmentId={}", event.getAssignmentId());

        Map<String, Object> data = new HashMap<>();
        data.put("assignmentId", event.getAssignmentId().toString());
        data.put("classId", event.getClassId().toString());

        // Note: Class member IDs should be fetched by the caller or passed in the event
        // For now, we'll need to integrate with ClassMemberRepository
        // This is a placeholder - actual implementation needs class member lookup
        
        SendNotificationRequest request = SendNotificationRequest.builder()
                .type(NotificationType.ASSIGNMENT_CREATED)
                .priority(NotificationPriority.HIGH)
                .title("📝 Bài tập mới: " + event.getAssignmentTitle())
                .body("Lớp " + event.getClassName() + " có bài tập mới. Hãy hoàn thành trước hạn!")
                .data(data)
                .actionUrl("/assignments/" + event.getAssignmentId())
                .build();

        // TODO: Fetch class members and send to them
        // List<UUID> memberIds = classMemberRepository.findMemberIdsByClassId(event.getClassId());
        // request.setUserIds(memberIds);
        // notificationService.send(request);
        
        log.info("Assignment created notification queued: {}", event.getAssignmentTitle());
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
        data.put("maxScore", event.getMaxScore());

        String scoreText = String.format("%.1f/%.1f", event.getScore(), event.getMaxScore());
        
        SendNotificationRequest request = SendNotificationRequest.builder()
                .userId(event.getStudentId())
                .type(NotificationType.ASSIGNMENT_GRADED)
                .priority(NotificationPriority.NORMAL)
                .title("✅ Bài tập đã được chấm điểm")
                .body("Bài \"" + event.getAssignmentTitle() + "\" của bạn đã được chấm: " + scoreText)
                .data(data)
                .actionUrl("/assignments/" + event.getAssignmentId() + "/result")
                .build();

        notificationService.send(request);
        log.info("Assignment graded notification sent to student: {}", event.getStudentId());
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
     * Handles AchievementUnlockedEvent - notifies user about new achievement.
     */
    @Async
    @EventListener
    public void handleAchievementUnlocked(AchievementUnlockedEvent event) {
        log.debug("Processing AchievementUnlockedEvent: userId={}, achievement={}", 
                event.getUserId(), event.getAchievementTitle());

        Map<String, Object> data = new HashMap<>();
        data.put("achievementId", event.getAchievementId().toString());

        SendNotificationRequest request = SendNotificationRequest.builder()
                .userId(event.getUserId())
                .type(NotificationType.ACHIEVEMENT_UNLOCKED)
                .priority(NotificationPriority.NORMAL)
                .title("🏆 " + event.getAchievementTitle())
                .body(event.getAchievementDescription())
                .data(data)
                .imageUrl(event.getBadgeImageUrl())
                .actionUrl("/achievements")
                .build();

        notificationService.send(request);
        log.info("Achievement notification sent: {} -> {}", event.getUserId(), event.getAchievementTitle());
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
        log.info("Password changed notification sent: {}", event.getUserId());
    }
}

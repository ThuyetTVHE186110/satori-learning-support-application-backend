package com.fu.infrastructure.email.impl;

import com.fu.infrastructure.email.EmailService;
import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.time.LocalDate;
import java.util.Map;

/**
 * Implementation of EmailService using Resend HTTP API.
 * Replaces SMTP-based JavaMailSender to bypass DigitalOcean port restrictions.
 * All email sending is done asynchronously to avoid blocking the main thread.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {

    private final Resend resend;
    private final TemplateEngine templateEngine;

    @Value("${app.email.from:Satori Learning <noreply@satori-learning.com>}")
    private String fromEmail;

    @Value("${app.email.base-url:http://localhost:3000}")
    private String baseUrl;

    @Value("${app.email.enabled:true}")
    private boolean emailEnabled;

    // Resend free tier: 2 req/sec. Keep ≥600 ms between sends to stay safely under the limit.
    private static final long MIN_SEND_INTERVAL_MS = 600;
    /** Guards lastResendCallMs read/sleep/write so two threads can't both pass the gate. */
    private final Object resendLock = new Object();
    private long lastResendCallMs = 0;

    @Override
    @Async("mailExecutor")
    public void sendSimpleEmail(String to, String subject, String text) {
        if (!emailEnabled) {
            log.info("[EMAIL DISABLED] Would send to: {}, subject: {}", to, subject);
            return;
        }

        try {
            CreateEmailOptions params = CreateEmailOptions.builder()
                    .from(fromEmail)
                    .to(to)
                    .subject(subject)
                    .text(text)
                    .build();

            sendThrottled(params);
            log.info("Simple email sent to: {}", to);
        } catch (ResendException e) {
            log.error("Failed to send simple email to: {}", to, e);
        }
    }

    @Override
    @Async("mailExecutor")
    public void sendTemplateEmail(String to, String subject, String templateName, Map<String, Object> variables) {
        if (!emailEnabled) {
            log.info("[EMAIL DISABLED] Would send template '{}' to: {}, subject: {}", templateName, to, subject);
            return;
        }

        try {
            Context context = new Context();
            context.setVariables(variables);
            context.setVariable("baseUrl", baseUrl);

            String htmlContent = templateEngine.process("email/" + templateName, context);

            CreateEmailOptions params = CreateEmailOptions.builder()
                    .from(fromEmail)
                    .to(to)
                    .subject(subject)
                    .html(htmlContent)
                    .build();

            sendThrottled(params);
            log.info("Template email '{}' sent to: {}", templateName, to);
        } catch (ResendException e) {
            log.error("Failed to send template email to: {}", to, e);
        } catch (Exception e) {
            log.error("Unexpected error sending email to: {}", to, e);
        }
    }

    @Override
    @Async("mailExecutor")
    public void sendWelcomeEmailWithCredentials(String to, String userName, String email, String password) {
        String subject = "Chào mừng đến với Satori Learning - Thông tin đăng nhập";

        Map<String, Object> variables = Map.of(
                "userName", userName != null ? userName : "ban",
                "email", email,
                "password", password,
                "loginUrl", baseUrl + "/login",
                "supportEmail", "support@satori-learning.com"
        );

        sendTemplateEmail(to, subject, "welcome-credentials", variables);
        log.info("Welcome email with credentials sent to: {}", to);
    }

    @Override
    @Async("mailExecutor")
    public void sendPasswordResetOtp(String to, String userName, String otp, int expirationMinutes) {
        String subject = "Mã OTP đặt lại mật khẩu - Satori Learning";

        Map<String, Object> variables = Map.of(
                "userName", userName != null ? userName : "ban",
                "otp", otp,
                "expirationMinutes", expirationMinutes
        );

        sendTemplateEmail(to, subject, "password-reset-otp", variables);
        log.info("Password reset OTP sent to: {}", to);
    }

    @Override
    @Async("mailExecutor")
    public void sendPasswordChangedNotification(String to, String userName) {
        String subject = "Mật khẩu đã được thay đổi - Satori Learning";

        Map<String, Object> variables = Map.of(
                "userName", userName != null ? userName : "ban",
                "supportEmail", "support@satori-learning.com"
        );

        sendTemplateEmail(to, subject, "password-changed", variables);
        log.info("Password changed notification sent to: {}", to);
    }

    @Override
    @Async("mailExecutor")
    public void sendNewAssignmentNotification(String to, String userName, String assignmentTitle, String className, String dueDate) {
        String subject = "Thông báo bài tập mới lớp " + className + "-SatoriNihongo";
        Map<String, Object> variables = Map.of(
                "userName", userName != null ? userName : "Bạn",
                "assignmentTitle", assignmentTitle,
                "className", className,
                "dueDate", dueDate,
                "baseUrl", baseUrl
        );
        sendTemplateEmail(to, subject, "new-assignment-notification", variables);
        log.info("New assignment notification sent to: {}", to);
    }

    @Override
    @Async("mailExecutor")
    public void sendEmailChangeNotification(String email, String fullName, String oldEmail) {
        String subject = "Email của bạn đã được thay đổi - Satori Learning";

        Map<String, Object> variables = Map.of(
                "fullName", fullName != null ? fullName : "ban",
                "supportEmail", "support@satori-learning.com",
                "oldEmail", oldEmail,
                "newEmail", email
        );

        sendTemplateEmail(email, subject, "email-changed", variables);
        log.info("Password changed notification sent to: {}", email);
    }

    /**
     * Wraps every Resend API call with a minimum-interval guard.
     * Resend allows 2 req/sec; sleeping ≥600 ms between sends keeps us under that limit.
     *
     * <p>The whole gate (compute wait → sleep → record timestamp → send) runs inside
     * {@code resendLock}. Earlier code used an AtomicLong but read-then-sleep-then-write
     * is not atomic — concurrent threads could both pass the gate before either updated
     * the timestamp, then fire two sends back-to-back and trip Resend's limiter.
     * Holding the lock across the {@code send()} ensures the next thread doesn't
     * compute its wait against a stale timestamp from a still-in-flight call.</p>
     *
     * <p>Pairs with the single-thread {@code mailExecutor} bean — defense in depth in
     * case a future caller invokes the bean from outside the executor.</p>
     */
    private void sendThrottled(CreateEmailOptions params) throws ResendException {
        synchronized (resendLock) {
            long wait = MIN_SEND_INTERVAL_MS - (System.currentTimeMillis() - lastResendCallMs);
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            try {
                resend.emails().send(params);
            } finally {
                lastResendCallMs = System.currentTimeMillis();
            }
        }
    }
}

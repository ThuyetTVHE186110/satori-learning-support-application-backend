package com.fu.infrastructure.email.impl;

import com.fu.infrastructure.email.EmailService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Map;

/**
 * Implementation of EmailService using Spring Mail and Thymeleaf templates.
 * All email sending is done asynchronously to avoid blocking the main thread.
 * 
 * <h2>Email Templates</h2>
 * <ul>
 *   <li>welcome-credentials.html - UC-02: Welcome with login credentials</li>
 *   <li>password-reset-otp.html - UC-07: OTP for password reset</li>
 *   <li>password-changed.html - UC-07, UC-08: Password change confirmation</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    @Value("${app.email.from:noreply@satori-learning.com}")
    private String fromEmail;

    @Value("${app.email.from-name:Satori Learning}")
    private String fromName;

    @Value("${app.email.base-url:http://localhost:3000}")
    private String baseUrl;

    @Value("${app.email.enabled:true}")
    private boolean emailEnabled;

    @Override
    @Async
    public void sendSimpleEmail(String to, String subject, String text) {
        if (!emailEnabled) {
            log.info("[EMAIL DISABLED] Would send to: {}, subject: {}", to, subject);
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(text);
            
            mailSender.send(message);
            log.info("Simple email sent to: {}", to);
        } catch (Exception e) {
            log.error("Failed to send simple email to: {}", to, e);
        }
    }

    @Override
    @Async
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
            
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            
            helper.setFrom(fromEmail, fromName);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);
            
            mailSender.send(message);
            log.info("Template email '{}' sent to: {}", templateName, to);
        } catch (MessagingException e) {
            log.error("Failed to send template email to: {}", to, e);
        } catch (Exception e) {
            log.error("Unexpected error sending email to: {}", to, e);
        }
    }

    @Override
    @Async("mailExecutor")
    public void sendWelcomeEmailWithCredentials(String to, String userName, String email, String password) {
        String subject = "🎉 Chào mừng đến với Satori Learning - Thông tin đăng nhập";
        
        Map<String, Object> variables = Map.of(
                "userName", userName != null ? userName : "bạn",
                "email", email,
                "password", password,
                "loginUrl", baseUrl + "/login",
                "supportEmail", "support@satori-learning.com"
        );
        
        sendTemplateEmail(to, subject, "welcome-credentials", variables);
        log.info("Welcome email with credentials sent to: {}", to);
    }

    @Override
    @Async
    public void sendPasswordResetOtp(String to, String userName, String otp, int expirationMinutes) {
        String subject = "🔐 Mã OTP đặt lại mật khẩu - Satori Learning";
        
        Map<String, Object> variables = Map.of(
                "userName", userName != null ? userName : "bạn",
                "otp", otp,
                "expirationMinutes", expirationMinutes
        );
        
        sendTemplateEmail(to, subject, "password-reset-otp", variables);
        log.info("Password reset OTP sent to: {}", to);
    }

    @Override
    @Async
    public void sendPasswordChangedNotification(String to, String userName) {
        String subject = "🔒 Mật khẩu đã được thay đổi - Satori Learning";
        
        Map<String, Object> variables = Map.of(
                "userName", userName != null ? userName : "bạn",
                "supportEmail", "support@satori-learning.com"
        );
        
        sendTemplateEmail(to, subject, "password-changed", variables);
        log.info("Password changed notification sent to: {}", to);
    }
}


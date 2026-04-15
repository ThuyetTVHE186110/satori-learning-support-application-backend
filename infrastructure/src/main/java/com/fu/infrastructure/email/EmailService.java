package com.fu.infrastructure.email;

import java.time.LocalDate;
import java.util.Map;

/**
 * Email Service Interface.
 * Provides email sending capabilities for the application.
 * Used by authentication, user management, notifications, etc.
 * 
 * <h2>Integration with User Management</h2>
 * <ul>
 *   <li>UC-02: Welcome email with login credentials</li>
 * </ul>
 * 
 * <h2>Integration with Authentication</h2>
 * <ul>
 *   <li>UC-07: Password reset OTP email + confirmation after reset</li>
 *   <li>UC-08: Password changed confirmation email</li>
 * </ul>
 */
public interface EmailService {

    /**
     * Send a simple text email.
     *
     * @param to recipient email address
     * @param subject email subject
     * @param text email body (plain text)
     */
    void sendSimpleEmail(String to, String subject, String text);

    /**
     * Send an HTML email using a template.
     *
     * @param to recipient email address
     * @param subject email subject
     * @param templateName the template name (without extension)
     * @param variables template variables
     */
    void sendTemplateEmail(String to, String subject, String templateName, Map<String, Object> variables);

    /**
     * UC-02: Send welcome email with login credentials.
     * Sent when Manager creates a new user account.
     * Contains:
     * - Login email
     * - Generated password
     * - Instructions to change password on first login
     *
     * @param to recipient email address
     * @param userName user's display name
     * @param email user's login email
     * @param password generated password (plain text)
     */
    void sendWelcomeEmailWithCredentials(String to, String userName, String email, String password);

    /**
     * UC-07: Send password reset OTP email.
     * OTP is 6 digits, valid for specified minutes.
     *
     * @param to recipient email address
     * @param userName user's display name
     * @param otp one-time password (6 digits)
     * @param expirationMinutes OTP expiration time in minutes
     */
    void sendPasswordResetOtp(String to, String userName, String otp, int expirationMinutes);

    /**
     * UC-07 & UC-08: Send password changed confirmation email.
     * Sent after successful password reset or change.
     *
     * @param to recipient email address
     * @param userName user's display name
     */
    void sendPasswordChangedNotification(String to, String userName);

    void sendEmailChangeNotification(String email, String fullName, String oldEmail);

    /**
     * Send email after creating a new assignment to notify the user.
     * Contains:
     * - Assignment title
     * - Class Name
     * - Due date
     */
    void sendNewAssignmentNotification(String to, String userName, String assignmentTitle, String className, String dueDate);
}

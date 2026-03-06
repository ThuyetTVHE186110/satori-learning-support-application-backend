# Auth Controller — Test Procedures
> Format: System Interaction Steps  
> Last updated: 2026-03-06

---

## Section 1 — Login `POST /api/v1/auth/login`

### TC_AUTH_LG_001 — Happy path
1. Client → POST `/api/v1/auth/login` with email and password.
2. AuthController → extract client metadata and call `AuthService.login()`.
3. AuthService → retrieve the user account by email.
4. AuthService → validate the credentials and account status.
5. AuthService → create a login session.
6. AuthService → generate the authentication tokens.
7. System → return the authentication response.

---

### TC_AUTH_LG_002 — Wrong password
1. Client → POST `/api/v1/auth/login` with a correct email but wrong password.
2. AuthController → call `AuthService.login()`.
3. AuthService → retrieve the user account by email.
4. AuthService → fail password verification.
5. AuthService → update the failed login attempt counter.
6. System → return the authentication error response.

---

### TC_AUTH_LG_003 — Non-existing email
1. Client → POST `/api/v1/auth/login` with a non-existing email.
2. AuthController → call `AuthService.login()`.
3. AuthService → attempt to retrieve the user account by email.
4. AuthService → detect that the account does not exist.
5. AuthService → treat as invalid credentials to prevent email enumeration.
6. System → return the authentication error response.

---

### TC_AUTH_LG_004 — SUSPENDED account
1. Client → POST `/api/v1/auth/login` with valid credentials.
2. AuthController → call `AuthService.login()`.
3. AuthService → retrieve the user account by email.
4. AuthService → validate the credentials.
5. AuthService → detect that the account status is SUSPENDED.
6. System → return the account suspended error response.

---

### TC_AUTH_LG_005 — Soft-deleted account
1. Client → POST `/api/v1/auth/login` with valid credentials.
2. AuthController → call `AuthService.login()`.
3. AuthService → retrieve the user account by email.
4. AuthService → detect that the account has been soft-deleted.
5. System → return the account deleted error response.

---

### TC_AUTH_LG_006 — Account locked after 5th wrong attempt
1. Client → POST `/api/v1/auth/login` with wrong password (5th attempt).
2. AuthController → call `AuthService.login()`.
3. AuthService → retrieve the user account by email.
4. AuthService → fail password verification.
5. AuthService → update the failed login attempt counter and lock the account.
6. System → return the account locked error response.

---

### TC_AUTH_LG_007 — Login succeeds after lock expires
1. Client → POST `/api/v1/auth/login` with valid credentials (lock already expired).
2. AuthController → call `AuthService.login()`.
3. AuthService → retrieve the user account by email.
4. AuthService → detect that the lock has expired.
5. AuthService → reset the failed login attempt counter.
6. AuthService → validate the credentials and account status.
7. AuthService → create a login session.
8. AuthService → generate the authentication tokens.
9. System → return the authentication response.

---

### TC_AUTH_LG_008 — Expired access token
1. Client → POST `/api/v1/auth/logout` with an expired Bearer token.
2. AuthFilter → parse the token and detect it is expired.
3. System → return the unauthorized error response.

---

### TC_AUTH_LG_009 — Valid refresh token
1. Client → POST `/api/v1/auth/refresh` with a valid refreshToken.
2. AuthController → call `AuthService.refresh()`.
3. AuthService → retrieve the session by token.
4. AuthService → validate that the session is active and not expired.
5. AuthService → generate a new accessToken (same refreshToken — no rotation).
6. System → return the authentication response.

---

### TC_AUTH_LG_010 — Refresh token reuse allowed
1. Client → POST `/api/v1/auth/refresh` with a valid refreshToken (first use).
2. AuthController → call `AuthService.refresh()`.
3. AuthService → retrieve the session by token.
4. AuthService → validate that the session is active and issue a new accessToken.
5. Client → POST `/api/v1/auth/refresh` with the same refreshToken (second use).
6. AuthController → call `AuthService.refresh()`.
7. AuthService → retrieve the session by token.
8. AuthService → validate that the session is still active and issue a new accessToken.
9. System → return the authentication response. *(KNOWN GAP: token rotation not yet implemented)*

---

### TC_AUTH_LG_011 — SQL injection in email field
1. Client → POST `/api/v1/auth/login` with a malformed email containing SQL injection.
2. AuthController → validate the request body.
3. AuthController → detect that the email format is invalid.
4. System → return the validation error response before reaching AuthService.

---

### TC_AUTH_LG_012 — Empty request body
1. Client → POST `/api/v1/auth/login` with an empty body `{}`.
2. AuthController → validate the request body.
3. AuthController → detect that required fields are missing.
4. System → return the validation error response before reaching AuthService.

---

### TC_AUTH_LG_013 — Expired refresh token
1. Client → POST `/api/v1/auth/refresh` with an expired refreshToken.
2. AuthController → call `AuthService.refresh()`.
3. AuthService → retrieve the session by token.
4. AuthService → detect that the session has expired.
5. System → return the session expired error response.

---

### TC_AUTH_LG_014 — Revoked refresh token
1. Client → POST `/api/v1/auth/refresh` with a revoked refreshToken.
2. AuthController → call `AuthService.refresh()`.
3. AuthService → retrieve the session by token.
4. AuthService → detect that the session has been revoked.
5. System → return the session expired error response.

---

## Section 2 — Forgot Password

### TC_AUTH_FP_001 — Full forgot-password flow
1. Client → POST `/api/v1/auth/forgot-password` with a registered email.
2. AuthController → call `AuthService.sendPasswordResetOtp()`.
3. AuthService → retrieve the user account by email.
4. AuthService → generate a 6-digit OTP and save it.
5. AuthService → send the OTP to the user via EmailService.
6. Client → POST `/api/v1/auth/verify-otp` with the correct OTP.
7. AuthController → call `AuthService.verifyOtp()`.
8. AuthService → retrieve and validate the OTP by email.
9. AuthService → generate a resetToken and save it.
10. Client → POST `/api/v1/auth/reset-password` with resetToken and new password.
11. AuthController → call `AuthService.resetPassword()`.
12. AuthService → retrieve the account by resetToken.
13. AuthService → validate that the token is not expired and not yet used.
14. AuthService → save the new password and clear the resetToken.
15. AuthService → notify the user via EmailService.
16. System → return the success response.

---

### TC_AUTH_FP_002 — Wrong OTP
1. Client → POST `/api/v1/auth/forgot-password` — OTP generated and saved.
2. Client → POST `/api/v1/auth/verify-otp` with a wrong OTP.
3. AuthController → call `AuthService.verifyOtp()`.
4. AuthService → retrieve the OTP by email.
5. AuthService → detect that the OTP value does not match.
6. System → return the invalid OTP error response.

---

### TC_AUTH_FP_003 — Expired OTP
1. Client → POST `/api/v1/auth/verify-otp` with a correct but expired OTP.
2. AuthController → call `AuthService.verifyOtp()`.
3. AuthService → retrieve the OTP by email.
4. AuthService → detect that the OTP has already expired.
5. System → return the OTP expired error response.

---

### TC_AUTH_FP_004 — Invalid reset token
1. Client → POST `/api/v1/auth/reset-password` with a non-existing resetToken.
2. AuthController → call `AuthService.resetPassword()`.
3. AuthService → attempt to retrieve the account by resetToken.
4. AuthService → detect that the token does not exist.
5. System → return the invalid token error response.

---

### TC_AUTH_FP_005 — Expired reset token
1. Client → POST `/api/v1/auth/reset-password` with a valid but expired resetToken.
2. AuthController → call `AuthService.resetPassword()`.
3. AuthService → retrieve the account by resetToken.
4. AuthService → detect that the token has already expired.
5. System → return the token expired error response.

---

### TC_AUTH_FP_006 — Reset token single-use
1. Client → POST `/api/v1/auth/reset-password` with a valid resetToken (first use).
2. AuthController → call `AuthService.resetPassword()`.
3. AuthService → validate and consume the resetToken.
4. AuthService → clear the resetToken after the first use.
5. Client → POST `/api/v1/auth/reset-password` with the same resetToken (second use).
6. AuthController → call `AuthService.resetPassword()`.
7. AuthService → attempt to retrieve the account by resetToken.
8. AuthService → detect that the token no longer exists.
9. System → return the invalid token error response.

---

### TC_AUTH_FP_007 — Weak new password
1. Client → POST `/api/v1/auth/reset-password` with a weak new password.
2. AuthController → validate the request body.
3. AuthController → detect that the password does not meet the policy requirements.
4. System → return the validation error response before reaching AuthService.

---

### TC_AUTH_FP_008 — Confirm password mismatch
1. Client → POST `/api/v1/auth/reset-password` with newPassword ≠ confirmPassword.
2. AuthController → call `AuthService.resetPassword()`.
3. AuthService → retrieve the account by resetToken.
4. AuthService → detect the password mismatch.
5. System → return the password mismatch error response.

---

## Section 3 — Change Password `POST /api/v1/auth/change-password`

### TC_AUTH_CP_001 — Successful password change
1. Client → POST `/api/v1/auth/change-password` with a valid Bearer token and correct currentPassword.
2. AuthFilter → parse the token and resolve the user identity.
3. AuthController → call `AuthService.changePassword()`.
4. AuthService → retrieve the user account by identity.
5. AuthService → verify the current password.
6. AuthService → save the new password.
7. AuthService → notify the user via EmailService.
8. System → return the success response.

---

### TC_AUTH_CP_002 — logoutOtherDevices=true
1. Client → POST `/api/v1/auth/change-password` with logoutOtherDevices = true.
2. AuthFilter → parse the token and resolve the user identity.
3. AuthController → call `AuthService.changePassword()`.
4. AuthService → retrieve the user account by identity.
5. AuthService → verify the current password and save the new password.
6. AuthService → retrieve all active sessions of the user.
7. AuthService → revoke all sessions except the current one.
8. System → return the success response.

---

### TC_AUTH_CP_003 — Wrong current password
1. Client → POST `/api/v1/auth/change-password` with a wrong currentPassword.
2. AuthFilter → parse the token and resolve the user identity.
3. AuthController → call `AuthService.changePassword()`.
4. AuthService → retrieve the user account by identity.
5. AuthService → fail current password verification.
6. System → return the wrong password error response.

---

### TC_AUTH_CP_004 — New password same as current
1. Client → POST `/api/v1/auth/change-password` with newPassword = currentPassword.
2. AuthFilter → parse the token and resolve the user identity.
3. AuthController → call `AuthService.changePassword()`.
4. AuthService → retrieve the user account by identity.
5. AuthService → verify the current password.
6. AuthService → detect that the new password is identical to the current one (BR-59).
7. System → return the password reuse error response.

---

### TC_AUTH_CP_005 — Confirm password mismatch
1. Client → POST `/api/v1/auth/change-password` with newPassword ≠ confirmPassword.
2. AuthFilter → parse the token and resolve the user identity.
3. AuthController → call `AuthService.changePassword()`.
4. AuthService → detect the password mismatch.
5. System → return the password mismatch error response.

---

### TC_AUTH_CP_006 — Weak new password
1. Client → POST `/api/v1/auth/change-password` with a weak new password.
2. AuthController → validate the request body.
3. AuthController → detect that the password does not meet the policy requirements.
4. System → return the validation error response before reaching AuthService.

---

### TC_AUTH_CP_007 — Unauthenticated request
1. Client → POST `/api/v1/auth/change-password` with no Authorization header.
2. AuthFilter → detect that no token is present.
3. AuthFilter → reject the request as unauthenticated.
4. System → return the unauthorized error response.

---

### TC_AUTH_CP_008 — Expired access token
1. Client → POST `/api/v1/auth/change-password` with an expired Bearer token.
2. AuthFilter → parse the token and detect it is expired.
3. System → return the unauthorized error response.

---

## Section 4 — Logout `POST /api/v1/auth/logout`

### TC_AUTH_LO_001 — Logout revokes session
1. Client → POST `/api/v1/auth/logout` with a valid Bearer token and refreshToken.
2. AuthFilter → parse the token and resolve the user identity.
3. AuthController → call `AuthService.logout()`.
4. AuthService → retrieve the session by refreshToken.
5. AuthService → revoke the session.
6. System → return the success response.

---

### TC_AUTH_LO_002 — logoutAll=true revokes all sessions
1. Client → POST `/api/v1/auth/logout` with logoutAll = true.
2. AuthFilter → parse the token and resolve the user identity.
3. AuthController → call `AuthService.logout()`.
4. AuthService → retrieve all active sessions of the user.
5. AuthService → revoke all sessions.
6. System → return the success response.

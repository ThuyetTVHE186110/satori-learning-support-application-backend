# AuthController Integration Test Script

## Overview
This document contains detailed test scripts for all authentication API endpoints in the Satori Learning Support Application backend.

## Test Environment Setup

### Pre-conditions
- Test DB running via `application-test.properties`
- PostgreSQL `satori_test` database created and initialized
- Docker environment up and running

---

## Section 1 — Login Tests (POST /api/v1/auth/login)

### Test Case ID: TC_AUTH_LG_001
**Test Case Description:** Login successfully — valid credentials return JWT token pair with correct role

**Pre-conditions:**
ACTIVE LEARNER inserted via userRepository.save():
- email = learner@test.com
- password = passwordEncoder.encode("ValidPass@1")
- role = LEARNER
- status = ACTIVE
- isDeleted = false
- failedLoginAttempts = 0

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/login with email and password.
2. AuthController extracts client metadata and calls AuthService.login().
3. AuthService calls UserRepository.findByEmail() to fetch the user record.
4. UserRepository executes database query and returns the user object.
5. AuthService validates the credentials and account status.
6. AuthService creates a login session via UserSessionRepository.save().
7. UserSessionRepository saves the new session with generated tokens.
8. AuthService generates the authentication tokens.
9. System returns the authentication response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.accessToken: non-null, non-empty
- data.refreshToken: non-null, non-empty
- data.tokenType = "Bearer"
- data.expiresIn = 1800
- data.user.role = "LEARNER"
- data.user.email = "learner@test.com"

---

### Test Case ID: TC_AUTH_LG_002
**Test Case Description:** Wrong password → INVALID_CREDENTIALS

**Pre-conditions:**
ACTIVE LEARNER inserted via userRepository.save():
- email = learner@test.com
- password = passwordEncoder.encode("ValidPass@1")
- role = LEARNER
- status = ACTIVE
- isDeleted = false
- failedLoginAttempts = 0

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/login with correct email but wrong password.
2. AuthController extracts client metadata and calls AuthService.login().
3. AuthService calls UserRepository.findByEmail() to fetch the user record.
4. UserRepository executes database query and returns the user object.
5. AuthService fails password verification.
6. AuthService updates the failed login attempt counter via UserRepository.save().
7. UserRepository saves the updated user with incremented failedLoginAttempts.
8. System returns the authentication error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "INVALID_CREDENTIALS"

---

### Test Case ID: TC_AUTH_LG_003
**Test Case Description:** Non-existing email → INVALID_CREDENTIALS

**Pre-conditions:**
No user with email "ghost@test.com" exists in database.

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/login with a non-existing email.
2. AuthController extracts client metadata and calls AuthService.login().
3. AuthService calls UserRepository.findByEmail() to fetch the user record.
4. UserRepository executes database query and returns empty (no user found).
5. AuthService returns authentication failure.
6. System returns the authentication error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "INVALID_CREDENTIALS"

---

### Test Case ID: TC_AUTH_LG_004
**Test Case Description:** SUSPENDED account → ACCOUNT_SUSPENDED

**Pre-conditions:**
SUSPENDED LEARNER inserted via userRepository.save():
- email = suspended@test.com
- password = passwordEncoder.encode("ValidPass@1")
- role = LEARNER
- status = SUSPENDED
- isDeleted = false
- failedLoginAttempts = 0

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/login with valid credentials.
2. AuthController extracts client metadata and calls AuthService.login().
3. AuthService calls UserRepository.findByEmail() to fetch the user record.
4. UserRepository executes database query and returns the user object.
5. AuthService validates account status and detects suspension.
6. System returns the account suspended error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "ACCOUNT_SUSPENDED"

---

### Test Case ID: TC_AUTH_LG_005
**Test Case Description:** Soft-deleted account → ACCOUNT_DELETED

**Pre-conditions:**
Soft-deleted LEARNER inserted via userRepository.save():
- email = deleted@test.com
- password = passwordEncoder.encode("ValidPass@1")
- role = LEARNER
- status = ACTIVE
- isDeleted = true
- deletedAt = LocalDateTime.now()
- deletedBy = "system"
- failedLoginAttempts = 0

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/login with valid credentials.
2. AuthController extracts client metadata and calls AuthService.login().
3. AuthService calls UserRepository.findByEmail() to fetch the user record.
4. UserRepository executes database query and returns the user object.
5. AuthService detects soft-deleted account.
6. System returns the account deleted error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "ACCOUNT_DELETED"

---

### Test Case ID: TC_AUTH_LG_006
**Test Case Description:** 5th wrong attempt → ACCOUNT_LOCKED + DB state verified

**Pre-conditions:**
ACTIVE LEARNER inserted via userRepository.save():
- email = learner@test.com
- password = passwordEncoder.encode("ValidPass@1")
- role = LEARNER
- status = ACTIVE
- isDeleted = false
- failedLoginAttempts = 4 (pre-set via UserRepository.save())

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/login with wrong password (5th attempt).
2. AuthController extracts client metadata and calls AuthService.login().
3. AuthService calls UserRepository.findByEmail() to fetch the user record.
4. UserRepository executes database query and returns the user object.
5. AuthService validates credentials and detects password mismatch.
6. AuthService increments failedLoginAttempts to 5 and locks the account via UserRepository.save().
7. UserRepository saves the updated user with status = LOCKED and failedLoginAttempts = 5.
8. System returns the account locked error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "ACCOUNT_LOCKED"
Database state: user.status = LOCKED, user.failedLoginAttempts = 5

---

### Test Case ID: TC_AUTH_LG_007
**Test Case Description:** Login succeeds after lock expires; failedAttempts reset to 0

**Pre-conditions:**
LOCKED LEARNER inserted via userRepository.save():
- email = learner@test.com
- password = passwordEncoder.encode("ValidPass@1")
- role = LEARNER
- status = LOCKED
- isDeleted = false
- failedLoginAttempts = 5
- lockTime = LocalDateTime.now().minusHours(25) (lock expired)

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/login with valid credentials (lock already expired).
2. AuthController extracts client metadata and calls AuthService.login().
3. AuthService calls UserRepository.findByEmail() to fetch the user record.
4. UserRepository executes database query and returns the user object.
5. AuthService detects lock has expired and resets account state via UserRepository.save().
6. UserRepository saves the updated user with status = ACTIVE and failedLoginAttempts = 0.
7. AuthService validates credentials and creates session via UserSessionRepository.save().
8. UserSessionRepository saves the new session with generated tokens.
9. AuthService generates tokens.
10. System returns the authentication response.

**Expected Results:** HTTP 200 OK
Database state: user.status = ACTIVE, user.failedLoginAttempts = 0

---

### Test Case ID: TC_AUTH_LG_008
**Test Case Description:** Expired access token → 401 UNAUTHORIZED

**Pre-conditions:**
ACTIVE LEARNER exists in database.

**Test Case Procedure:**
1. Create expired JWT token (iat = -1 hour, exp = -30 minutes) using JwtService.
2. Client sends POST /api/v1/auth/logout with expired Bearer token.
3. JwtService validates token and detects expiration.
4. System returns the unauthorized error response.

**Expected Results:** HTTP 401 UNAUTHORIZED
Response JSON:
- success = false
- code = "UNAUTHORIZED"

---

### Test Case ID: TC_AUTH_LG_009
**Test Case Description:** Valid refresh → new accessToken, same refreshToken

**Pre-conditions:**
ACTIVE LEARNER inserted via userRepository.save():
- email = learner@test.com
- password = passwordEncoder.encode("ValidPass@1")
- role = LEARNER
- status = ACTIVE
- isDeleted = false

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/login to get initial tokens.
2. AuthController processes login and creates session via UserSessionRepository.save().
3. UserSessionRepository saves the new session with generated tokens.
4. System returns the authentication response with tokens.
5. Client sends POST /api/v1/auth/refresh with valid refreshToken.
6. AuthController calls AuthService.refreshToken().
7. AuthService validates refreshToken and creates new session via UserSessionRepository.save().
8. UserSessionRepository saves the new session with new accessToken.
9. AuthService generates new accessToken but keeps same refreshToken.
10. System returns the authentication response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.accessToken: new token (different from original)
- data.refreshToken: same as original
- data.expiresIn = 1800

---

### Test Case ID: TC_AUTH_LG_010
**Test Case Description:** Refresh token reuse succeeds (KNOWN GAP: rotation not implemented)

**Pre-conditions:**
ACTIVE LEARNER inserted via userRepository.save():
- email = learner@test.com
- password = passwordEncoder.encode("ValidPass@1")
- role = LEARNER
- status = ACTIVE
- isDeleted = false

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/login to get initial tokens.
2. AuthController processes login and creates session via UserSessionRepository.save().
3. UserSessionRepository saves the new session with generated tokens.
4. System returns the authentication response with tokens.
5. Client sends POST /api/v1/auth/refresh with valid refreshToken (first use).
6. AuthController calls AuthService.refreshToken().
7. AuthService validates refreshToken and creates new session via UserSessionRepository.save().
8. UserSessionRepository saves the new session with new accessToken.
9. System returns new accessToken with same refreshToken.
10. Client sends POST /api/v1/auth/refresh with same refreshToken (second use).
11. AuthService allows reuse and creates another session via UserSessionRepository.save().
12. UserSessionRepository saves the new session with another new accessToken.
13. System returns the authentication response (KNOWN GAP: token rotation not yet implemented).

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.accessToken: new token
- data.refreshToken: same as original

---

### Test Case ID: TC_AUTH_LG_011
**Test Case Description:** SQL injection in email → 400, no internal details exposed

**Pre-conditions:**
None (validation occurs before database access).

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/login with malformed email containing SQL injection.
2. Request validation fails at controller level.
3. System returns the validation error response before reaching AuthService.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- No internal details exposed

---

### Test Case ID: TC_AUTH_LG_012
**Test Case Description:** Empty body {} → 400, validation error message present

**Pre-conditions:**
None (validation occurs before database access).

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/login with empty request body {}.
2. Request validation fails at controller level.
3. System returns the validation error response before reaching AuthService.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- Validation error message present

---

### Test Case ID: TC_AUTH_LG_013
**Test Case Description:** Expired refresh token → SESSION_EXPIRED

**Pre-conditions:**
ACTIVE LEARNER inserted via userRepository.save():
- email = learner@test.com
- password = passwordEncoder.encode("ValidPass@1")
- role = LEARNER
- status = ACTIVE
- isDeleted = false

A login session exists with:
- refreshToken generated from /api/v1/auth/login
- refreshTokenExpiresAt = LocalDateTime.now().minusMinutes(1) (expired)

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/login to get initial tokens.
2. AuthController processes login and creates session via UserSessionRepository.save().
3. UserSessionRepository saves the new session with generated tokens.
4. System returns the authentication response with tokens.
5. Manually update session.refreshTokenExpiresAt to past time via UserSessionRepository.save().
6. UserSessionRepository saves the updated session with expired timestamp.
7. Client sends POST /api/v1/auth/refresh with expired refreshToken.
8. AuthService validates refreshToken and detects expiration.
9. System returns the session expired error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "SESSION_EXPIRED"

---

### Test Case ID: TC_AUTH_LG_014
**Test Case Description:** Revoked refresh token → SESSION_EXPIRED

**Pre-conditions:**
ACTIVE LEARNER inserted via userRepository.save():
- email = learner@test.com
- password = passwordEncoder.encode("ValidPass@1")
- role = LEARNER
- status = ACTIVE
- isDeleted = false

A login session exists with:
- refreshToken generated from /api/v1/auth/login
- revokedAt = LocalDateTime.now().minusSeconds(1) (session already revoked)

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/login to get initial tokens.
2. AuthController processes login and creates session via UserSessionRepository.save().
3. UserSessionRepository saves the new session with generated tokens.
4. System returns the authentication response with tokens.
5. Manually set session.revokedAt to non-null value via UserSessionRepository.save().
6. UserSessionRepository saves the updated session with revoked timestamp.
7. Client sends POST /api/v1/auth/refresh with revoked refreshToken.
8. AuthService validates refreshToken and detects revocation.
9. System returns the session expired error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "SESSION_EXPIRED"

---

### Test Case ID: TC_AUTH_LG_015
**Test Case Description:** Email with whitespace trimmed (BR-48) → successful login

**Pre-conditions:**
ACTIVE LEARNER inserted via userRepository.save():
- email = learner@test.com
- password = passwordEncoder.encode("ValidPass@1")
- role = LEARNER
- status = ACTIVE
- isDeleted = false

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/login with email containing leading/trailing whitespace.
2. AuthController trims whitespace from email.
3. AuthController calls AuthService.login() with trimmed email.
4. AuthService calls UserRepository.findByEmail() with trimmed email.
5. UserRepository executes database query and returns the user object.
6. AuthService validates credentials and creates session via UserSessionRepository.save().
7. UserSessionRepository saves the new session with generated tokens.
8. AuthService generates tokens.
9. System returns the authentication response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- Standard token response

---

### Test Case ID: TC_AUTH_LG_016
**Test Case Description:** Email length > 255 characters (BR-49) → 400 validation error

**Pre-conditions:**
None (validation occurs before database access).

**Test Case Procedure:**
1. Create email with 256 characters (255 + 1 extra).
2. Client sends POST /api/v1/auth/login with oversized email.
3. Request validation fails at controller level.
4. System returns the validation error response before reaching AuthService.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- Validation error for email length

---

## Section 2 — Forgot Password Tests

### Test Case ID: TC_AUTH_FP_001
**Test Case Description:** Full forgot-password flow succeeds end-to-end

**Pre-conditions:**
ACTIVE LEARNER inserted via userRepository.save():
- email = learner@test.com
- password = passwordEncoder.encode("ValidPass@1")
- role = LEARNER
- status = ACTIVE
- isDeleted = false

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/forgot-password with valid email.
2. AuthController calls AuthService.requestPasswordReset().
3. AuthService generates 6-digit OTP and sets expiry via UserRepository.save().
4. UserRepository saves the updated user with OTP and expiry.
5. EmailService sends OTP to user.
6. System returns success response.
7. Client sends POST /api/v1/auth/verify-otp with correct OTP.
8. AuthService calls UserRepository.findByEmail() to fetch user.
9. UserRepository executes database query and returns the user object.
10. AuthService validates OTP and generates resetToken via UserRepository.save().
11. UserRepository saves the updated user with resetToken and clears OTP.
12. System returns success response with resetToken.
13. Client sends POST /api/v1/auth/reset-password with resetToken and new password.
14. AuthService calls UserRepository.findByResetToken() to fetch user.
15. UserRepository executes database query and returns the user object.
16. AuthService validates resetToken and updates password via UserRepository.save().
17. UserRepository saves the updated user with new password hash.
18. EmailService sends password change notification.
19. System returns success response.

**Expected Results:** HTTP 200 OK for all steps
Password updated in database with new hash

---

### Test Case ID: TC_AUTH_FP_002
**Test Case Description:** Wrong OTP → INVALID_OTP

**Pre-conditions:**
ACTIVE LEARNER inserted via userRepository.save():
- email = learner@test.com
- password = passwordEncoder.encode("ValidPass@1")
- role = LEARNER
- status = ACTIVE
- isDeleted = false

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/forgot-password with valid email.
2. AuthController calls AuthService.requestPasswordReset().
3. AuthService generates 6-digit OTP and sets expiry via UserRepository.save().
4. UserRepository saves the updated user with OTP and expiry.
5. EmailService sends OTP to user.
6. System returns success response.
7. Client sends POST /api/v1/auth/verify-otp with wrong OTP.
8. AuthService calls UserRepository.findByEmail() to fetch user.
9. UserRepository executes database query and returns the user object.
10. AuthService validates OTP and detects mismatch.
11. System returns the invalid OTP error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "INVALID_OTP"

---

### Test Case ID: TC_AUTH_FP_003
**Test Case Description:** Expired OTP → OTP_EXPIRED

**Pre-conditions:**
ACTIVE LEARNER inserted via userRepository.save():
- email = learner@test.com
- password = passwordEncoder.encode("ValidPass@1")
- role = LEARNER
- status = ACTIVE
- isDeleted = false
- passwordResetToken = "123456"
- passwordResetTokenExpiresAt = LocalDateTime.now().minusMinutes(1) (expired)

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/verify-otp with expired OTP.
2. AuthService calls UserRepository.findByEmail() to fetch user.
3. UserRepository executes database query and returns the user object.
4. AuthService validates OTP and detects expiration.
5. System returns the OTP expired error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "OTP_EXPIRED"

---

### Test Case ID: TC_AUTH_FP_004
**Test Case Description:** Invalid reset token → INVALID_TOKEN

**Pre-conditions:**
None (invalid UUID token).

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/reset-password with invalid resetToken.
2. AuthService calls UserRepository.findByResetToken() to fetch user.
3. UserRepository executes database query and returns empty (no user found).
4. System returns the invalid token error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "INVALID_TOKEN"

---

### Test Case ID: TC_AUTH_FP_005
**Test Case Description:** Expired reset token → TOKEN_EXPIRED

**Pre-conditions:**
ACTIVE LEARNER inserted via userRepository.save():
- email = learner@test.com
- password = passwordEncoder.encode("ValidPass@1")
- role = LEARNER
- status = ACTIVE
- isDeleted = false

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/forgot-password with valid email.
2. AuthController calls AuthService.requestPasswordReset().
3. AuthService generates 6-digit OTP and sets expiry via UserRepository.save().
4. Client sends POST /api/v1/auth/verify-otp with correct OTP.
5. AuthService validates OTP and generates resetToken via UserRepository.save().
6. Manually set user.passwordResetTokenExpiresAt to past time via UserRepository.save().
7. Client sends POST /api/v1/auth/reset-password with expired resetToken.
8. AuthService calls UserRepository.findByResetToken() to fetch user.
9. AuthService validates resetToken and detects expiration.
10. System returns the token expired error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "TOKEN_EXPIRED"

---

### Test Case ID: TC_AUTH_FP_006
**Test Case Description:** Reset token single-use: reuse after success → INVALID_TOKEN

**Pre-conditions:**
ACTIVE LEARNER inserted via userRepository.save():
- email = learner@test.com
- password = passwordEncoder.encode("ValidPass@1")
- role = LEARNER
- status = ACTIVE
- isDeleted = false

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/forgot-password with valid email.
2. AuthController calls AuthService.requestPasswordReset().
3. AuthService generates 6-digit OTP and sets expiry via UserRepository.save().
4. UserRepository saves the updated user with OTP and expiry.
5. EmailService sends OTP to user.
6. System returns success response.
7. Client sends POST /api/v1/auth/verify-otp with correct OTP.
8. AuthService calls UserRepository.findByEmail() to fetch user.
9. UserRepository executes database query and returns the user object.
10. AuthService validates OTP and generates resetToken via UserRepository.save().
11. UserRepository saves the updated user with resetToken and clears OTP.
12. System returns success response with resetToken.
13. Client sends POST /api/v1/auth/reset-password with resetToken and valid passwords (first use).
14. AuthService calls UserRepository.findByResetToken() to fetch user.
15. UserRepository executes database query and returns the user object.
16. AuthService validates resetToken and updates password via UserRepository.save().
17. UserRepository saves the updated user with new password hash and clears resetToken.
18. EmailService sends password change notification.
19. System returns success response.
20. Client sends POST /api/v1/auth/reset-password with same resetToken (second use).
21. AuthService calls UserRepository.findByResetToken() to fetch user.
22. UserRepository executes database query and returns empty (resetToken already cleared).
23. System returns the invalid token error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "INVALID_TOKEN"

---

### Test Case ID: TC_AUTH_FP_007
**Test Case Description:** Weak new password on reset → 400 validation error

**Pre-conditions:**
None (validation occurs before database access).

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/reset-password with weak password.
2. AuthController validates the request body.
3. AuthController detects that the password does not meet the policy requirements.
4. System returns the validation error response before reaching AuthService.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- Validation error message

---

### Test Case ID: TC_AUTH_FP_008
**Test Case Description:** Confirm password mismatch on reset → PASSWORD_MISMATCH

**Pre-conditions:**
ACTIVE LEARNER inserted via userRepository.save():
- email = learner@test.com
- password = passwordEncoder.encode("ValidPass@1")
- role = LEARNER
- status = ACTIVE
- isDeleted = false

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/forgot-password with valid email.
2. AuthController calls AuthService.requestPasswordReset().
3. AuthService generates 6-digit OTP and sets expiry via UserRepository.save().
4. UserRepository saves the updated user with OTP and expiry.
5. EmailService sends OTP to user.
6. System returns success response.
7. Client sends POST /api/v1/auth/verify-otp with correct OTP.
8. AuthService calls UserRepository.findByEmail() to fetch user.
9. UserRepository executes database query and returns the user object.
10. AuthService validates OTP and generates resetToken via UserRepository.save().
11. UserRepository saves the updated user with resetToken and clears OTP.
12. System returns success response with resetToken.
13. Client sends POST /api/v1/auth/reset-password with mismatched passwords.
14. AuthService validates password confirmation and detects mismatch.
15. System returns the password mismatch error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "PASSWORD_MISMATCH"

---

### Test Case ID: TC_AUTH_FP_009
**Test Case Description:** Non-existent email during OTP verification → INVALID_OTP

**Pre-conditions:**
ACTIVE LEARNER inserted via userRepository.save():
- email = learner@test.com
- password = passwordEncoder.encode("ValidPass@1")
- role = LEARNER
- status = ACTIVE
- isDeleted = false

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/forgot-password with valid email.
2. AuthController calls AuthService.requestPasswordReset().
3. AuthService generates 6-digit OTP and sets expiry via UserRepository.save().
4. Client sends POST /api/v1/auth/verify-otp with non-existent email.
5. AuthService calls UserRepository.findByEmail() and returns empty.
6. System returns the invalid OTP error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "INVALID_OTP"

---

### Test Case ID: TC_AUTH_FP_009b
**Test Case Description:** Email mismatch during OTP verification → INVALID_OTP

**Pre-conditions:**
User A inserted via userRepository.save():
- email = userA@test.com
- password = passwordEncoder.encode("ValidPass@1")
- role = LEARNER
- status = ACTIVE
- isDeleted = false

User B inserted via userRepository.save():
- email = userB@test.com
- password = passwordEncoder.encode("ValidPass@1")
- role = LEARNER
- status = ACTIVE
- isDeleted = false

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/forgot-password for userA@test.com.
2. AuthController calls AuthService.requestPasswordReset().
3. AuthService generates 6-digit OTP for userA and sets expiry via UserRepository.save().
4. Client sends POST /api/v1/auth/verify-otp with userB@test.com but userA's OTP.
5. AuthService calls UserRepository.findByEmail() to fetch userB.
6. AuthService detects email mismatch (userB's OTP is null/empty).
7. System returns the invalid OTP error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "INVALID_OTP"

---

### Test Case ID: TC_AUTH_FP_010
**Test Case Description:** Resend OTP (A4) → new OTP generated, old OTP invalid

**Pre-conditions:**
ACTIVE LEARNER inserted via userRepository.save():
- email = learner@test.com
- password = passwordEncoder.encode("ValidPass@1")
- role = LEARNER
- status = ACTIVE
- isDeleted = false

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/forgot-password with valid email (first request).
2. AuthController calls AuthService.requestPasswordReset().
3. AuthService generates 6-digit OTP and sets expiry via UserRepository.save().
4. UserRepository saves the updated user with first OTP and expiry.
5. EmailService sends first OTP to user.
6. System returns success response.
7. Client sends POST /api/v1/auth/forgot-password with same email (resend request).
8. AuthController calls AuthService.requestPasswordReset().
9. AuthService generates new 6-digit OTP and updates expiry via UserRepository.save().
10. UserRepository saves the updated user with second OTP (different from first).
11. EmailService sends second OTP to user.
12. System returns success response.
13. Client sends POST /api/v1/auth/verify-otp with old OTP (first OTP).
14. AuthService calls UserRepository.findByEmail() to fetch user.
15. UserRepository executes database query and returns the user object.
16. AuthService validates OTP and detects it doesn't match current OTP.
17. System returns the invalid OTP error response.
18. Client sends POST /api/v1/auth/verify-otp with new OTP (second OTP).
19. AuthService calls UserRepository.findByEmail() to fetch user.
20. UserRepository executes database query and returns the user object.
21. AuthService validates OTP and generates resetToken via UserRepository.save().
22. UserRepository saves the updated user with resetToken and clears OTP.
23. System returns success response with resetToken.

**Expected Results:** 
- Old OTP: HTTP 400 BAD REQUEST with INVALID_OTP
- New OTP: HTTP 200 OK with resetToken

---

### Test Case ID: TC_AUTH_FP_011
**Test Case Description:** Password reset doesn't auto-login (BR-58) → must login with new credentials

**Pre-conditions:**
ACTIVE LEARNER inserted via userRepository.save():
- email = learner@test.com
- password = passwordEncoder.encode("ValidPass@1")
- role = LEARNER
- status = ACTIVE
- isDeleted = false

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/forgot-password with valid email.
2. AuthController calls AuthService.requestPasswordReset().
3. AuthService generates 6-digit OTP and sets expiry via UserRepository.save().
4. UserRepository saves the updated user with OTP and expiry.
5. EmailService sends OTP to user.
6. System returns success response.
7. Client sends POST /api/v1/auth/verify-otp with correct OTP.
8. AuthService calls UserRepository.findByEmail() to fetch user.
9. UserRepository executes database query and returns the user object.
10. AuthService validates OTP and generates resetToken via UserRepository.save().
11. UserRepository saves the updated user with resetToken and clears OTP.
12. System returns success response with resetToken.
13. Client sends POST /api/v1/auth/reset-password with resetToken and new password.
14. AuthService calls UserRepository.findByResetToken() to fetch user.
15. UserRepository executes database query and returns the user object.
16. AuthService validates resetToken and updates password via UserRepository.save().
17. UserRepository saves the updated user with new password hash and clears resetToken.
18. EmailService sends password change notification.
19. System returns success response.
20. Client sends POST /api/v1/auth/login with old password.
21. AuthController extracts client metadata and calls AuthService.login().
22. AuthService calls UserRepository.findByEmail() to fetch the user record.
23. UserRepository executes database query and returns the user object.
24. AuthService fails password verification (old password no longer works).
25. System returns the authentication error response.
26. Client sends POST /api/v1/auth/login with new password.
27. AuthController extracts client metadata and calls AuthService.login().
28. AuthService calls UserRepository.findByEmail() to fetch the user record.
29. UserRepository executes database query and returns the user object.
30. AuthService validates credentials and creates session via UserSessionRepository.save().
31. UserSessionRepository saves the new session with generated tokens.
32. AuthService generates tokens.
33. System returns the authentication response.

**Expected Results:** 
- Old password login: HTTP 400 BAD REQUEST with INVALID_CREDENTIALS
- New password login: HTTP 200 OK with tokens

---

### Test Case ID: TC_AUTH_FP_012
**Test Case Description:** OTP valid for exactly 10 minutes (BR-54) → expires after 10 minutes

**Pre-conditions:**
ACTIVE LEARNER inserted via userRepository.save():
- email = learner@test.com
- password = passwordEncoder.encode("ValidPass@1")
- role = LEARNER
- status = ACTIVE
- isDeleted = false

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/forgot-password with valid email.
2. AuthController calls AuthService.requestPasswordReset().
3. AuthService generates 6-digit OTP and sets expiry via UserRepository.save().
4. UserRepository saves the updated user with OTP and expiry time (~10 minutes).
5. EmailService sends OTP to user.
6. System returns success response.
7. Client sends POST /api/v1/auth/verify-otp with valid OTP within 10 minutes.
8. AuthService calls UserRepository.findByEmail() to fetch user.
9. UserRepository executes database query and returns the user object.
10. AuthService validates OTP and generates resetToken via UserRepository.save().
11. UserRepository saves the updated user with resetToken and clears OTP.
12. System returns success response with resetToken.
13. Client waits until OTP expires (after 10 minutes).
14. Client sends POST /api/v1/auth/verify-otp with same OTP (now expired).
15. AuthService calls UserRepository.findByEmail() to fetch user.
16. UserRepository executes database query and returns the user object.
17. AuthService validates OTP and detects expiration.
18. System returns the OTP expired error response.

**Expected Results:**
- Within 10 minutes: HTTP 200 OK with resetToken
- After 10 minutes: HTTP 400 BAD REQUEST with OTP_EXPIRED

---

## Section 3 — Change Password Tests

### Test Case ID: TC_AUTH_CP_001
**Test Case Description:** Change password succeeds, old password no longer works

**Pre-conditions:**
ACTIVE LEARNER inserted via userRepository.save():
- email = learner@test.com
- password = passwordEncoder.encode("ValidPass@1")
- role = LEARNER
- status = ACTIVE
- isDeleted = false

A login session exists with:
- accessToken
- refreshToken
- revokedAt = null (session active)

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/change-password with valid current password and new password.
2. AuthController validates token and calls AuthService.changePassword().
3. AuthService calls UserRepository.findByEmail() to fetch user.
4. UserRepository executes database query and returns the user object.
5. AuthService validates current password and updates to new password via UserRepository.save().
6. UserRepository saves the updated user with new password hash.
7. AuthService keeps current session active (logoutOtherDevices=false).
8. EmailService sends password change notification.
9. System returns success response.
10. Client sends POST /api/v1/auth/login with old password.
11. AuthController extracts client metadata and calls AuthService.login().
12. AuthService calls UserRepository.findByEmail() to fetch the user record.
13. UserRepository executes database query and returns the user object.
14. AuthService fails password verification (old password no longer works).
15. System returns the authentication error response.

**Expected Results:** HTTP 200 OK for password change
Old password login: HTTP 400 BAD REQUEST with INVALID_CREDENTIALS
Database: password updated with new hash
Email notification sent
Current session remains active

---

### Test Case ID: TC_AUTH_CP_002
**Test Case Description:** Password change invalidates ALL sessions (BR-61, BR-63)

**Pre-conditions:**
ACTIVE LEARNER inserted via userRepository.save():
- email = learner@test.com
- password = passwordEncoder.encode("ValidPass@1")
- role = LEARNER
- status = ACTIVE
- isDeleted = false

Two login sessions exist with:
- Session 1: accessToken and refreshToken from /api/v1/auth/login, revokedAt = null
- Session 2: accessToken and refreshToken from /api/v1/auth/login, revokedAt = null

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/change-password with logoutOtherDevices=true.
2. AuthController validates token and calls AuthService.changePassword().
3. AuthService calls UserRepository.findByEmail() to fetch user.
4. UserRepository executes database query and returns the user object.
5. AuthService validates current password and updates to new password via UserRepository.save().
6. UserRepository saves the updated user with new password hash.
7. AuthService calls UserSessionRepository.findAllByUserId() to get all user sessions.
8. UserSessionRepository executes database query and returns all user sessions.
9. AuthService revokes ALL sessions via UserSessionRepository.save().
10. UserSessionRepository saves all sessions with revokedAt timestamps.
11. EmailService sends password change notification.
12. System returns success response.
13. Verification: Current token becomes invalid (UNAUTHORIZED).
14. Verification: All other sessions become invalid (SESSION_EXPIRED).

**Expected Results:** HTTP 200 OK for password change
Subsequent requests with old tokens:
- Current access token: UNAUTHORIZED
- Other session refresh tokens: SESSION_EXPIRED
Database: 0 active sessions remaining

---

### Test Case ID: TC_AUTH_CP_003
**Test Case Description:** Wrong current password → INVALID_CREDENTIALS

**Pre-conditions:**
ACTIVE LEARNER inserted via userRepository.save():
- email = learner@test.com
- password = passwordEncoder.encode("ValidPass@1")
- role = LEARNER
- status = ACTIVE
- isDeleted = false

A login session exists with:
- accessToken generated from /api/v1/auth/login
- refreshToken generated from /api/v1/auth/login
- revokedAt = null (session active)

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/change-password with wrong current password.
2. AuthController validates token and calls AuthService.changePassword().
3. AuthService calls UserRepository.findByEmail() to fetch user.
4. AuthService validates current password and detects mismatch.
5. System returns authentication error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "INVALID_CREDENTIALS"

---

### Test Case ID: TC_AUTH_CP_004
**Test Case Description:** New password confirmation mismatch → PASSWORD_MISMATCH

**Pre-conditions:**
ACTIVE LEARNER inserted via userRepository.save():
- email = learner@test.com
- password = passwordEncoder.encode("ValidPass@1")
- role = LEARNER
- status = ACTIVE
- isDeleted = false

A login session exists with:
- accessToken generated from /api/v1/auth/login
- refreshToken generated from /api/v1/auth/login
- revokedAt = null (session active)

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/change-password with mismatched new password confirmation.
2. AuthController validates token and calls AuthService.changePassword().
3. AuthService validates password confirmation and detects mismatch.
4. System returns password mismatch error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "PASSWORD_MISMATCH"

---

### Test Case ID: TC_AUTH_CP_005
**Test Case Description:** New password same as current password → SAME_PASSWORD

**Pre-conditions:**
ACTIVE LEARNER inserted via userRepository.save():
- email = learner@test.com
- password = passwordEncoder.encode("ValidPass@1")
- role = LEARNER
- status = ACTIVE
- isDeleted = false

A login session exists with:
- accessToken generated from /api/v1/auth/login
- refreshToken generated from /api/v1/auth/login
- revokedAt = null (session active)

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/change-password with same password as current.
2. AuthController validates token and calls AuthService.changePassword().
3. AuthService calls UserRepository.findByEmail() to fetch user.
4. AuthService detects new password is same as current.
5. System returns same password error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "SAME_PASSWORD"

---

### Test Case ID: TC_AUTH_CP_006
**Test Case Description:** Weak new password → 400 validation error

**Pre-conditions:**
ACTIVE LEARNER inserted via userRepository.save():
- email = learner@test.com
- password = passwordEncoder.encode("ValidPass@1")
- role = LEARNER
- status = ACTIVE
- isDeleted = false

A login session exists with:
- accessToken generated from /api/v1/auth/login
- refreshToken generated from /api/v1/auth/login
- revokedAt = null (session active)

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/change-password with weak new password.
2. AuthController validates request body.
3. AuthController detects that password does not meet policy requirements.
4. System returns validation error response before reaching AuthService.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- Validation error message

---

### Test Case ID: TC_AUTH_CP_007
**Test Case Description:** No token → 401 Unauthorized

**Pre-conditions:**
None (no Authorization header).

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/change-password with no Authorization header.
2. AuthFilter detects that no token is present.
3. AuthFilter rejects the request as unauthenticated.
4. System returns the unauthorized error response.

**Expected Results:** HTTP 401 UNAUTHORIZED
Response JSON:
- success = false
- code = "UNAUTHORIZED"

---

### Test Case ID: TC_AUTH_CP_008
**Test Case Description:** Expired access token → 401 Unauthorized

**Pre-conditions:**
ACTIVE LEARNER inserted via userRepository.save():
- email = learner@test.com
- password = passwordEncoder.encode("ValidPass@1")
- role = LEARNER
- status = ACTIVE
- isDeleted = false

**Test Case Procedure:**
1. Client generates expired JWT access token using JWT service.
2. Client sends POST /api/v1/auth/change-password with expired Bearer token.
3. AuthFilter parses the token and detects it is expired.
4. System returns the unauthorized error response.

**Expected Results:** HTTP 401 UNAUTHORIZED
Response JSON:
- success = false
- code = "UNAUTHORIZED"

---

## Section 4 — Logout Tests

### Test Case ID: TC_AUTH_LO_001
**Test Case Description:** Logout revokes session and invalidates access token

**Pre-conditions:**
ACTIVE LEARNER inserted via userRepository.save():
- email = learner@test.com
- password = passwordEncoder.encode("ValidPass@1")
- role = LEARNER
- status = ACTIVE
- isDeleted = false

A login session exists with:
- accessToken generated from /api/v1/auth/login
- refreshToken generated from /api/v1/auth/login
- revokedAt = null (session active)

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/logout with valid Bearer token and refreshToken.
2. AuthFilter parses token and resolves user identity.
3. AuthController calls AuthService.logout().
4. AuthService calls UserSessionRepository.findByRefreshToken() to fetch session.
5. UserSessionRepository executes database query and returns the session object.
6. AuthService revokes the session via UserSessionRepository.save().
7. UserSessionRepository saves the updated session with revokedAt timestamp.
8. System returns success response.
9. Verification: Access token becomes invalid (UNAUTHORIZED).
10. Verification: Refresh token becomes invalid (SESSION_EXPIRED).

**Expected Results:** HTTP 200 OK for logout
Subsequent requests:
- Access token: UNAUTHORIZED
- Refresh token: SESSION_EXPIRED
Database: 0 active sessions

---

### Test Case ID: TC_AUTH_LO_001b
**Test Case Description:** Logout does not affect other sessions

**Pre-conditions:**
ACTIVE LEARNER inserted via userRepository.save():
- email = learner@test.com
- password = passwordEncoder.encode("ValidPass@1")
- role = LEARNER
- status = ACTIVE
- isDeleted = false

Two login sessions exist with:
- Session 1: accessToken and refreshToken from /api/v1/auth/login, revokedAt = null
- Session 2: accessToken and refreshToken from /api/v1/auth/login, revokedAt = null

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/logout with session 1 token and refreshToken.
2. AuthFilter parses token and resolves user identity.
3. AuthController calls AuthService.logout().
4. AuthService calls UserSessionRepository.findByRefreshToken() to fetch session 1.
5. UserSessionRepository executes database query and returns the session object.
6. AuthService revokes only session 1 via UserSessionRepository.save() (logoutAll=false).
7. UserSessionRepository saves the updated session 1 with revokedAt timestamp.
8. System returns success response.
9. Verification: Session 1 tokens become invalid.
10. Verification: Session 2 remains active and functional.

**Expected Results:** HTTP 200 OK for logout
Session 1 tokens: invalid (UNAUTHORIZED/SESSION_EXPIRED)
Session 2 tokens: valid and functional
Database: 1 active session remaining

---

## Test Execution Commands

### Run All Auth Tests
```bash
mvn -pl start -am test -Dtest=AuthControllerIntegrationTest
```

### Run Specific Test Sections
```bash
# Login tests only
mvn -pl start -am test -Dtest=AuthControllerIntegrationTest#LoginTests

# Forgot password tests only  
mvn -pl start -am test -Dtest=AuthControllerIntegrationTest#ForgotPasswordTests

# Change password tests only
mvn -pl start -am test -Dtest=AuthControllerIntegrationTest#ChangePasswordTests

# Logout tests only
mvn -pl start -am test -Dtest=AuthControllerIntegrationTest#LogoutTests
```

### Run Individual Test Cases
```bash
# Example: Run TC_AUTH_LG_001
mvn -pl start -am test -Dtest=AuthControllerIntegrationTest#tc_lg_001_loginSuccessfully
```

## Business Rules References

- **BR-48**: Email whitespace trimming during login
- **BR-49**: Email length validation (max 255 characters)
- **BR-61**: Invalidate current session immediately after password change
- **BR-63**: Revoke all other active sessions upon password change

## Known Gaps & Limitations

1. **Token Rotation**: Refresh token reuse is currently allowed (TC_AUTH_LG_010)
2. **HTTP Status Mapping**: All BusinessException currently return HTTP 400 instead of appropriate status codes
3. **Session Management**: Some edge cases around concurrent session handling may need additional testing

## Test Data Cleanup

Tests automatically clean up after execution:
- `@AfterEach` method deletes all sessions and users via UserSessionRepository.deleteAll() and UserRepository.deleteAll()
- Each test runs with isolated data
- No test pollution between test cases

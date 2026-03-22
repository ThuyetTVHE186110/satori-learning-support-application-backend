# Account Management Integration Test Script

## Overview
This document contains detailed test scripts for all account management API endpoints in Satori Learning Support Application backend.

## Test Environment Setup

### Pre-conditions
- Test DB running via `application-test.properties`
- PostgreSQL `satori_test` database created and initialized
- Docker environment up and running
- EmailService mocked to prevent actual email sending

---

## Section 1 — View Account List Tests (GET /api/v1/users)

### Test Case ID: TC_AM_VAL_001
**Test Case Description:** Admin view account list success with default pagination

**Pre-conditions:**
- Admin accessToken is available (from prior login)
- Accounts inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- manager@test.com (role=MANAGER, status=ACTIVE)
- teacher@test.com (role=TEACHER, status=ACTIVE)
- learner@test.com (role=LEARNER, status=ACTIVE)
- deleted.learner@test.com (role=LEARNER, status=DELETED, deletedAt set)

**Test Case Procedure:**
1. Client sends GET /api/v1/users with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves ADMIN user identity.
3. UserController calls UserService.getUsers() with ADMIN scope.
4. UserService retrieves account list including all roles and deleted accounts via UserRepository.findAll().
5. UserRepository executes database query and returns paginated account list.
6. System returns the paginated account list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.totalElements ≥ 4 (excluding deleted accounts from active count)
- data.content contains accounts with roles: "MANAGER", "TEACHER", "LEARNER", "ADMIN"
- Deleted account visible with status = "DELETED"
- data.pageNumber = 0
- data.pageSize = 20 (default)
- data.first = true
- data.last = depends on total count

---

### Test Case ID: TC_AM_VAL_002
**Test Case Description:** Admin filter list by role and status

**Pre-conditions:**
- Admin accessToken is available (from prior login)
- Accounts inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- manager1@test.com (role=MANAGER, status=ACTIVE)
- manager2@test.com (role=MANAGER, status=SUSPENDED)
- teacher1@test.com (role=TEACHER, status=ACTIVE)
- teacher2@test.com (role=TEACHER, status=INACTIVE)

**Test Case Procedure:**
1. Client sends GET /api/v1/users?role=MANAGER&status=ACTIVE with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves ADMIN user identity.
3. UserController calls UserService.getUsers() with role and status filters.
4. UserService retrieves filtered account list via UserRepository.findByRoleAndStatus().
5. UserRepository executes database query with filters and returns paginated results.
6. System returns the filtered paginated account list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.totalElements = 1
- All items in data.content have role = "MANAGER" and status = "ACTIVE"
- data.content does NOT contain accounts with role = "TEACHER" or "LEARNER"
- data.pageNumber = 0
- data.pageSize = 20 (default)
- data.first = true
- data.last = true (only 1 item)

---

### Test Case ID: TC_AM_VAL_003
**Test Case Description:** Admin search by keyword

**Pre-conditions:**
- Admin accessToken is available (from prior login)
- Accounts inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- john.doe@test.com (role=LEARNER, status=ACTIVE, firstName="John", lastName="Doe")
- jane.smith@test.com (role=TEACHER, status=ACTIVE, firstName="Jane", lastName="Smith")
- other.user@test.com (role=LEARNER, status=ACTIVE)

**Test Case Procedure:**
1. Client sends GET /api/v1/users?keyword=john with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves ADMIN user identity.
3. UserController calls UserService.getUsers() with keyword filter.
4. UserService searches accounts by keyword in firstName, lastName, or email via UserRepository.searchByKeyword().
5. UserRepository executes database query with keyword search and returns matching results.
6. System returns the filtered paginated account list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.totalElements = 1
- data.content contains account with email = "john.doe@test.com"
- data.content does NOT contain account with email = "jane.smith@test.com"
- data.content does NOT contain account with email = "other.user@test.com"
- data.pageNumber = 0
- data.pageSize = 20 (default)
- data.first = true
- data.last = true (only 1 item)

---

### Test Case ID: TC_AM_VAL_004
**Test Case Description:** Manager cannot view account list

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Accounts inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- manager@test.com (role=MANAGER, status=ACTIVE)
- learner@test.com (role=LEARNER, status=ACTIVE)

**Test Case Procedure:**
1. Client sends GET /api/v1/users with Authorization: Bearer {managerToken}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. UserController calls UserService.getUsers() with MANAGER scope.
4. UserService detects insufficient permissions and throws BusinessException with ACCESS_DENIED.
5. System returns the access denied error response.

**Expected Results:** HTTP 403 FORBIDDEN
Response JSON:
- success = false
- code = "ACCESS_DENIED"
- message = non-null error message
- timestamp = non-null timestamp
- path = "/api/v1/users"

---

## Section 2 — Create Account Tests (POST /api/v1/users)

### Test Case ID: TC_AM_CREATE_001
**Test Case Description:** Admin create an account success with email service call success, status of new account is Active

**Pre-conditions:**
- Admin accessToken is available (from prior login)
- Accounts inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)

**Test Case Procedure:**
1. Client sends POST /api/v1/users with Authorization: Bearer {token} and CreateUserRequest:
   - email = newaccount@test.com
   - firstName = New
   - lastName = Account
   - role = LEARNER
2. AuthFilter validates token and resolves ADMIN user identity.
3. UserController calls UserService.createUser().
4. UserService checks for existing email via UserRepository.findByEmail().
5. UserRepository executes database query and returns empty (no existing account).
6. UserService generates random password and encodes it via PasswordEncoder.encode().
7. UserService creates new User entity and saves via UserRepository.save().
8. UserRepository saves the new account with status=ACTIVE and generated password hash.
9. EmailService.sendWelcomeEmailWithCredentials() is called once.
10. System returns the created account response.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.email = "newaccount@test.com"
- data.firstName = "New"
- data.lastName = "Account"
- data.role = "LEARNER"
- data.status = "ACTIVE"
- data.id = non-empty UUID
- data.createdAt = non-null timestamp
- data.updatedAt = non-null timestamp
- data.passwordHash = NOT included in response (security)
- data.deletedAt = null
- data.deletedBy = null
Database state: account.status = ACTIVE, account.deletedAt = null, account.passwordHash non-empty
Email verification: sendWelcomeEmailWithCredentials called exactly once

---

### Test Case ID: TC_AM_CREATE_002
**Test Case Description:** Deleted email detected

**Pre-conditions:**
- Admin accessToken is available (from prior login)
- Accounts inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- deleted@test.com (role=LEARNER, status=DELETED, deletedAt set, deletedBy set)

**Test Case Procedure:**
1. Client sends POST /api/v1/users with Authorization: Bearer {token} and CreateUserRequest:
   - email = deleted@test.com (belongs to deleted account)
   - firstName = New
   - lastName = Account
   - role = LEARNER
2. AuthFilter validates token and resolves ADMIN user identity.
3. UserController calls UserService.createUser().
4. UserService checks for existing email via UserRepository.findByEmail().
5. UserRepository executes database query and returns deleted account.
6. UserService detects deleted account conflict and throws BusinessException with DELETED_USER_EXISTS.
7. System returns the deleted account exists error response with account details.

**Expected Results:** HTTP 409 CONFLICT
Response JSON:
- success = false
- code = "DELETED_USER_EXISTS"
- data.userId = non-empty UUID
- data.email = "deleted@test.com"
- data.status = "DELETED"
- data.deletedAt = non-null timestamp
- data.deletedBy = non-null UUID
- message = non-null error message
- timestamp = non-null timestamp
- path = "/api/v1/users"

---

### Test Case ID: TC_AM_CREATE_003
**Test Case Description:** Email already exist

**Pre-conditions:**
- Admin accessToken is available (from prior login)
- Accounts inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- exists@test.com (role=LEARNER, status=ACTIVE)

**Test Case Procedure:**
1. Client sends POST /api/v1/users with Authorization: Bearer {token} and CreateUserRequest:
   - email = exists@test.com (duplicate)
   - firstName = New
   - lastName = Account
   - role = LEARNER
2. AuthFilter validates token and resolves ADMIN user identity.
3. UserController calls UserService.createUser().
4. UserService checks for existing email via UserRepository.findByEmail().
5. UserRepository executes database query and returns existing active account.
6. UserService detects email conflict and throws BusinessException with EMAIL_EXISTS.
7. System returns the email exists error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "EMAIL_EXISTS"
- message = non-null error message
- data = null or error details
- timestamp = non-null timestamp
- path = "/api/v1/users"

---

### Test Case ID: TC_AM_CREATE_004
**Test Case Description:** Validation error

**Pre-conditions:**
- Admin accessToken is available (from prior login)
- Accounts inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)

**Test Case Procedure:**
1. Client sends POST /api/v1/users with Authorization: Bearer {token} and invalid JSON:
   - email = "" (blank)
   - firstName = "" (blank)
   - lastName = "" (blank)
   - role = INVALID_ROLE
2. Request validation fails at controller level due to multiple validation errors.
3. System returns the validation error response before reaching UserService.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "VALIDATION_ERROR"
- data.email = non-empty validation error message
- data.firstName = non-empty validation error message
- data.lastName = non-empty validation error message
- data.role = non-empty validation error message
- message = non-null error message
- timestamp = non-null timestamp
- path = "/api/v1/users"

---

### Test Case ID: TC_AM_CREATE_005
**Test Case Description:** Email service failure

**Pre-conditions:**
- Admin accessToken is available (from prior login)
- Accounts inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)

**Test Case Procedure:**
1. Mock EmailService to throw exception for sendWelcomeEmailWithCredentials().
2. Client sends POST /api/v1/users with Authorization: Bearer {token} and CreateUserRequest:
   - email = newaccount@test.com
   - firstName = New
   - lastName = Account
   - role = LEARNER
3. AuthFilter validates token and resolves ADMIN user identity.
4. UserController calls UserService.createUser().
5. UserService checks for existing email via UserRepository.findByEmail().
6. UserRepository executes database query and returns empty (no existing account).
7. UserService generates random password and encodes it via PasswordEncoder.encode().
8. UserService creates new User entity and saves via UserRepository.save().
9. UserRepository saves the new account with status=ACTIVE and generated password hash.
10. EmailService.sendWelcomeEmailWithCredentials() throws exception.
11. System returns the email service failure error response.

**Expected Results:** HTTP 500 INTERNAL SERVER ERROR
Response JSON:
- success = false
- code = "EMAIL_SERVICE_FAILURE"
- message = non-null error message
- timestamp = non-null timestamp
- path = "/api/v1/users"
Database state: account is created successfully despite email failure

---

### Test Case ID: TC_AM_CREATE_006
**Test Case Description:** Manager cannot create new account

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Accounts inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- manager@test.com (role=MANAGER, status=ACTIVE)

**Test Case Procedure:**
1. Client sends POST /api/v1/users with Authorization: Bearer {managerToken} and CreateUserRequest:
   - email = newaccount@test.com
   - firstName = New
   - lastName = Account
   - role = LEARNER
2. AuthFilter validates token and resolves MANAGER user identity.
3. UserController calls UserService.createUser().
4. UserService detects insufficient permissions and throws BusinessException with ACCESS_DENIED.
5. System returns the access denied error response.

**Expected Results:** HTTP 403 FORBIDDEN
Response JSON:
- success = false
- code = "ACCESS_DENIED"
- message = non-null error message
- timestamp = non-null timestamp
- path = "/api/v1/users"

---

## Section 3 — View Account Information Tests (GET /api/v1/users/{id}/details)

### Test Case ID: TC_AM_VIEW_001
**Test Case Description:** Admin view success, display account information

**Pre-conditions:**
- Admin accessToken is available (from prior login)
- Accounts inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- learner@test.com (role=LEARNER, status=ACTIVE)

**Test Case Procedure:**
1. Client sends GET /api/v1/users/{learnerId}/details with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves ADMIN user identity.
3. UserController calls UserService.getUserDetails().
4. UserService retrieves account by ID via UserRepository.findById().
5. UserRepository executes database query and returns the account object.
6. UserService retrieves account's course enrollments via CourseEnrollmentRepository.findByUserId().
7. UserService retrieves account's class enrollments via ClassEnrollmentRepository.findByUserId().
8. System returns the account details response with profile and enrollment arrays.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.userInfo.role = "LEARNER"
- data.userInfo.email = "learner@test.com"
- data.userInfo.status = "ACTIVE"
- data.userInfo.firstName = non-null string
- data.userInfo.lastName = non-null string
- data.courseEnrollments = array (may be empty)
- data.classEnrollments = array (may be empty)

---

### Test Case ID: TC_AM_VIEW_002
**Test Case Description:** Manager cannot view account information

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Accounts inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- manager@test.com (role=MANAGER, status=ACTIVE)
- learner@test.com (role=LEARNER, status=ACTIVE)

**Test Case Procedure:**
1. Client sends GET /api/v1/users/{learnerId}/details with Authorization: Bearer {managerToken}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. UserController calls UserService.getUserDetails().
4. UserService detects insufficient permissions and throws BusinessException with ACCESS_DENIED.
5. System returns the access denied error response.

**Expected Results:** HTTP 403 FORBIDDEN
Response JSON:
- success = false
- code = "ACCESS_DENIED"
- message = non-null error message
- timestamp = non-null timestamp
- path = "/api/v1/users/{id}/details"

---

## Section 4 — Update Account Information Tests (PUT /api/v1/users/{id})

### Test Case ID: TC_AM_UPDATE_001
**Test Case Description:** Admin update success

**Pre-conditions:**
- Admin accessToken is available (from prior login)
- Accounts inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- learner@test.com (role=LEARNER, status=ACTIVE, firstName="Old", lastName="Name")

**Test Case Procedure:**
1. Client sends PUT /api/v1/users/{learnerId} with Authorization: Bearer {token} and UpdateUserRequest:
   - firstName = Updated
   - email = learner@test.com
2. AuthFilter validates token and resolves ADMIN user identity.
3. UserController calls UserService.updateUser().
4. UserService retrieves account by ID via UserRepository.findById().
5. UserRepository executes database query and returns the account object.
6. UserService updates account fields and saves via UserRepository.save().
7. UserRepository saves the updated account with new firstName and updated timestamp.
8. System returns the updated account response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.firstName = "Updated"
- data.lastName = "Name"
- data.email = "learner@test.com"
- data.role = "LEARNER"
- data.status = "ACTIVE"
- data.updatedAt = non-null timestamp (newer than before)
Database state: account.firstName = "Updated", account.updatedAt > beforeUpdate

---

### Test Case ID: TC_AM_UPDATE_002
**Test Case Description:** Validation error

**Pre-conditions:**
- Admin accessToken is available (from prior login)
- Accounts inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- learner@test.com (role=LEARNER, status=ACTIVE)

**Test Case Procedure:**
1. Client sends PUT /api/v1/users/{learnerId} with Authorization: Bearer {token} and invalid UpdateUserRequest:
   - firstName = "" (blank)
   - email = learner@test.com
2. Request validation fails at controller level due to blank firstName.
3. System returns the validation error response before reaching UserService.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "VALIDATION_ERROR"
- data.firstName = non-empty validation error message
- message = non-null error message
- timestamp = non-null timestamp
- path = "/api/v1/users/{id}"

---

### Test Case ID: TC_AM_UPDATE_003
**Test Case Description:** Admin cannot change account status to deleted

**Pre-conditions:**
- Admin accessToken is available (from prior login)
- Accounts inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- learner@test.com (role=LEARNER, status=ACTIVE)

**Test Case Procedure:**
1. Client sends PUT /api/v1/users/{learnerId} with Authorization: Bearer {token} and UpdateUserRequest:
   - status = DELETED (invalid for update)
   - email = learner@test.com
2. AuthFilter validates token and resolves ADMIN user identity.
3. UserController calls UserService.updateUser().
4. UserService retrieves account by ID via UserRepository.findById().
5. UserRepository executes database query and returns the account object.
6. UserService detects invalid status update and throws BusinessException with INVALID_STATUS.
7. System returns the invalid status error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "INVALID_STATUS"
- message = non-null error message
- timestamp = non-null timestamp
- path = "/api/v1/users/{id}"
Database state: account.status = ACTIVE (unchanged)

---

### Test Case ID: TC_AM_UPDATE_004
**Test Case Description:** Manager cannot update account

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Accounts inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- manager@test.com (role=MANAGER, status=ACTIVE)
- learner@test.com (role=LEARNER, status=ACTIVE)

**Test Case Procedure:**
1. Client sends PUT /api/v1/users/{learnerId} with Authorization: Bearer {managerToken} and UpdateUserRequest:
   - firstName = Updated
   - email = learner@test.com
2. AuthFilter validates token and resolves MANAGER user identity.
3. UserController calls UserService.updateUser().
4. UserService detects insufficient permissions and throws BusinessException with ACCESS_DENIED.
5. System returns the access denied error response.

**Expected Results:** HTTP 403 FORBIDDEN
Response JSON:
- success = false
- code = "ACCESS_DENIED"
- message = non-null error message
- timestamp = non-null timestamp
- path = "/api/v1/users/{id}"

---

### Test Case ID: TC_AM_UPDATE_005
**Test Case Description:** Admin cannot update deleted account

**Pre-conditions:**
- Admin accessToken is available (from prior login)
- Accounts inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- deleted@test.com (role=LEARNER, status=DELETED, deletedAt set, deletedBy set)

**Test Case Procedure:**
1. Client sends PUT /api/v1/users/{deletedAccountId} with Authorization: Bearer {token} and UpdateUserRequest:
   - firstName = Updated
   - email = deleted@test.com
2. AuthFilter validates token and resolves ADMIN user identity.
3. UserController calls UserService.updateUser().
4. UserService retrieves account by ID via UserRepository.findById().
5. UserRepository executes database query and returns the deleted account object.
6. UserService detects account is deleted and throws BusinessException with NOT_FOUND.
7. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND
Response JSON:
- success = false
- code = "NOT_FOUND"
- message = non-null error message
- timestamp = non-null timestamp
- path = "/api/v1/users/{id}"

---

### Test Case ID: TC_AM_UPDATE_006
**Test Case Description:** Email is read-only, not be updated

**Pre-conditions:**
- Admin accessToken is available (from prior login)
- Accounts inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- learner@test.com (role=LEARNER, status=ACTIVE)

**Test Case Procedure:**
1. Client sends PUT /api/v1/users/{learnerId} with Authorization: Bearer {token} and UpdateUserRequest:
   - firstName = Updated
   - email = different@test.com (attempting to change email)
2. AuthFilter validates token and resolves ADMIN user identity.
3. UserController calls UserService.updateUser().
4. UserService retrieves account by ID via UserRepository.findById().
5. UserRepository executes database query and returns the account object.
6. UserService detects email change attempt and ignores email field (read-only).
7. UserService updates only allowed fields and saves via UserRepository.save().
8. UserRepository saves the updated account with new firstName but original email.
9. System returns the updated account response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.firstName = "Updated"
- data.email = "learner@test.com" (unchanged)
- data.lastName = original value
- data.role = "LEARNER"
- data.status = "ACTIVE"
Database state: account.firstName = "Updated", account.email = "learner@test.com" (unchanged)

---

### Test Case ID: TC_AM_UPDATE_007
**Test Case Description:** First Name, Last Name, Role is not blank and not null

**Pre-conditions:**
- Admin accessToken is available (from prior login)
- Accounts inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- learner@test.com (role=LEARNER, status=ACTIVE)

**Test Case Procedure:**
1. Client sends PUT /api/v1/users/{learnerId} with Authorization: Bearer {token} and valid UpdateUserRequest:
   - firstName = "ValidFirstName"
   - lastName = "ValidLastName"
   - role = "TEACHER"
   - email = learner@test.com
2. AuthFilter validates token and resolves ADMIN user identity.
3. UserController calls UserService.updateUser().
4. UserService retrieves account by ID via UserRepository.findById().
5. UserRepository executes database query and returns the account object.
6. UserService validates all required fields are present and valid.
7. UserService updates account fields and saves via UserRepository.save().
8. UserRepository saves the updated account with new values.
9. System returns the updated account response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.firstName = "ValidFirstName"
- data.lastName = "ValidLastName"
- data.role = "TEACHER"
- data.email = "learner@test.com"
- data.status = "ACTIVE"
- data.updatedAt = non-null timestamp (newer than before)
Database state: account.firstName = "ValidFirstName", account.lastName = "ValidLastName", account.role = "TEACHER"

---

## Section 5 — Delete Account Tests (DELETE /api/v1/users/{id})

### Test Case ID: TC_AM_DELETE_001
**Test Case Description:** Admin delete account success with soft-delete

**Pre-conditions:**
- Admin accessToken is available (from prior login)
- Accounts inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- learner@test.com (role=LEARNER, status=ACTIVE)

**Test Case Procedure:**
1. Client sends DELETE /api/v1/users/{learnerId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves ADMIN user identity.
3. UserController calls UserService.deleteUser().
4. UserService retrieves account by ID via UserRepository.findById().
5. UserRepository executes database query and returns the account object.
6. UserService performs soft delete: sets deletedAt, deletedBy, and status=DELETED via UserRepository.save().
7. UserRepository saves the updated account with soft delete fields.
8. UserService terminates all active sessions for the deleted account via UserSessionRepository.deleteByUserId().
9. UserSessionRepository deletes all sessions for the deleted account.
10. System returns the delete success response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- message = "Xóa người dùng thành công"
- data = null (no data field)
Database state: account.deletedAt ≠ null, account.deletedBy = adminId, account.status = DELETED
Session verification: All sessions for deleted account are terminated

---

### Test Case ID: TC_AM_DELETE_002
**Test Case Description:** Cannot delete deleted account

**Pre-conditions:**
- Admin accessToken is available (from prior login)
- Accounts inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- already.deleted@test.com (role=LEARNER, status=DELETED, deletedAt set, deletedBy set)

**Test Case Procedure:**
1. Client sends DELETE /api/v1/users/{deletedAccountId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves ADMIN user identity.
3. UserController calls UserService.deleteUser().
4. UserService retrieves account by ID via UserRepository.findById().
5. UserRepository executes database query and returns the deleted account object.
6. UserService detects account is already deleted and throws BusinessException with NOT_FOUND.
7. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND
Response JSON:
- success = false
- code = "NOT_FOUND"
- message = non-null error message
- timestamp = non-null timestamp
- path = "/api/v1/users/{id}"

---

### Test Case ID: TC_AM_DELETE_003
**Test Case Description:** Deletion shall immediately terminate all active sessions

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Admin accessToken is available (from prior login)
- Accounts inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- learner@test.com (role=LEARNER, status=ACTIVE)

**Test Case Procedure:**
1. Client sends DELETE /api/v1/users/{learnerId} with Authorization: Bearer {adminToken}.
2. AuthFilter validates token and resolves ADMIN user identity.
3. UserController calls UserService.deleteUser().
4. UserService retrieves account by ID via UserRepository.findById().
5. UserRepository executes database query and returns the account object.
6. UserService performs soft delete: sets deletedAt, deletedBy, and status=DELETED via UserRepository.save().
7. UserRepository saves the updated account with soft delete fields.
8. UserService terminates all active sessions for the deleted account via UserSessionRepository.deleteByUserId().
9. UserSessionRepository deletes all sessions for the deleted account.
10. System returns the delete success response.
11. Client sends POST /api/v1/auth/refresh with learner's refresh token.
12. AuthService validates refresh token and detects session no longer exists.
13. System returns the session expired error response.

**Expected Results:** 
- Delete operation: HTTP 200 OK with success message
- Subsequent refresh attempt: HTTP 400 BAD REQUEST with SESSION_EXPIRED
Session verification: All sessions for deleted account are terminated immediately

---

### Test Case ID: TC_AM_DELETE_004
**Test Case Description:** Manager cannot delete account

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Accounts inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- manager@test.com (role=MANAGER, status=ACTIVE)
- learner@test.com (role=LEARNER, status=ACTIVE)

**Test Case Procedure:**
1. Client sends DELETE /api/v1/users/{learnerId} with Authorization: Bearer {managerToken}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. UserController calls UserService.deleteUser().
4. UserService detects insufficient permissions and throws BusinessException with ACCESS_DENIED.
5. System returns the access denied error response.

**Expected Results:** HTTP 403 FORBIDDEN
Response JSON:
- success = false
- code = "ACCESS_DENIED"
- message = non-null error message
- timestamp = non-null timestamp
- path = "/api/v1/users/{id}"

---

## Section 6 — Restore Account Tests (POST /api/v1/users/restore)

### Test Case ID: TC_AM_RESTORE_001
**Test Case Description:** Admin restore account success with account status -> active, original account information preserved, email service call success

**Pre-conditions:**
- Admin accessToken is available (from prior login)
- Accounts inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- deleted@test.com (role=LEARNER, status=DELETED, deletedAt set, deletedBy set)

**Test Case Procedure:**
1. Client sends POST /api/v1/users/restore with Authorization: Bearer {token} and RestoreUserRequest:
   - userId = deletedAccountId
   - email = deleted@test.com
   - firstName = Restored
   - lastName = Account
   - role = LEARNER
2. AuthFilter validates token and resolves ADMIN user identity.
3. UserController calls UserService.restoreUser().
4. UserService retrieves account by ID via UserRepository.findById().
5. UserRepository executes database query and returns the deleted account object.
6. UserService generates new random password and encodes it via PasswordEncoder.encode().
7. UserService clears soft delete fields and updates password via UserRepository.save().
8. UserRepository saves the updated account with status=ACTIVE, deletedAt=null, deletedBy=null, new password hash.
9. EmailService.sendWelcomeEmailWithCredentials() is called once.
10. System returns the restored account response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.status = "ACTIVE"
- data.email = "deleted@test.com"
- data.firstName = "Restored"
- data.lastName = "Account"
- data.role = "LEARNER"
- data.id = non-empty UUID (same as original)
- data.createdAt = original creation timestamp (preserved)
- data.updatedAt = non-null timestamp (newer than before)
- data.passwordHash = NOT included in response (security)
- data.deletedAt = null
- data.deletedBy = null
Database state: account.deletedAt = null, account.deletedBy = null, account.status = ACTIVE, account.passwordHash ≠ oldPasswordHash
Email verification: sendWelcomeEmailWithCredentials called exactly once

---

### Test Case ID: TC_AM_RESTORE_002
**Test Case Description:** Cannot restore non-deleted account

**Pre-conditions:**
- Admin accessToken is available (from prior login)
- Accounts inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- active@test.com (role=LEARNER, status=ACTIVE)

**Test Case Procedure:**
1. Client sends POST /api/v1/users/restore with Authorization: Bearer {token} and RestoreUserRequest:
   - userId = activeAccountId
   - email = active@test.com
   - firstName = Restored
   - lastName = Account
   - role = LEARNER
2. AuthFilter validates token and resolves ADMIN user identity.
3. UserController calls UserService.restoreUser().
4. UserService retrieves account by ID via UserRepository.findById().
5. UserRepository executes database query and returns the active account object.
6. UserService detects account is not deleted and throws BusinessException with INVALID_OPERATION.
7. System returns the invalid operation error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "INVALID_OPERATION"
- message = non-null error message
- timestamp = non-null timestamp
- path = "/api/v1/users/restore"

---

### Test Case ID: TC_AM_RESTORE_003
**Test Case Description:** Manager cannot restore account

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Accounts inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- manager@test.com (role=MANAGER, status=ACTIVE)
- deleted@test.com (role=LEARNER, status=DELETED, deletedAt set, deletedBy set)

**Test Case Procedure:**
1. Client sends POST /api/v1/users/restore with Authorization: Bearer {managerToken} and RestoreUserRequest:
   - userId = deletedAccountId
   - email = deleted@test.com
   - firstName = Restored
   - lastName = Account
   - role = LEARNER
2. AuthFilter validates token and resolves MANAGER user identity.
3. UserController calls UserService.restoreUser().
4. UserService detects insufficient permissions and throws BusinessException with ACCESS_DENIED.
5. System returns the access denied error response.

**Expected Results:** HTTP 403 FORBIDDEN
Response JSON:
- success = false
- code = "ACCESS_DENIED"
- message = non-null error message
- timestamp = non-null timestamp
- path = "/api/v1/users/restore"

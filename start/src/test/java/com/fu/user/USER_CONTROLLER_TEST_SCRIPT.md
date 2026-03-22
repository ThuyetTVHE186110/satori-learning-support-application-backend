# UserController Integration Test Script

## Overview
This document contains detailed test scripts for all user management API endpoints in the Satori Learning Support Application backend.

## Test Environment Setup

### Pre-conditions
- Test DB running via `application-test.properties`
- PostgreSQL `satori_test` database created and initialized
- Docker environment up and running
- EmailService mocked to prevent actual email sending

---

## Section 1 — View User List Tests (GET /api/v1/users)

### Test Case ID: TC_UM_VUL_001
**Test Case Description:** ADMIN views list → sees all roles and deleted

**Pre-conditions:**
Users inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- manager@test.com (role=MANAGER, status=ACTIVE)
- teacher@test.com (role=TEACHER, status=ACTIVE)
- learner@test.com (role=LEARNER, status=ACTIVE)
- deleted.learner@test.com (role=LEARNER, status=DELETED, deletedAt set)

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/login with admin credentials to get Bearer token.
2. AuthController processes login and creates session via UserSessionRepository.save().
3. UserSessionRepository saves the new session with generated tokens.
4. System returns the authentication response with Bearer token.
5. Client sends GET /api/v1/users with Authorization: Bearer {token}.
6. AuthFilter validates token and resolves ADMIN user identity.
7. UserController calls UserService.getUsers() with ADMIN scope.
8. UserService retrieves user list including all roles and deleted users via UserRepository.findAll().
9. UserRepository executes database query and returns paginated user list.
10. System returns the paginated account list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.totalElements ≥ 5
- data.content contains users with roles: "MANAGER", "ADMIN", "TEACHER", "LEARNER"
- Deleted user visible with status = "DELETED"
- data.pageable.pageNumber = 0
- data.pageable.pageSize = 20 (default)
- data.first = true
- data.last = depends on total count

---

### Test Case ID: TC_UM_VUL_002
**Test Case Description:** ADMIN filters role=TEACHER → all items are TEACHER, totalElements=2

**Pre-conditions:**
Users inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- teacher1@test.com (role=TEACHER, status=ACTIVE)
- teacher2@test.com (role=TEACHER, status=ACTIVE)
- learner1@test.com (role=LEARNER, status=ACTIVE)
- learner2@test.com (role=LEARNER, status=ACTIVE)

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/login with admin credentials to get Bearer token.
2. AuthController processes login and creates session via UserSessionRepository.save().
3. UserSessionRepository saves the new session with generated tokens.
4. System returns the authentication response with Bearer token.
5. Client sends GET /api/v1/users?role=TEACHER with Authorization: Bearer {token}.
6. AuthFilter validates token and resolves ADMIN user identity.
7. UserController calls UserService.getUsers() with role filter.
8. UserService retrieves filtered user list via UserRepository.findByRole().
9. UserRepository executes database query with role filter and returns paginated results.
10. System returns the filtered paginated account list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.totalElements = 2
- All items in data.content have role = "TEACHER"
- data.content does NOT contain users with role = "LEARNER", "MANAGER", or "ADMIN"
- data.pageable.pageNumber = 0
- data.pageable.pageSize = 20 (default)
- data.first = true
- data.last = true (only 2 items, fits in first page)

---

### Test Case ID: TC_UM_VUL_003
**Test Case Description:** ADMIN searches keyword=nguyen → returns matching LEARNER

**Pre-conditions:**
Users inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- nguyen@test.com (role=LEARNER, status=ACTIVE, firstName="Nguyen", lastName="Van A")
- other.learner@test.com (role=LEARNER, status=ACTIVE)

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/login with admin credentials to get Bearer token.
2. AuthController processes login and creates session via UserSessionRepository.save().
3. UserSessionRepository saves the new session with generated tokens.
4. System returns the authentication response with Bearer token.
5. Client sends GET /api/v1/users?keyword=nguyen with Authorization: Bearer {token}.
6. AuthFilter validates token and resolves ADMIN user identity.
7. UserController calls UserService.getUsers() with keyword filter.
8. UserService searches users by keyword in firstName, lastName, or email via UserRepository.searchByKeyword().
9. UserRepository executes database query with keyword search and returns matching results.
10. System returns the filtered paginated account list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.totalElements = 1
- data.content contains user with email = "nguyen@test.com"
- data.content does NOT contain user with email = "other.learner@test.com"
- data.pageable.pageNumber = 0
- data.pageable.pageSize = 20 (default)
- data.first = true
- data.last = true (only 1 item)

---

## Section 2 — Create User Tests (POST /api/v1/users)

### Test Case ID: TC_UM_CU_001
**Test Case Description:** ADMIN creates LEARNER → 201, status=ACTIVE, email sent once

**Pre-conditions:**
ADMIN user inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/login with admin credentials to get Bearer token.
2. AuthController processes login and creates session via UserSessionRepository.save().
3. UserSessionRepository saves the new session with generated tokens.
4. System returns the authentication response with Bearer token.
5. Client sends POST /api/v1/users with Authorization: Bearer {token} and CreateUserRequest:
   - email = newlearner@test.com
   - firstName = New
   - lastName = Learner
   - role = LEARNER
6. AuthFilter validates token and resolves ADMIN user identity.
7. UserController calls UserService.createUser().
8. UserService checks for existing email via UserRepository.findByEmail().
9. UserRepository executes database query and returns empty (no existing user).
10. UserService generates random password and encodes it via PasswordEncoder.encode().
11. UserService creates new User entity and saves via UserRepository.save().
12. UserRepository saves the new user with status=ACTIVE and generated password hash.
13. EmailService.sendWelcomeEmailWithCredentials() is called once.
14. System returns the created user response.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.email = "newlearner@test.com"
- data.firstName = "New"
- data.lastName = "Learner"
- data.role = "LEARNER"
- data.status = "ACTIVE"
- data.id = non-empty UUID
- data.createdAt = non-null timestamp
- data.updatedAt = non-null timestamp
- data.passwordHash = NOT included in response (security)
- data.deletedAt = null
- data.deletedBy = null
Database state: user.status = ACTIVE, user.deletedAt = null, user.passwordHash non-empty
Email verification: sendWelcomeEmailWithCredentials called exactly once

---

### Test Case ID: TC_UM_CU_003
**Test Case Description:** ADMIN creates MANAGER → 201, status=ACTIVE

**Pre-conditions:**
ADMIN user inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/login with admin credentials to get Bearer token.
2. AuthController processes login and creates session via UserSessionRepository.save().
3. UserSessionRepository saves the new session with generated tokens.
4. System returns the authentication response with Bearer token.
5. Client sends POST /api/v1/users with Authorization: Bearer {token} and CreateUserRequest:
   - email = newmanager@test.com
   - firstName = New
   - lastName = Manager
   - role = MANAGER
6. AuthFilter validates token and resolves ADMIN user identity.
7. UserController calls UserService.createUser().
8. UserService checks for existing email via UserRepository.findByEmail().
9. UserRepository executes database query and returns empty (no existing user).
10. UserService generates random password and encodes it via PasswordEncoder.encode().
11. UserService creates new User entity and saves via UserRepository.save().
12. UserRepository saves the new user with status=ACTIVE and role=MANAGER.
13. EmailService.sendWelcomeEmailWithCredentials() is called once.
14. System returns the created user response.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.email = "newmanager@test.com"
- data.firstName = "New"
- data.lastName = "Manager"
- data.role = "MANAGER"
- data.status = "ACTIVE"
- data.id = non-empty UUID
- data.createdAt = non-null timestamp
- data.updatedAt = non-null timestamp
- data.passwordHash = NOT included in response (security)
- data.deletedAt = null
- data.deletedBy = null
Email verification: sendWelcomeEmailWithCredentials called exactly once

---

### Test Case ID: TC_UM_CU_004
**Test Case Description:** Duplicate active email → 400 EMAIL_EXISTS

**Pre-conditions:**
Users inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- exists@test.com (role=LEARNER, status=ACTIVE)

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/login with admin credentials to get Bearer token.
2. AuthController processes login and creates session via UserSessionRepository.save().
3. UserSessionRepository saves the new session with generated tokens.
4. System returns the authentication response with Bearer token.
5. Client sends POST /api/v1/users with Authorization: Bearer {token} and CreateUserRequest:
   - email = exists@test.com (duplicate)
   - firstName = New
   - lastName = User
   - role = LEARNER
6. AuthFilter validates token and resolves ADMIN user identity.
7. UserController calls UserService.createUser().
8. UserService checks for existing email via UserRepository.findByEmail().
9. UserRepository executes database query and returns existing active user.
10. UserService detects email conflict and throws BusinessException with EMAIL_EXISTS.
11. System returns the email exists error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "EMAIL_EXISTS"
- message = non-null error message
- data = null or error details
- timestamp = non-null timestamp
- path = "/api/v1/users"

---

### Test Case ID: TC_UM_CU_005
**Test Case Description:** Email belongs to deleted user → 409 DELETED_USER_EXISTS with userId

**Pre-conditions:**
Users inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- deleted@test.com (role=LEARNER, status=DELETED, deletedAt set, deletedBy set)

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/login with admin credentials to get Bearer token.
2. AuthController processes login and creates session via UserSessionRepository.save().
3. UserSessionRepository saves the new session with generated tokens.
4. System returns the authentication response with Bearer token.
5. Client sends POST /api/v1/users with Authorization: Bearer {token} and CreateUserRequest:
   - email = deleted@test.com (belongs to deleted user)
   - firstName = New
   - lastName = User
   - role = LEARNER
6. AuthFilter validates token and resolves ADMIN user identity.
7. UserController calls UserService.createUser().
8. UserService checks for existing email via UserRepository.findByEmail().
9. UserRepository executes database query and returns deleted user.
10. UserService detects deleted user conflict and throws BusinessException with DELETED_USER_EXISTS.
11. System returns the deleted user exists error response with user details.

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

### Test Case ID: TC_UM_CU_006
**Test Case Description:** ADMIN creates MANAGER role → 201, status=ACTIVE

**Pre-conditions:**
ADMIN user inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/login with admin credentials to get Bearer token.
2. AuthController processes login and creates session via UserSessionRepository.save().
3. UserSessionRepository saves the new session with generated tokens.
4. System returns the authentication response with Bearer token.
5. Client sends POST /api/v1/users with Authorization: Bearer {token} and CreateUserRequest:
   - email = mgr@test.com
   - firstName = New
   - lastName = Mgr
   - role = MANAGER
6. AuthFilter validates token and resolves ADMIN user identity.
7. UserController calls UserService.createUser().
8. UserService checks for existing email via UserRepository.findByEmail().
9. UserRepository executes database query and returns empty (no existing user).
10. UserService generates random password and encodes it via PasswordEncoder.encode().
11. UserService creates new User entity and saves via UserRepository.save().
12. UserRepository saves the new user with status=ACTIVE and role=MANAGER.
13. EmailService.sendWelcomeEmailWithCredentials() is called once.
14. System returns the created user response.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.email = "mgr@test.com"
- data.firstName = "New"
- data.lastName = "Mgr"
- data.role = "MANAGER"
- data.status = "ACTIVE"
- data.id = non-empty UUID
- data.createdAt = non-null timestamp
- data.updatedAt = non-null timestamp
- data.passwordHash = NOT included in response (security)
- data.deletedAt = null
- data.deletedBy = null
Email verification: sendWelcomeEmailWithCredentials called exactly once

---

### Test Case ID: TC_UM_CU_007
**Test Case Description:** Blank firstName → 400 VALIDATION_ERROR with firstName error

**Pre-conditions:**
ADMIN user inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/login with admin credentials to get Bearer token.
2. AuthController processes login and creates session via UserSessionRepository.save().
3. UserSessionRepository saves the new session with generated tokens.
4. System returns the authentication response with Bearer token.
5. Client sends POST /api/v1/users with Authorization: Bearer {token} and invalid JSON:
   - email = valid@test.com
   - firstName = "" (blank)
   - lastName = User
   - role = LEARNER
6. Request validation fails at controller level due to blank firstName.
7. System returns the validation error response before reaching UserService.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "VALIDATION_ERROR"
- data.firstName = non-empty validation error message
- message = non-null error message
- timestamp = non-null timestamp
- path = "/api/v1/users"

---

### Test Case ID: TC_UM_CU_008
**Test Case Description:** Invalid email format → 400 VALIDATION_ERROR with email error

**Pre-conditions:**
ADMIN user inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/login with admin credentials to get Bearer token.
2. AuthController processes login and creates session via UserSessionRepository.save().
3. UserSessionRepository saves the new session with generated tokens.
4. System returns the authentication response with Bearer token.
5. Client sends POST /api/v1/users with Authorization: Bearer {token} and invalid JSON:
   - email = not-an-email (invalid format)
   - firstName = New
   - lastName = User
   - role = LEARNER
6. Request validation fails at controller level due to invalid email format.
7. System returns the validation error response before reaching UserService.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "VALIDATION_ERROR"
- data.email = "Invalid email format"
- message = non-null error message
- timestamp = non-null timestamp
- path = "/api/v1/users"

---

### Test Case ID: TC_UM_CU_009
**Test Case Description:** No Authorization header → 401 Unauthorized

**Pre-conditions:**
None (authentication required before any processing).

**Test Case Procedure:**
1. Client sends POST /api/v1/users without Authorization header.
2. AuthFilter detects missing authentication.
3. System returns the unauthorized error response.

**Expected Results:** HTTP 401 UNAUTHORIZED
Response JSON:
- success = false
- code = "UNAUTHORIZED"
- message = non-null error message
- timestamp = non-null timestamp
- path = "/api/v1/users"

---

## Section 3 — View User Information Tests (GET /api/v1/users/{id}/details)

### Test Case ID: TC_UM_VUI_001
**Test Case Description:** ADMIN views LEARNER detail → 200, profile + enrollment arrays

**Pre-conditions:**
Users inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- learner@test.com (role=LEARNER, status=ACTIVE)

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/login with admin credentials to get Bearer token.
2. AuthController processes login and creates session via UserSessionRepository.save().
3. UserSessionRepository saves the new session with generated tokens.
4. System returns the authentication response with Bearer token.
5. Client sends GET /api/v1/users/{learnerId}/details with Authorization: Bearer {token}.
6. AuthFilter validates token and resolves ADMIN user identity.
7. UserController calls UserService.getUserDetails().
8. UserService retrieves user by ID via UserRepository.findById().
9. UserRepository executes database query and returns the user object.
10. UserService retrieves user's course enrollments via CourseEnrollmentRepository.findByUserId().
11. UserService retrieves user's class enrollments via ClassEnrollmentRepository.findByUserId().
12. System returns the user details response with profile and enrollment arrays.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.userInfo.role = "LEARNER"
- data.userInfo.email = "learner@test.com"
- data.userInfo.status = "ACTIVE"
- data.courseEnrollments = array (may be empty)
- data.classEnrollments = array (may be empty)

---

### Test Case ID: TC_UM_VUI_002
**Test Case Description:** ADMIN views MANAGER detail → 200

**Pre-conditions:**
Users inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- manager2@test.com (role=MANAGER, status=ACTIVE)

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/login with admin credentials to get Bearer token.
2. AuthController processes login and creates session via UserSessionRepository.save().
3. UserSessionRepository saves the new session with generated tokens.
4. System returns the authentication response with Bearer token.
5. Client sends GET /api/v1/users/{managerId}/details with Authorization: Bearer {token}.
6. AuthFilter validates token and resolves ADMIN user identity.
7. UserController calls UserService.getUserDetails().
8. UserService retrieves user by ID via UserRepository.findById().
9. UserRepository executes database query and returns the user object.
10. UserService retrieves user's course enrollments via CourseEnrollmentRepository.findByUserId().
11. UserService retrieves user's class enrollments via ClassEnrollmentRepository.findByUserId().
12. System returns the user details response with profile and enrollment arrays.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.userInfo.role = "MANAGER"

---

## Section 4 — Update User Tests (PUT /api/v1/users/{id})

### Test Case ID: TC_UM_UU_001
**Test Case Description:** ADMIN updates LEARNER firstName → 200, updated in response and DB

**Pre-conditions:**
Users inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- learner@test.com (role=LEARNER, status=ACTIVE, firstName="Old", lastName="Name")

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/login with admin credentials to get Bearer token.
2. AuthController processes login and creates session via UserSessionRepository.save().
3. UserSessionRepository saves the new session with generated tokens.
4. System returns the authentication response with Bearer token.
5. Client sends PUT /api/v1/users/{learnerId} with Authorization: Bearer {token} and UpdateUserRequest:
   - firstName = Updated
   - email = learner@test.com
6. AuthFilter validates token and resolves ADMIN user identity.
7. UserController calls UserService.updateUser().
8. UserService retrieves user by ID via UserRepository.findById().
9. UserRepository executes database query and returns the user object.
10. UserService updates user fields and saves via UserRepository.save().
11. UserRepository saves the updated user with new firstName and updated timestamp.
12. System returns the updated user response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.firstName = "Updated"
Database state: user.firstName = "Updated", user.updatedAt > beforeUpdate

---

### Test Case ID: TC_UM_UU_002
**Test Case Description:** Update status=DELETED → 400 INVALID_STATUS, DB status unchanged

**Pre-conditions:**
Users inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- learner@test.com (role=LEARNER, status=ACTIVE)

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/login with admin credentials to get Bearer token.
2. AuthController processes login and creates session via UserSessionRepository.save().
3. UserSessionRepository saves the new session with generated tokens.
4. System returns the authentication response with Bearer token.
5. Client sends PUT /api/v1/users/{learnerId} with Authorization: Bearer {token} and UpdateUserRequest:
   - status = DELETED (invalid for update)
   - email = learner@test.com
6. AuthFilter validates token and resolves ADMIN user identity.
7. UserController calls UserService.updateUser().
8. UserService retrieves user by ID via UserRepository.findById().
9. UserRepository executes database query and returns the user object.
10. UserService detects invalid status update and throws BusinessException with INVALID_STATUS.
11. System returns the invalid status error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "INVALID_STATUS"
Database state: user.status = ACTIVE (unchanged)

---

## Section 5 — Delete User Tests (DELETE /api/v1/users/{id})

### Test Case ID: TC_UM_DU_001
**Test Case Description:** ADMIN soft-deletes LEARNER → 200, deletedAt set, visible in list as DELETED

**Pre-conditions:**
Users inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- learner@test.com (role=LEARNER, status=ACTIVE)

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/login with admin credentials to get Bearer token.
2. AuthController processes login and creates session via UserSessionRepository.save().
3. UserSessionRepository saves the new session with generated tokens.
4. System returns the authentication response with Bearer token.
5. Client sends DELETE /api/v1/users/{learnerId} with Authorization: Bearer {token}.
6. AuthFilter validates token and resolves ADMIN user identity.
7. UserController calls UserService.deleteUser().
8. UserService retrieves user by ID via UserRepository.findById().
9. UserRepository executes database query and returns the user object.
10. UserService performs soft delete: sets deletedAt, deletedBy, and status=DELETED via UserRepository.save().
11. UserRepository saves the updated user with soft delete fields.
12. System returns the delete success response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- message = "Xóa người dùng thành công"
- data = null (no data field)
Database state: user.deletedAt ≠ null, user.deletedBy = adminId, user.status = DELETED
List verification: User appears in GET /api/v1/users with status = "DELETED"

---

### Test Case ID: TC_UM_DU_002
**Test Case Description:** ADMIN soft-deletes MANAGER → 200, deletedAt set, deletedBy=adminId

**Pre-conditions:**
Users inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- targetmanager@test.com (role=MANAGER, status=ACTIVE)

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/login with admin credentials to get Bearer token.
2. AuthController processes login and creates session via UserSessionRepository.save().
3. UserSessionRepository saves the new session with generated tokens.
4. System returns the authentication response with Bearer token.
5. Client sends DELETE /api/v1/users/{managerId} with Authorization: Bearer {token}.
6. AuthFilter validates token and resolves ADMIN user identity.
7. UserController calls UserService.deleteUser().
8. UserService retrieves user by ID via UserRepository.findById().
9. UserRepository executes database query and returns the user object.
10. UserService performs soft delete: sets deletedAt, deletedBy, and status=DELETED via UserRepository.save().
11. UserRepository saves the updated user with soft delete fields.
12. System returns the delete success response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
Database state: user.deletedAt ≠ null, user.deletedBy = adminId, user.status = DELETED

---

### Test Case ID: TC_UM_DU_003
**Test Case Description:** Delete already-deleted user → 404 NOT_FOUND (BR-34)

**Pre-conditions:**
Users inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- already.deleted@test.com (role=LEARNER, status=DELETED, deletedAt set, deletedBy set)

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/login with admin credentials to get Bearer token.
2. AuthController processes login and creates session via UserSessionRepository.save().
3. UserSessionRepository saves the new session with generated tokens.
4. System returns the authentication response with Bearer token.
5. Client sends DELETE /api/v1/users/{deletedUserId} with Authorization: Bearer {token}.
6. AuthFilter validates token and resolves ADMIN user identity.
7. UserController calls UserService.deleteUser().
8. UserService retrieves user by ID via UserRepository.findById().
9. UserRepository executes database query and returns user with status=DELETED.
10. UserService detects already deleted user and throws BusinessException with NOT_FOUND.
11. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND
Response JSON:
- success = false
- code = "NOT_FOUND"

---

## Section 6 — Restore User Tests (POST /api/v1/users/restore)

### Test Case ID: TC_UM_RU_001
**Test Case Description:** ADMIN restores deleted LEARNER → 200, ACTIVE, deletedAt=null, credentials unchanged

**Pre-conditions:**
Users inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- restore@test.com (role=LEARNER, status=DELETED, deletedAt set, deletedBy set)

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/login with admin credentials to get Bearer token.
2. AuthController processes login and creates session via UserSessionRepository.save().
3. UserSessionRepository saves the new session with generated tokens.
4. System returns the authentication response with Bearer token.
5. Client sends POST /api/v1/users/restore with Authorization: Bearer {token} and RestoreUserRequest:
   - userId = deletedUserId
   - email = restore@test.com
   - firstName = Restored
   - lastName = Learner
   - role = LEARNER
6. AuthFilter validates token and resolves ADMIN user identity.
7. UserController calls UserService.restoreUser().
8. UserService retrieves user by ID via UserRepository.findById().
9. UserRepository executes database query and returns the deleted user object.
10. UserService generates new random password and encodes it via PasswordEncoder.encode().
11. UserService clears soft delete fields and updates password via UserRepository.save().
12. UserRepository saves the updated user with status=ACTIVE, deletedAt=null, deletedBy=null, new password hash.
13. EmailService.sendWelcomeEmailWithCredentials() is called once.
14. System returns the restored user response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.status = "ACTIVE"
- data.email = "restore@test.com"
- data.firstName = "Restored"
Database state: user.deletedAt = null, user.deletedBy = null, user.status = ACTIVE, user.passwordHash ≠ oldPasswordHash
Email verification: sendWelcomeEmailWithCredentials called exactly once

---

### Test Case ID: TC_UM_RU_002
**Test Case Description:** ADMIN restores deleted MANAGER → 200, role=MANAGER, status=ACTIVE

**Pre-conditions:**
Users inserted via userRepository.save():
- admin@test.com (role=ADMIN, status=ACTIVE)
- mgr@test.com (role=MANAGER, status=DELETED, deletedAt set, deletedBy set)

**Test Case Procedure:**
1. Client sends POST /api/v1/auth/login with admin credentials to get Bearer token.
2. AuthController processes login and creates session via UserSessionRepository.save().
3. UserSessionRepository saves the new session with generated tokens.
4. System returns the authentication response with Bearer token.
5. Client sends POST /api/v1/users/restore with Authorization: Bearer {token} and RestoreUserRequest:
   - userId = deletedManagerId
   - email = mgr@test.com
   - firstName = Restored
   - lastName = Manager
   - role = MANAGER
6. AuthFilter validates token and resolves ADMIN user identity.
7. UserController calls UserService.restoreUser().
8. UserService retrieves user by ID via UserRepository.findById().
9. UserRepository executes database query and returns the deleted user object.
10. UserService generates new random password and encodes it via PasswordEncoder.encode().
11. UserService clears soft delete fields and updates password via UserRepository.save().
12. UserRepository saves the updated user with status=ACTIVE, deletedAt=null, deletedBy=null, new password hash.
13. EmailService.sendWelcomeEmailWithCredentials() is called once.
14. System returns the restored user response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.role = "MANAGER"
- data.status = "ACTIVE"

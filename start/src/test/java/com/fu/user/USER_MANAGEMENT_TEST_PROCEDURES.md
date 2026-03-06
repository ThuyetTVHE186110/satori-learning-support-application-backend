# User Management — Test Procedures
> Format: System Interaction Steps  
> Last updated: 2026-03-06

---

## Section 1 — View User List `GET /api/v1/users`

### TC_UM_VUL_001 — MANAGER views user list
1. Client → GET `/api/v1/users` with a valid MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. AuthController → call `UserService.getUsers()`.
4. UserService → retrieve the user list by MANAGER scope (TEACHER and LEARNER only).
5. UserService → filter out ADMIN, MANAGER, and soft-deleted users.
6. System → return the paginated user list response.

---

### TC_UM_VUL_002 — ADMIN views user list
1. Client → GET `/api/v1/users` with a valid ADMIN Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. AuthController → call `UserService.getUsers()`.
4. UserService → retrieve the user list by ADMIN scope (all roles, including deleted).
5. System → return the paginated user list response.

---

### TC_UM_VUL_003 — MANAGER filters by role
1. Client → GET `/api/v1/users?role=TEACHER` with a valid MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. AuthController → call `UserService.getUsers()` with role filter.
4. UserService → retrieve the user list filtered by role=TEACHER within MANAGER scope.
5. System → return the filtered paginated user list response.

---

### TC_UM_VUL_004 — MANAGER searches by keyword
1. Client → GET `/api/v1/users?keyword=nguyen` with a valid MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. AuthController → call `UserService.getUsers()` with keyword filter.
4. UserService → retrieve the user list filtered by keyword within MANAGER scope.
5. System → return the filtered paginated user list response.

---

## Section 2 — Create User `POST /api/v1/users`

### TC_UM_CU_001 — MANAGER creates LEARNER
1. Client → POST `/api/v1/users` with a valid MANAGER Bearer token and user data.
2. AuthFilter → parse the token and resolve the user identity.
3. AuthController → call `UserService.createUser()`.
4. UserService → validate the role permission (MANAGER can create LEARNER/TEACHER).
5. UserService → check that the email does not already exist.
6. UserService → generate a temporary password and save the new user.
7. UserService → send a welcome email via EmailService.
8. System → return the created user response.

---

### TC_UM_CU_003 — ADMIN creates MANAGER
1. Client → POST `/api/v1/users` with a valid ADMIN Bearer token and user data.
2. AuthFilter → parse the token and resolve the user identity.
3. AuthController → call `UserService.createUser()`.
4. UserService → validate the role permission (only ADMIN can create MANAGER).
5. UserService → check that the email does not already exist.
6. UserService → generate a temporary password and save the new user.
7. UserService → send a welcome email via EmailService.
8. System → return the created user response.

---

### TC_UM_CU_004 — Duplicate active email
1. Client → POST `/api/v1/users` with an email that already belongs to an active user.
2. AuthFilter → parse the token and resolve the user identity.
3. AuthController → call `UserService.createUser()`.
4. UserService → check that the email does not already exist.
5. UserService → detect that the email is already in use by an active account.
6. System → return the email exists error response.

---

### TC_UM_CU_005 — Email belongs to a soft-deleted user
1. Client → POST `/api/v1/users` with an email that belongs to a soft-deleted user.
2. AuthFilter → parse the token and resolve the user identity.
3. AuthController → call `UserService.createUser()`.
4. UserService → check that the email does not already exist.
5. UserService → detect that a deleted account exists with the same email.
6. System → return the deleted user conflict response with the existing userId.

---

### TC_UM_CU_006 — MANAGER creates MANAGER role (BR-11)
1. Client → POST `/api/v1/users` with role=MANAGER using a MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. AuthController → call `UserService.createUser()`.
4. UserService → validate the role permission.
5. UserService → detect that MANAGER is not allowed to create MANAGER accounts (BR-11).
6. System → return the invalid role error response.

---

### TC_UM_CU_007 — Blank firstName
1. Client → POST `/api/v1/users` with firstName = `""`.
2. AuthFilter → parse the token and resolve the user identity.
3. AuthController → validate the request body.
4. AuthController → detect that firstName is blank.
5. System → return the validation error response before reaching UserService.

---

### TC_UM_CU_008 — Invalid email format
1. Client → POST `/api/v1/users` with an invalid email format.
2. AuthFilter → parse the token and resolve the user identity.
3. AuthController → validate the request body.
4. AuthController → detect that the email format is invalid.
5. System → return the validation error response before reaching UserService.

---

### TC_UM_CU_009 — Unauthenticated request
1. Client → POST `/api/v1/users` with no Authorization header.
2. AuthFilter → detect that no token is present.
3. AuthFilter → reject the request as unauthenticated.
4. System → return the unauthorized error response.

---

## Section 3 — View User Information `GET /api/v1/users/{id}/details`

### TC_UM_VUI_001 — MANAGER views LEARNER detail
1. Client → GET `/api/v1/users/{id}/details` with a valid MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. AuthController → call `UserService.getUserDetail()`.
4. UserService → validate that the target user is within MANAGER scope.
5. UserService → retrieve the user profile and enrollment data.
6. System → return the user detail response.

---

### TC_UM_VUI_002 — MANAGER views MANAGER detail (outside scope)
1. Client → GET `/api/v1/users/{id}/details` with a MANAGER token targeting another MANAGER.
2. AuthFilter → parse the token and resolve the user identity.
3. AuthController → call `UserService.getUserDetail()`.
4. UserService → validate that the target user is within MANAGER scope.
5. UserService → detect that MANAGER is outside scope (target is also MANAGER).
6. System → return the forbidden error response.

---

## Section 4 — Update User `PUT /api/v1/users/{id}`

### TC_UM_UU_001 — MANAGER updates LEARNER's firstName
1. Client → PUT `/api/v1/users/{id}` with a valid MANAGER Bearer token and updated fields.
2. AuthFilter → parse the token and resolve the user identity.
3. AuthController → call `UserService.updateUser()`.
4. UserService → validate that the target user is within MANAGER scope.
5. UserService → apply the updates and save to the database.
6. System → return the updated user response.

---

### TC_UM_UU_002 — Update with status=DELETED
1. Client → PUT `/api/v1/users/{id}` with status=DELETED in the request body.
2. AuthFilter → parse the token and resolve the user identity.
3. AuthController → call `UserService.updateUser()`.
4. UserService → validate that the requested status change is allowed.
5. UserService → detect that status=DELETED is not a valid update value.
6. System → return the invalid status error response.

---

## Section 5 — Delete User `DELETE /api/v1/users/{id}`

### TC_UM_DU_001 — MANAGER soft-deletes LEARNER
1. Client → DELETE `/api/v1/users/{id}` with a valid MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. AuthController → call `UserService.deleteUser()`.
4. UserService → validate that the target user is within MANAGER scope.
5. UserService → set deletedAt and deletedBy on the user account.
6. System → return the success response.

---

### TC_UM_DU_002 — ADMIN soft-deletes MANAGER
1. Client → DELETE `/api/v1/users/{id}` with a valid ADMIN Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. AuthController → call `UserService.deleteUser()`.
4. UserService → validate that the target user is within ADMIN scope.
5. UserService → set deletedAt and deletedBy on the user account.
6. System → return the success response.

---

### TC_UM_DU_003 — MANAGER deletes MANAGER account (BR-33)
1. Client → DELETE `/api/v1/users/{id}` with a MANAGER token targeting another MANAGER.
2. AuthFilter → parse the token and resolve the user identity.
3. AuthController → call `UserService.deleteUser()`.
4. UserService → validate that the target user is within MANAGER scope.
5. UserService → detect that MANAGER cannot delete MANAGER accounts (BR-33).
6. System → return the forbidden error response.

---

### TC_UM_DU_004 — Delete already-deleted user (BR-34)
1. Client → DELETE `/api/v1/users/{id}` where the target user is already soft-deleted.
2. AuthFilter → parse the token and resolve the user identity.
3. AuthController → call `UserService.deleteUser()`.
4. UserService → attempt to retrieve the user by id.
5. UserService → detect that the user does not exist (soft-deleted treated as not found).
6. System → return the not found error response.

---

## Section 6 — Restore User `POST /api/v1/users/restore`

### TC_UM_RU_001 — MANAGER restores deleted LEARNER
1. Client → POST `/api/v1/users/restore` with a valid MANAGER Bearer token and restore data.
2. AuthFilter → parse the token and resolve the user identity.
3. AuthController → call `UserService.restoreUser()`.
4. UserService → validate that the target user is within MANAGER scope.
5. UserService → validate that the user is currently soft-deleted.
6. UserService → clear deletedAt and deletedBy, set status to ACTIVE.
7. UserService → generate a new temporary password and save.
8. UserService → send a welcome email via EmailService.
9. System → return the restored user response.

---

### TC_UM_RU_002 — ADMIN restores deleted MANAGER
1. Client → POST `/api/v1/users/restore` with a valid ADMIN Bearer token and restore data.
2. AuthFilter → parse the token and resolve the user identity.
3. AuthController → call `UserService.restoreUser()`.
4. UserService → validate that the target user is within ADMIN scope.
5. UserService → validate that the user is currently soft-deleted.
6. UserService → clear deletedAt and deletedBy, set status to ACTIVE.
7. UserService → generate a new temporary password and save.
8. UserService → send a welcome email via EmailService.
9. System → return the restored user response.

---

### TC_UM_RU_004 — Restore non-deleted user (BR-42)
1. Client → POST `/api/v1/users/restore` with the id of an active (non-deleted) user.
2. AuthFilter → parse the token and resolve the user identity.
3. AuthController → call `UserService.restoreUser()`.
4. UserService → validate that the user is currently soft-deleted.
5. UserService → detect that the user is not deleted (BR-42).
6. System → return the user not deleted error response.

---

### TC_UM_RU_005 — MANAGER restores deleted MANAGER (BR-40)
1. Client → POST `/api/v1/users/restore` with a MANAGER token targeting a deleted MANAGER.
2. AuthFilter → parse the token and resolve the user identity.
3. AuthController → call `UserService.restoreUser()`.
4. UserService → validate that the target user is within MANAGER scope.
5. UserService → detect that MANAGER cannot restore MANAGER accounts (BR-40).
6. System → return the forbidden error response.


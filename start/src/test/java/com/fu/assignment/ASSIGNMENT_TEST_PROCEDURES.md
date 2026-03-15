# Practice Task (Assignment) — Test Procedures
> Format: System Interaction Steps
> Focus: Component Integration and System Integration Mocking
> Last updated: 2026-03-14

---

## 1. TEACHER SIDE

### 1.1 View Practice Task List — `GET /api/v1/teacher/assignments` & `/api/v1/teacher/assignments/class/{classId}`

#### TC_AS_T_VL_001 — Retrieve list successfully, returns correct fields
1. Client → GET `/api/v1/teacher/assignments/class/{classId}` with a valid TEACHER Bearer token.
2. AuthFilter → parse the token and resolve the user identity (role=TEACHER).
3. TeacherAssignmentController → call `AssignmentService.getAssignmentsByClass()`.
4. AssignmentService → verify that the class exists and the teacher is the class owner.
5. AssignmentService → retrieve active assignments for the class.
6. System → return HTTP 200 OK with paginated list.

#### TC_AS_T_VL_002 — Class does not exist → 404/Empty list
1. Client → GET `/api/v1/teacher/assignments/class/{classId}` with a non-existent class ID.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → call `AssignmentService.getAssignmentsByClass()`.
4. AssignmentService → attempt to retrieve assignments (JOIN condition yields empty).
5. System → return HTTP 200 OK with an empty list `[]`.

#### TC_AS_T_VL_003 — Class exists but has no assignments → empty list
1. Client → GET `/api/v1/teacher/assignments/class/{classId}` with a valid TEACHER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → call `AssignmentService.getAssignmentsByClass()`.
4. AssignmentService → attempt to retrieve assignments (none found).
5. System → return HTTP 200 OK with an empty array `[]`.

#### TC_AS_T_VL_004 — Teacher is not the class owner → 400
1. Client → GET `/api/v1/teacher/assignments/class/{classId}` with another Teacher's token.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → call `AssignmentService.getAssignmentsByClass()`.
4. AssignmentService → verify class exists and check ownership.
5. AssignmentService → detect that the caller is not the class owner and throw `BusinessException(ACCESS_DENIED)`.
6. GlobalExceptionHandler → catch the exception.
7. System → return HTTP 400 Bad Request.

#### TC_AS_T_VL_005 — Learner calls Teacher endpoint → 403 (role guard)
1. Client → GET `/api/v1/teacher/assignments/class/{classId}` with a valid LEARNER Bearer token.
2. AuthFilter → parse the token and resolve the user identity (role=LEARNER).
3. AuthFilter / Method Security → detect that LEARNER role is not authorized for `@PreAuthorize("hasRole('TEACHER')")`.
4. System → return HTTP 403 Forbidden.

---

### 1.2 View Practice Task Info — `GET /api/v1/teacher/assignments/{id}`

#### TC_AS_T_VI_001 — View successfully, returns correct fields
1. Client → GET `/api/v1/teacher/assignments/{id}` with a valid TEACHER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → call `AssignmentService.getAssignment()`.
4. AssignmentService → retrieve the assignment by ID and verify teacher ownership.
5. AssignmentService → build the Response containing all info and linked questions/prompts.
6. System → return HTTP 200 OK.

#### TC_AS_T_VI_002 — Assignment does not exist → 404
1. Client → GET `/api/v1/teacher/assignments/{id}` with a non-existent assignment ID.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → call `AssignmentService.getAssignment()`.
4. AssignmentService → attempt to retrieve the assignment by ID.
5. AssignmentService → detect that no such assignment exists and throw `ResourceNotFoundException`.
6. System → return HTTP 404 Not Found.

#### TC_AS_T_VI_003 — Assignment belongs to another class (not owned by this teacher) → 400
1. Client → GET `/api/v1/teacher/assignments/{id}` for an assignment in a class the teacher does not manage.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → call `AssignmentService.getAssignment()`.
4. AssignmentService → retrieve the assignment by ID and check class ownership.
5. AssignmentService → detect that the caller is not the class owner and throw `BusinessException(ACCESS_DENIED)`.
6. System → return HTTP 400 Bad Request.

#### TC_AS_T_VI_004 — Teacher B views Teacher A's assignment → 400
1. Client → GET `/api/v1/teacher/assignments/{id}` using Teacher B's token for Teacher A's assignment.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → call `AssignmentService.getAssignment()`.
4. AssignmentService → retrieve the assignment by ID and verify ownership.
5. AssignmentService → detect that Teacher B is not the owner and throw `BusinessException(ACCESS_DENIED)`.
6. System → return HTTP 400 Bad Request.

#### TC_AS_T_VI_005 — Learner calls Teacher endpoint → 403
1. Client → GET `/api/v1/teacher/assignments/{id}` with a valid LEARNER Bearer token.
2. AuthFilter → parse the token and resolve the user identity (role=LEARNER).
3. AuthFilter / Method Security → detect unauthorized role.
4. System → return HTTP 403 Forbidden.

---

### 1.3 Create Practice Task — `POST /api/v1/teacher/assignments/class/{classId}/...`

#### TC_AS_T_CR_001 — Create QUIZ successfully → status DRAFT
1. Client → POST `/api/v1/teacher/assignments/class/{classId}/quiz` (multipart) with `data` containing JSON payload.
2. AuthFilter → parse the token and resolve the user identity (TEACHER).
3. TeacherAssignmentController → validate request and call `AssignmentService.saveFullQuiz()`.
4. AssignmentService → verify that the class exists and the caller is the owner.
5. AssignmentService → create a new Assignment entity (type=QUIZ, status=DRAFT) and link provided questions.
6. AssignmentService → persist the assignment and questions to the DB.
7. System → return HTTP 200 OK.

#### TC_AS_T_CR_002 — Create WRITING/TRANSLATION successfully → status DRAFT
1. Client → POST `/api/v1/teacher/assignments/class/{classId}/writing` (or `/translation`) with JSON payload.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → validate request and call `AssignmentService.saveFullWriting()`.
4. AssignmentService → verify that the class exists and the caller is the owner.
5. AssignmentService → create a new Assignment entity (type=WRITING, status=DRAFT) with relevant content prompts.
6. AssignmentService → persist the assignment to the DB.
7. System → return HTTP 200 OK.

#### TC_AS_T_CR_003 — Missing required field (title null) → 400
1. Client → POST `/api/v1/teacher/assignments/class/{classId}/quiz` without the `title` field.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → execute `@Valid` validation on the request body.
4. Controller/Validation framework → detect that `title` violates `@NotBlank` constraint.
5. System → return HTTP 400 Bad Request.

#### TC_AS_T_CR_004 — dueDate is set to past date when creating → 400
1. Client → POST `/api/v1/teacher/assignments/class/{classId}/quiz` with `dueDate` in the past.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → execute `@Valid` validation.
4. Controller/Validation framework → detect that `dueDate` violates `@Future` constraint.
5. System → return HTTP 400 Bad Request.

#### TC_AS_T_CR_005 — classId does not exist → 400
1. Client → POST `/api/v1/teacher/assignments/class/{classId}/quiz` with non-existent `classId`.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → call `AssignmentService.saveFullQuiz()`.
4. AssignmentService → attempt to locate the class.
5. AssignmentService → detect that the class implies it does not exist and throw `BusinessException(CLASS_NOT_FOUND)`.
6. System → return HTTP 400 Bad Request.

#### TC_AS_T_CR_006 — allowRetry = true but maxRetries not set → default = 1
1. Client → POST `/api/v1/teacher/assignments/class/{classId}/quiz` with `allowRetry: true` and `maxRetries: null`.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → call `AssignmentService.saveFullQuiz()`.
4. AssignmentService → apply default logic assigning `maxRetries = 1`.
5. AssignmentService → persist the assignment indicating retries are allowed up to 1 time.
6. System → return HTTP 200 OK.

#### TC_AS_T_CR_007 — Learner calls Teacher endpoint → 403
1. Client → POST `/api/v1/teacher/assignments/class/{classId}/quiz` with a valid LEARNER token.
2. AuthFilter / Method Security → detect unauthorized role (LEARNER).
3. System → return HTTP 403 Forbidden.

#### TC_AS_T_CR_008 — Teacher B creates assignment in Teacher A's class → 400
1. Client → POST `/api/v1/teacher/assignments/class/{classId}/quiz` using Teacher B's token for Teacher A's class.
2. AuthFilter → parse the token and resolve the user identity (Teacher B).
3. TeacherAssignmentController → call `AssignmentService.saveFullQuiz()`.
4. AssignmentService → verify class exists and verify owner.
5. AssignmentService → detect that Teacher B is not the owner of the class and throw `BusinessException(ACCESS_DENIED)`.
6. System → return HTTP 400 Bad Request.

---

### 1.4 Update Practice Task (UPSERT) — `PUT /api/v1/teacher/assignments/{id}/...`

#### TC_AS_T_UP_001 — Update metadata successfully when DRAFT
1. Client → PUT `/api/v1/teacher/assignments/{id}/quiz` with new `title`/`description` and `status: DRAFT`.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → validate request and call `AssignmentService.saveFullQuiz()`.
4. AssignmentService → retrieve the assignment by ID and verify ownership.
5. AssignmentService → update metadata fields and persist to DB.
6. System → return HTTP 200 OK.

#### TC_AS_T_UP_002 — Update metadata successfully when PUBLISHED
1. Client → PUT `/api/v1/teacher/assignments/{id}/quiz` with new `title`/`description` and `status: PUBLISHED`.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → validate request and call `AssignmentService.saveFullQuiz()`.
4. AssignmentService → retrieve the assignment by ID and verify ownership.
5. AssignmentService → update metadata fields and persist to DB.
6. System → return HTTP 200 OK.

#### TC_AS_T_UP_003 — Update content (questions) when no submissions exist → successfully
1. Client → PUT `/api/v1/teacher/assignments/{id}/quiz` changing questions array, while no submissions exist.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → call `AssignmentService.saveFullQuiz()`.
4. AssignmentService → retrieve assignment and verify no submissions exist (`hasSubmissions == false`).
5. AssignmentService → update, insert, or delete related questions.
6. System → return HTTP 200 OK.

#### TC_AS_T_UP_004 — Update content when hasSubmissions = true → 400
1. Client → PUT `/api/v1/teacher/assignments/{id}/quiz` changing questions when submissions exist.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → call `AssignmentService.saveFullQuiz()`.
4. AssignmentService → retrieve assignment and determine `hasSubmissions == true`.
5. AssignmentService → detect Content modification and block it, throwing `BusinessException`.
6. System → return HTTP 400 Bad Request.

#### TC_AS_T_UP_005 — Update dueDate — shorten when hasSubmissions = true → 400
1. Client → PUT `/api/v1/teacher/assignments/{id}/quiz` shrinking `dueDate` when submissions exist.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → call `AssignmentService.saveFullQuiz()`.
4. AssignmentService → check `hasSubmissions == true` and detect attempt to shorten `dueDate`.
5. AssignmentService → throw `BusinessException` blocking the timeline restriction.
6. System → return HTTP 400 Bad Request.

#### TC_AS_T_UP_006 — Update dueDate — extend when hasSubmissions = true → successfully
1. Client → PUT `/api/v1/teacher/assignments/{id}/quiz` extending `dueDate` when submissions exist.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → call `AssignmentService.saveFullQuiz()`.
4. AssignmentService → check `hasSubmissions == true` and allow dueDate extension.
5. AssignmentService → persist the new `dueDate` to DB.
6. System → return HTTP 200 OK.

#### TC_AS_T_UP_007 — Update passingScore — increase when hasSubmissions = true → 400
1. Client → PUT `/api/v1/teacher/assignments/{id}/quiz` increasing `passingScore` when submissions exist.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → call `AssignmentService.saveFullQuiz()`.
4. AssignmentService → detect `hasSubmissions == true` and block passing score increase.
5. System → return HTTP 400 Bad Request.

#### TC_AS_T_UP_008 — Update dueDate to the past → 400
1. Client → PUT `/api/v1/teacher/assignments/{id}/quiz` with a past `dueDate`.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → execute `@Valid` validation.
4. Controller/Validation framework → detect that `dueDate` violates `@Future` constraint.
5. System → return HTTP 400 Bad Request.

---

### 1.5 Update Practice Task — status transitions — `PUT /api/v1/teacher/assignments/{id}/...`

#### TC_AS_T_ST_001 — DRAFT → PUBLISHED successfully
1. Client → PUT `/api/v1/teacher/assignments/{id}/quiz` with `status: PUBLISHED` for an assignment that has questions and a valid `dueDate`.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → call `AssignmentService.saveFullQuiz()`.
4. AssignmentService → retrieve assignment (currently DRAFT).
5. AssignmentService → execute status transition validation (checks questions size > 0, dueDate valid).
6. AssignmentService → update status to PUBLISHED and persist to DB.
7. System → return HTTP 200 OK.

#### TC_AS_T_ST_002 — DRAFT → PUBLISHED missing content → 400
1. Client → PUT `/api/v1/teacher/assignments/{id}/quiz` with `status: PUBLISHED` but empty questions array.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → call `AssignmentService.saveFullQuiz()`.
4. AssignmentService → execute status transition validation.
5. AssignmentService → detect empty questions and throw `BusinessException(NO_QUESTIONS)`.
6. System → return HTTP 400 Bad Request.

#### TC_AS_T_ST_003 — DRAFT → PUBLISHED dueDate overdue in request → 400
1. Client → PUT `/api/v1/teacher/assignments/{id}/quiz` with `status: PUBLISHED` and invalid past `dueDate`.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → `@Valid` check detects past `dueDate`.
4. System → return HTTP 400 Bad Request (@Future validation error).

#### TC_AS_T_ST_004 — PUBLISHED → DRAFT → firmly blocked → 400
1. Client → PUT `/api/v1/teacher/assignments/{id}/quiz` with `status: DRAFT` for an assignment currently PUBLISHED.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → call `AssignmentService.saveFullQuiz()`.
4. AssignmentService → attempt state transition.
5. AssignmentService → detect downward transition (PUBLISHED → DRAFT) and throw `BusinessException(INVALID_STATUS_TRANSITION)`.
6. System → return HTTP 400 Bad Request.

#### TC_AS_T_ST_005 — PUBLISHED → CLOSED → successfully
1. Client → PUT `/api/v1/teacher/assignments/{id}/quiz` with `status: CLOSED` for an assignment currently PUBLISHED.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → call `AssignmentService.saveFullQuiz()`.
4. AssignmentService → attempt state transition.
5. AssignmentService → validate transition and persist CLOSED state.
6. System → return HTTP 200 OK.

#### TC_AS_T_ST_006 — CLOSED → any → terminal block → 400
1. Client → PUT `/api/v1/teacher/assignments/{id}/quiz` changing status away from CLOSED.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → call `AssignmentService.saveFullQuiz()`.
4. AssignmentService → detect current state is CLOSED.
5. AssignmentService → reject state change as CLOSED is terminal.
6. System → return HTTP 400 Bad Request.

---

### 1.6 Delete Practice Task — `DELETE /api/v1/teacher/assignments/{id}`

#### TC_AS_T_DL_001 — Delete DRAFT without submission → soft delete successfully
1. Client → DELETE `/api/v1/teacher/assignments/{id}` with a valid TEACHER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → call `AssignmentService.deleteAssignment()`.
4. AssignmentService → retrieve assignment by ID and verify ownership.
5. AssignmentService → verify assignment is DRAFT and has no submissions.
6. AssignmentService → set `deleted_at` and `deleted_by` fields.
7. System → return HTTP 200 OK.

#### TC_AS_T_DL_002 — Delete DRAFT with submission → deleted successfully, submissions become orphaned (BUG TEST)
1. Client → DELETE `/api/v1/teacher/assignments/{id}` knowing submissions exist.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → call `AssignmentService.deleteAssignment()`.
4. AssignmentService → retrieve assignment, verify ownership, verify it is DRAFT.
5. AssignmentService → fails to guard against existing submissions (current bug behavior) and soft-deletes the assignment.
6. System → return HTTP 200 OK.

#### TC_AS_T_DL_003 — Delete PUBLISHED → blocked by requireDraft() → 400
1. Client → DELETE `/api/v1/teacher/assignments/{id}` for a PUBLISHED assignment.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → call `AssignmentService.deleteAssignment()`.
4. AssignmentService → retrieve assignment by ID.
5. AssignmentService → check `requireDraft()` and detect assignment is not DRAFT, throwing `BusinessException`.
6. System → return HTTP 400 Bad Request.

#### TC_AS_T_DL_004 — Delete CLOSED → blocked by requireDraft() → 400
1. Client → DELETE `/api/v1/teacher/assignments/{id}` for a CLOSED assignment.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → call `AssignmentService.deleteAssignment()`.
4. AssignmentService → retrieve assignment and throw `BusinessException` due to non-DRAFT state.
5. System → return HTTP 400 Bad Request.

#### TC_AS_T_DL_005 — Teacher B deletes Teacher A's assignment → 400
1. Client → DELETE `/api/v1/teacher/assignments/{id}` using Teacher B's token.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → call `AssignmentService.deleteAssignment()`.
4. AssignmentService → retrieve assignment and verify ownership.
5. AssignmentService → detect Teacher B is not owner and throw `BusinessException(ACCESS_DENIED)`.
6. System → return HTTP 400 Bad Request.

#### TC_AS_T_DL_006 — Learner calls Teacher DELETE endpoint → 403
1. Client → DELETE `/api/v1/teacher/assignments/{id}` with LEARNER token.
2. AuthFilter / Method Security → reject unprivileged role.
3. System → return HTTP 403 Forbidden.

---

### 1.7 View Practice Task Results (Teacher) — `GET .../submissions`

#### TC_AS_T_VS_001 — View all submissions list → returns correctly
1. Client → GET `/api/v1/teacher/assignments/{id}/submissions` with a valid TEACHER token.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → call `AssignmentService.getSubmissionsByAssignment()`.
4. AssignmentService → retrieve paginated list of submissions for the given assignment.
5. AssignmentService → compile submission objects including score and status.
6. System → return HTTP 200 OK.

#### TC_AS_T_VS_002 — Assignment has no submissions → empty list
1. Client → GET `/api/v1/teacher/assignments/{id}/submissions` for an assignment with no submissions.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → call `AssignmentService.getSubmissionsByAssignment()`.
4. AssignmentService → query database, yielding no results.
5. System → return HTTP 200 OK with `data.content = []`.

#### TC_AS_T_VS_003 — Assignment ID does not exist → 404
1. Client → GET `/api/v1/teacher/assignments/{id}/submissions` with an invalid assignment ID.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → call `AssignmentService.getSubmissionsByAssignment()`.
4. AssignmentService → attempt query but assignment does not exist.
5. System → return HTTP 404 Not Found.

#### TC_AS_T_VS_004 — View 1 specific submission → correct fields
1. Client → GET `/api/v1/teacher/assignments/submissions/{subId}`.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → call `AssignmentService.getSubmission()`.
4. AssignmentService → retrieve submission details (answers, score, feedback).
5. System → return HTTP 200 OK.

#### TC_AS_T_VS_005 — Submission does not exist → 404
1. Client → GET `/api/v1/teacher/assignments/submissions/{subId}` with an invalid submission ID.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → call `AssignmentService.getSubmission()`.
4. AssignmentService → unable to find submission, throwing `ResourceNotFoundException`.
5. System → return HTTP 404 Not Found.

#### TC_AS_T_VS_006 — Learner calls Teacher endpoint → 403
1. Client → GET `/api/v1/teacher/assignments/{id}/submissions` with a LEARNER token.
2. AuthFilter / Method Security → role validation fails.
3. System → return HTTP 403 Forbidden.

---

## 2. LEARNER SIDE

### 2.1 View Assigned Practice Tasks — `GET /api/v1/learner/assignments` & `{id}`

#### TC_AS_L_VL_001 — View list successfully
1. Client → GET `/api/v1/learner/assignments` with a valid LEARNER Bearer token.
2. AuthFilter → parse the token and resolve the user identity (role=LEARNER).
3. LearnerAssignmentController → call `AssignmentService.getLearnerAssignments()`.
4. AssignmentService → fetch the learner's enrolled classes.
5. AssignmentService → retrieve PUBLISHED (or CLOSED) assignments associated with those classes.
6. AssignmentService → build the paginated response.
7. System → return HTTP 200 OK.

#### TC_AS_L_VL_002 — DRAFT assignment not visible to Learner
1. Client → GET `/api/v1/learner/assignments`.
2. AuthFilter → parse the token and resolve the user identity.
3. LearnerAssignmentController → call `AssignmentService.getLearnerAssignments()`.
4. AssignmentService → query DB excluding assignments with status=DRAFT.
5. System → return HTTP 200 OK.

#### TC_AS_L_VL_003 — CLOSED assignment remains visible (for history view)
1. Client → GET `/api/v1/learner/assignments`.
2. AuthFilter → parse the token and resolve the user identity.
3. LearnerAssignmentController → call `AssignmentService.getLearnerAssignments()`.
4. AssignmentService → query DB including assignments with status=CLOSED.
5. System → return HTTP 200 OK.

#### TC_AS_L_VL_004 — Learner belongs to no class → empty list
1. Client → GET `/api/v1/learner/assignments`.
2. AuthFilter → parse the token and resolve the user identity.
3. LearnerAssignmentController → call `AssignmentService.getLearnerAssignments()`.
4. AssignmentService → determine learner has 0 class enrollments.
5. System → return HTTP 200 OK with `data.content = []`.

#### TC_AS_L_VL_005 — Teacher calls Learner endpoint → 403
1. Client → GET `/api/v1/learner/assignments` with TEACHER token.
2. AuthFilter / Method Security → reject role (requires LEARNER).
3. System → return HTTP 403 Forbidden.

---

### 2.2 Do Practice Task (startAssignment + submit)

#### TC_AS_L_DO_001 — Start successfully → submission created with IN_PROGRESS
1. Client → POST `/api/v1/learner/assignments/{id}/start` with LEARNER token.
2. AuthFilter → parse the token and resolve the user identity.
3. LearnerAssignmentController → call `AssignmentService.startAssignment()`.
4. AssignmentService → check assignment status (PUBLISHED) and learner eligibility.
5. AssignmentService → create new Submission entity with status `IN_PROGRESS` and `startedAt`.
6. AssignmentService → save Submission to DB.
7. System → return HTTP 200 OK.

#### TC_AS_L_DO_002 — Assignment not in PUBLISHED state → 400
1. Client → POST `/api/v1/learner/assignments/{id}/start` on a DRAFT assignment.
2. AuthFilter → parse the token and resolve the user identity.
3. LearnerAssignmentController → call `AssignmentService.startAssignment()`.
4. AssignmentService → check assignment status.
5. AssignmentService → detect it is DRAFT and throw `BusinessException(ASSIGNMENT_NOT_PUBLISHED)`.
6. System → return HTTP 400 Bad Request.

#### TC_AS_L_DO_003 — allowRetry = false, already has 1 graded attempt → 400
1. Client → POST `/api/v1/learner/assignments/{id}/start` for an assignment allowing NO retries.
2. AuthFilter → parse the token and resolve the user identity.
3. LearnerAssignmentController → call `AssignmentService.startAssignment()`.
4. AssignmentService → calculate previous attempts for this learner.
5. AssignmentService → detect previous attempt exists and retry is forbidden.
6. AssignmentService → throw `BusinessException(NO_RETRY)`.
7. System → return HTTP 400 Bad Request.

#### TC_AS_L_DO_004 — allowRetry = true, attemptCount >= maxRetries → 400
1. Client → POST `/api/v1/learner/assignments/{id}/start`.
2. AuthFilter → parse the token and resolve the user identity.
3. LearnerAssignmentController → call `AssignmentService.startAssignment()`.
4. AssignmentService → count previous attempts.
5. AssignmentService → detect attempts >= `maxRetries` and throw `BusinessException(MAX_RETRIES)`.
6. System → return HTTP 400 Bad Request.

#### TC_AS_L_DO_005 — Active submission IN_PROGRESS exists, start again → 400
1. Client → POST `/api/v1/learner/assignments/{id}/start` while an attempt is active.
2. AuthFilter → parse the token and resolve the user identity.
3. LearnerAssignmentController → call `AssignmentService.startAssignment()`.
4. AssignmentService → check existing submissions for learner.
5. AssignmentService → detect an active `IN_PROGRESS` attempt and throw `BusinessException(ALREADY_IN_PROGRESS)`.
6. System → return HTTP 400 Bad Request.

#### TC_AS_L_DO_006 — Submit Quiz on time, correct answers → GRADED, score=100
1. Client → POST `/api/v1/learner/assignments/submit` with correct answers.
2. AuthFilter → parse the token and resolve the user identity.
3. LearnerAssignmentController → validate body and call `AssignmentService.submitQuiz()`.
4. AssignmentService → retrieve active `IN_PROGRESS` submission.
5. AssignmentService → process the answers, calculate the score against correct question answers.
6. AssignmentService → set status to `GRADED` and calculate final score=100.
7. AssignmentService → save updated Submission to DB.
8. System → return HTTP 200 OK.

#### TC_AS_L_DO_007 — Submit Quiz with wrong answers → GRADED, score=0
1. Client → POST `/api/v1/learner/assignments/submit` with incorrect answers.
2. AuthFilter → parse the token and resolve the user identity.
3. LearnerAssignmentController → call `AssignmentService.submitQuiz()`.
4. AssignmentService → calculate the score (0 matches).
5. AssignmentService → set status to `GRADED` with score=0.
6. System → return HTTP 200 OK.

#### TC_AS_L_DO_008 — Submit Quiz past dueDate → OVERDUE
1. Client → POST `/api/v1/learner/assignments/submit` after the assignment's `dueDate`.
2. AuthFilter → parse the token and resolve the user identity.
3. LearnerAssignmentController → call `AssignmentService.submitQuiz()`.
4. AssignmentService → verify submission timestamp against `dueDate`.
5. AssignmentService → determine it is submitted late, setting status to `OVERDUE`.
6. System → return HTTP 200 OK.

#### TC_AS_L_DO_009 — Submit Writing on time → SUBMITTED (awaiting grading)
1. Client → POST `/api/v1/learner/assignments/{id}/submit-writing` with `data` part and image attachments.
2. AuthFilter → parse the token and resolve the user identity.
3. LearnerAssignmentController → process multipart request and call `AssignmentService.submitWriting()`.
4. AssignmentService → upload images if any, associate with Submission.
5. AssignmentService → update Submission content and set status to `SUBMITTED`.
6. AssignmentService → save to DB without auto-grading.
7. System → return HTTP 200 OK.

#### TC_AS_L_DO_010 — Submit Writing past dueDate → OVERDUE
1. Client → POST `/api/v1/learner/assignments/{id}/submit-writing` after `dueDate`.
2. AuthFilter → parse the token and resolve the user identity.
3. LearnerAssignmentController → call `AssignmentService.submitWriting()`.
4. AssignmentService → check `dueDate` and set status to `OVERDUE`.
5. System → return HTTP 200 OK.

#### TC_AS_L_DO_011 — Submit Writing missing text/image → 400
1. Client → POST `/api/v1/learner/assignments/{id}/submit-writing` providing no text answer and no image uploads.
2. AuthFilter → parse the token and resolve the user identity.
3. LearnerAssignmentController → call `AssignmentService.submitWriting()`.
4. AssignmentService → detect empty input payload and throw `BusinessException`.
5. System → return HTTP 400 Bad Request.

#### TC_AS_L_DO_012 — Teacher calls Learner submit endpoint → 403
1. Client → POST submit endpoints with a TEACHER token.
2. AuthFilter / Method Security → reject role (requires LEARNER).
3. System → return HTTP 403 Forbidden.

#### TC_AS_L_DO_013 — Start assignment when CLOSED → 400
1. Client → POST `/api/v1/learner/assignments/{id}/start` on a CLOSED assignment.
2. AuthFilter → parse the token and resolve the user identity.
3. LearnerAssignmentController → call `AssignmentService.startAssignment()`.
4. AssignmentService → evaluate status and throw `BusinessException` (cannot start a closed assignment).
5. System → return HTTP 400 Bad Request.

#### TC_AS_L_DO_014 — Submit assignment when CLOSED → 400
1. Client → POST `/api/v1/learner/assignments/submit` on a CLOSED assignment.
2. AuthFilter → parse the token and resolve the user identity.
3. LearnerAssignmentController → call `AssignmentService.submitQuiz()`.
4. AssignmentService → checking status and seeing CLOSED, throw `BusinessException`.
5. System → return HTTP 400 Bad Request.

---

### 2.3 View Practice Task Results (Learner) — `GET .../submissions`

#### TC_AS_L_VS_001 — View own submission → successfully
1. Client → GET `/api/v1/learner/assignments/submissions/{subId}` with LEARNER token.
2. AuthFilter → parse the token and resolve the user identity.
3. LearnerAssignmentController → call `AssignmentService.getMySubmission()`.
4. AssignmentService → load the specific submission.
5. AssignmentService → verify that the submission belongs to the current Learner.
6. System → return HTTP 200 OK.

#### TC_AS_L_VS_002 — Learner B views Learner A's submission → 400
1. Client → GET `/api/v1/learner/assignments/submissions/{subId}` mapping to Learner A, but requested with Learner B's token.
2. AuthFilter → parse the token and resolve the user identity (Learner B).
3. LearnerAssignmentController → call `AssignmentService.getMySubmission()`.
4. AssignmentService → load submission and check ownership.
5. AssignmentService → mismatch detected, throw `BusinessException(ACCESS_DENIED)`.
6. System → return HTTP 400 Bad Request.

#### TC_AS_L_VS_003 — Submission SUBMITTED (ungraded) → status=SUBMITTED, score=null
1. Client → GET `/api/v1/learner/assignments/submissions/{subId}` mapping to a WRITING submission not yet graded.
2. AuthFilter → parse the token and resolve the user identity.
3. LearnerAssignmentController → call `AssignmentService.getMySubmission()`.
4. AssignmentService → retrieve DB record (status=SUBMITTED, score=NULL).
5. System → return HTTP 200 OK reflecting those values.

#### TC_AS_L_VS_004 — Submission OVERDUE → status=OVERDUE
1. Client → GET `/api/v1/learner/assignments/submissions/{subId}` for a late submission.
2. AuthFilter → parse the token and resolve the user identity.
3. LearnerAssignmentController → call `AssignmentService.getMySubmission()`.
4. AssignmentService → retrieve record reflecting the `OVERDUE` status.
5. System → return HTTP 200 OK.

#### TC_AS_L_VS_005 — Submission does not exist → 404
1. Client → GET `/api/v1/learner/assignments/submissions/{invalidSubId}`.
2. AuthFilter → parse the token and resolve the user identity.
3. LearnerAssignmentController → call `AssignmentService.getMySubmission()`.
4. AssignmentService → attempt DB lookup, not found.
5. AssignmentService → throw `ResourceNotFoundException`.
6. System → return HTTP 404 Not Found.

---

## 3. SYSTEM INTEGRATION: EXTERNAL APIs (OPENAI / AZURE)

#### TC_AS_AI_001 — AI Generate Questions (Automated Mock)
1. System Configuration → Spring Boot Test context injects `@MockBean openaiService` returning mocked responses.
2. Client → POST `/api/v1/teacher/assignments/questions/ai-generate` within a `@SpringBootTest`.
3. TeacherAssignmentController → calls `AssignmentService.aiGenerateQuestions()`.
4. AssignmentService → forwards the generation request to the mocked `openaiService`.
5. Spring Test → returning predefined responses immediately.
6. System → return HTTP 200 OK containing parsed list.

#### TC_AS_AI_002 — AI Generate Explanation (Automated Mock)
1. System Configuration → `@MockBean openaiService` injected for explanation generation.
2. Client → POST `/api/v1/teacher/assignments/questions/{id}/ai-explain`.
3. TeacherAssignmentController → calls `AssignmentService.aiGenerateExplanation()`.
4. AssignmentService → uses mocked AI interaction to obtain explanation text.
5. AssignmentService → saves explanation to DB Question record.
6. System → return HTTP 200 OK with `data.explanation` matched.

#### TC_AS_AI_003 — AI Generate Questions (validation) missing teacherPrompt → 400
1. Client → POST `/api/v1/teacher/assignments/questions/ai-generate` missing `teacherPrompt`.
2. AuthFilter → parse the token and resolve the user identity.
3. TeacherAssignmentController → validation fails on request body constraints.
4. System → return HTTP 400 Bad Request.

#### TC_AS_AI_004 — Learner calls ai-generate endpoint → 403
1. Client → POST `/api/v1/teacher/assignments/questions/ai-generate` with LEARNER token.
2. AuthFilter / Method Security → method requires TEACHER role.
3. System → return HTTP 403 Forbidden.

#### TC_AS_AI_005 — AI Generate Questions (Postman Live)
1. Pre-condition → proper remote endpoint and live OpenAI API keys setup in `application.properties`.
2. Client → POST `/api/v1/teacher/assignments/questions/ai-generate` via Postman.
3. TeacherAssignmentController → calls real `AssignmentService`.
4. AssignmentService → issues LIVE HTTP requests to OpenAI backend waiting for realistic completions.
5. OpenAI Backend → returns tokens interpreted dynamically by the Backend.
6. System → return HTTP 200 OK containing realistic questions populated. (MANUAL TEST ONLY)

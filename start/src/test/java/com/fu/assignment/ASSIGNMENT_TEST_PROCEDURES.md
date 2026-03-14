# Practice Task (Assignment) — Test Procedures
> Format: Tabular Test Scenarios
> Focus: Component Integration and System Integration Mocking
> Last updated: 2026-03-14

## 1. TEACHER SIDE

### 1.1 View Practice Task List — `GET /api/v1/teacher/assignments` & `/api/v1/teacher/assignments/class/{classId}`

| Test Case ID | Test Case Description | Test Case Procedure | Expected Results | Pre-conditions | Round 1 | Test date | Tester | Note |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **TC_AS_T_VL_001** | Retrieve list successfully, returns correct fields | Client → GET `/api/v1/teacher/assignments/class/{classId}` with TEACHER token.<br>System → return paginated list of assignments created for the class. | HTTP 200 OK.<br>`success = true`.<br>`data.content` covers all active assignments with valid base fields (id, title, type, dueDate). | Class exists and has active assignments. Teacher is creator/owner. | | | | |
| **TC_AS_T_VL_002** | Class does not exist → 404/Empty list | Client → GET with non-existent `{classId}`. | HTTP 200 OK.<br>`data.content` is empty array `[]` (JOIN found nothing). | | | | | |
| **TC_AS_T_VL_003** | Class exists but has no assignments → empty list | Client → GET `/api/v1/teacher/assignments/class/{classId}`. | HTTP 200 OK.<br>`data.content` is empty array `[]`. | Class exists. No assignments created. | | | | |
| **TC_AS_T_VL_004** | Teacher is not the class owner → 400 | Client → GET `/api/v1/teacher/assignments/class/{classId}` with another Teacher's token. | HTTP 400 Bad Request (ACCESS_DENIED). | Class exists. Caller is not the class owner. | | | | |
| **TC_AS_T_VL_005** | Learner calls Teacher endpoint → 403 (role guard) | Client → GET `/api/v1/teacher/assignments/class/{classId}` with LEARNER token. | HTTP 403 Forbidden. | Learner account exists. | | | | |

### 1.2 View Practice Task Info — `GET /api/v1/teacher/assignments/{id}`

| Test Case ID | Test Case Description | Test Case Procedure | Expected Results | Pre-conditions | Round 1 | Test date | Tester | Note |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **TC_AS_T_VI_001** | View successfully, returns correct fields | Client → GET `/api/v1/teacher/assignments/{id}` with TEACHER token. | HTTP 200 OK.<br>`success = true`. Contains all info and linked questions/prompts. | Assignment exists. Teacher is owner. | | | | |
| **TC_AS_T_VI_002** | Assignment does not exist → 404 | Client → GET with non-existent `{id}`. | HTTP 404 Not Found. | | | | | |
| **TC_AS_T_VI_003** | Assignment belongs to another class (not owned by this teacher) → 400 | Client → GET `{id}` for an assignment in a class the teacher does not manage. | HTTP 400 Bad Request (ACCESS_DENIED). | Assignment exists. | | | | |
| **TC_AS_T_VI_004** | Teacher B views Teacher A's assignment → 400 | Client → GET `{id}` with Teacher B token for Teacher A's assignment. | HTTP 400 Bad Request (ACCESS_DENIED). | Two teachers exist. | | | | |
| **TC_AS_T_VI_005** | Learner calls Teacher endpoint → 403 | Client → GET `/api/v1/teacher/assignments/{id}` with LEARNER token. | HTTP 403 Forbidden. | Learner account exists. | | | | |

### 1.3 Create Practice Task — `POST /api/v1/teacher/assignments/class/{classId}/...`

| Test Case ID | Test Case Description | Test Case Procedure | Expected Results | Pre-conditions | Round 1 | Test date | Tester | Note |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **TC_AS_T_CR_001** | Create QUIZ successfully → status DRAFT | Client → POST `.../quiz` (multipart) with `data` containing JSON. | HTTP 200 OK.<br>`data.type = QUIZ`.<br>`data.status = DRAFT`. | Valid class. Logged in Teacher is owner. | | | | |
| **TC_AS_T_CR_002** | Create WRITING/TRANSLATION successfully → status DRAFT | Client → POST `.../writing` (and `.../translation`) with valid JSON data. | HTTP 200 OK.<br>`data.type` covers Writing/Translation.<br>`data.status = DRAFT`. | Valid class. Logged in Teacher is owner. | | | | |
| **TC_AS_T_CR_003** | Missing required field (title null) → 400 | Client → POST `.../quiz` without `title`. | HTTP 400 Bad Request (Validation Error). | | | | | |
| **TC_AS_T_CR_004** | dueDate is set to past date when creating → 400 | Client → POST `.../quiz` with `dueDate` set to past date. | HTTP 400 Bad Request. | | | | | @Future validation |
| **TC_AS_T_CR_005** | classId does not exist → 400 | Client → POST to non-existent `classId`. | HTTP 400 Bad Request (CLASS_NOT_FOUND). | | | | | |
| **TC_AS_T_CR_006** | allowRetry = true but maxRetries not set → default = 1 | Client → POST with `allowRetry: true` and `maxRetries: null`. | HTTP 200 OK.<br>`data.maxRetries = 1`. | | | | | |
| **TC_AS_T_CR_007** | Learner calls Teacher endpoint → 403 | Client → POST `.../quiz` with LEARNER token. | HTTP 403 Forbidden. | | | | | |
| **TC_AS_T_CR_008** | Teacher B creates assignment in Teacher A's class → 400 | Client → POST to Teacher A's `classId` using Teacher B's token. | HTTP 400 Bad Request (ACCESS_DENIED). | | | | | |

### 1.4 Update Practice Task — metadata & content — `PUT /api/v1/teacher/assignments/{id}/...`
*Note: The system uses a unified UPSERT mechanism. Metadata, content, and state changes all use the same endpoint `PUT /api/v1/teacher/assignments/{id}/quiz` (or `/writing`, `/translation`).*

| Test Case ID | Test Case Description | Test Case Procedure | Expected Results | Pre-conditions | Round 1 | Test date | Tester | Note |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **TC_AS_T_UP_001** | Update metadata successfully when DRAFT | Client → PUT `.../{id}/quiz` with new `title`/`description` and `status: DRAFT`. | HTTP 200 OK.<br>Assignment metadata updated. | Assignment = DRAFT. | | | | |
| **TC_AS_T_UP_002** | Update metadata successfully when PUBLISHED | Client → PUT `.../{id}/quiz` with new `title`/`description` and `status: PUBLISHED`. | HTTP 200 OK.<br>Assignment metadata updated. | Assignment = PUBLISHED. | | | | |
| **TC_AS_T_UP_003** | Update content (questions) when no submissions exist → successfully | Client → PUT `.../{id}/quiz` changing questions while no submissions exist. | HTTP 200 OK.<br>Questions updated. | `hasSubmissions = false`. | | | | |
| **TC_AS_T_UP_004** | Update content when hasSubmissions = true → 400 | Client → PUT `.../{id}/quiz` changing questions when submissions exist. | HTTP 400 Bad Request.<br>Block modifications to active task content. | `hasSubmissions = true`. | | | | |
| **TC_AS_T_UP_005** | Update dueDate — shorten when hasSubmissions = true → 400 | Client → PUT `.../{id}/quiz` shrinking `dueDate` while submissions exist. | HTTP 400 Bad Request.<br>Block shrinking timeline. | `hasSubmissions = true`. | | | | |
| **TC_AS_T_UP_006** | Update dueDate — extend when hasSubmissions = true → successfully | Client → PUT `.../{id}/quiz` extending `dueDate` while submissions exist. | HTTP 200 OK.<br>Extension allowed. | `hasSubmissions = true`. | | | | |
| **TC_AS_T_UP_007** | Update passingScore — increase when hasSubmissions = true → 400 | Client → PUT `.../{id}/quiz` increasing `passingScore` while submissions exist. | HTTP 400 Bad Request. | `hasSubmissions = true`. | | | | |
| **TC_AS_T_UP_008** | Update dueDate to the past → 400 | Client → PUT `.../{id}/quiz` with past `dueDate`. | HTTP 400 Bad Request. | | | | | |

### 1.5 Update Practice Task — status transitions — `PUT /api/v1/teacher/assignments/{id}/...`

| Test Case ID | Test Case Description | Test Case Procedure | Expected Results | Pre-conditions | Round 1 | Test date | Tester | Note |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **TC_AS_T_ST_001** | DRAFT → PUBLISHED successfully | Client → PUT `.../{id}/quiz` with `status: PUBLISHED`. | HTTP 200 OK.<br>`data.status = PUBLISHED`. | Assignment has questions & valid `dueDate`. | | | | |
| **TC_AS_T_ST_002** | DRAFT → PUBLISHED missing content → 400 | Client → PUT `.../{id}/quiz` with `status: PUBLISHED` but empty questions array. | HTTP 400 Bad Request (NO_QUESTIONS). | Quiz `questions` size = 0. | | | | |
| **TC_AS_T_ST_003** | DRAFT → PUBLISHED dueDate overdue in request → 400 | Client → PUT `.../{id}/quiz` with `status: PUBLISHED` and invalid `dueDate`. | HTTP 400 Bad Request (@Future error). | `dueDate` < Now. | | | | |
| **TC_AS_T_ST_004** | PUBLISHED → DRAFT → firmly blocked → 400 | Client → PUT `.../{id}/quiz` with `status: DRAFT`. | HTTP 400 Bad Request (INVALID_STATUS_TRANSITION). | Assignment = PUBLISHED. | | | | |
| **TC_AS_T_ST_005** | PUBLISHED → CLOSED → successfully | Client → PUT `.../{id}/quiz` with `status: CLOSED`. | HTTP 200 OK.<br>`data.status = CLOSED`. | Assignment = PUBLISHED. | | | | |
| **TC_AS_T_ST_006** | CLOSED → any → terminal block → 400 | Client → PUT `.../{id}/quiz` with `status: PUBLISHED`. | HTTP 400 Bad Request.<br>CLOSED is a terminal state. | Assignment = CLOSED. | | | | |

### 1.6 Delete Practice Task — `DELETE /api/v1/teacher/assignments/{id}`

| Test Case ID | Test Case Description | Test Case Procedure | Expected Results | Pre-conditions | Round 1 | Test date | Tester | Note |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **TC_AS_T_DL_001** | Delete DRAFT without submission → soft delete successfully | Client → DELETE `{id}`. | HTTP 200 OK.<br>`deleted_at` populated. | Assignment = DRAFT, `hasSubmissions = false`. | | | | |
| **TC_AS_T_DL_002** | Delete DRAFT with submission → deleted successfully, submissions become orphaned | Client → DELETE `{id}` knowing submissions exist. | HTTP 200 OK.<br>Deletes successfully but leaves orphaned submissions. | Assignment = DRAFT, `hasSubmissions = true`. | | | | Dev has BUG: missing guard logic. |
| **TC_AS_T_DL_003** | Delete PUBLISHED → blocked by requireDraft() → 400 | Client → DELETE `{id}`. | HTTP 400 Bad Request. | Assignment = PUBLISHED. | | | | |
| **TC_AS_T_DL_004** | Delete CLOSED → blocked by requireDraft() → 400 | Client → DELETE `{id}`. | HTTP 400 Bad Request. | Assignment = CLOSED. | | | | |
| **TC_AS_T_DL_005** | Teacher B deletes Teacher A's assignment → 400 | Client → DELETE `{id}` using Teacher B's token. | HTTP 400 Bad Request (ACCESS_DENIED). | Assignment belongs to Teacher A. | | | | |
| **TC_AS_T_DL_006** | Learner calls Teacher DELETE endpoint → 403 | Client → DELETE `{id}` with LEARNER token. | HTTP 403 Forbidden. | | | | | |

### 1.7 View Practice Task Results (Teacher) — `GET .../submissions`

| Test Case ID | Test Case Description | Test Case Procedure | Expected Results | Pre-conditions | Round 1 | Test date | Tester | Note |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **TC_AS_T_VS_001** | View all submissions list → returns correctly | Client → GET `/api/v1/teacher/assignments/{id}/submissions`. | HTTP 200 OK.<br>`data.content` lists submissions. | Assignment has subs. | | | | |
| **TC_AS_T_VS_002** | Assignment has no submissions → empty list | Client → GET `/api/v1/teacher/assignments/{id}/submissions`. | HTTP 200 OK.<br>`data.content` = `[]`. | Assignment has no subs. | | | | |
| **TC_AS_T_VS_003** | Assignment ID does not exist → 404 | Client → GET with invalid `{id}`. | HTTP 404 Not Found. | | | | | |
| **TC_AS_T_VS_004** | View 1 specific submission → correct fields | Client → GET `/api/v1/teacher/assignments/submissions/{subId}`. | HTTP 200 OK.<br>Returns full details. | Teacher can view Learner's submission. | | | | |
| **TC_AS_T_VS_005** | Submission does not exist → 404 | Client → GET invalid `{subId}`. | HTTP 404 Not Found. | | | | | |
| **TC_AS_T_VS_006** | Learner calls Teacher endpoint → 403 | Client → GET the submissions endpoint with LEARNER token. | HTTP 403 Forbidden. | | | | | |

---

## 2. LEARNER SIDE

### 2.1 View Assigned Practice Tasks — `GET /api/v1/learner/assignments` & `{id}`

| Test Case ID | Test Case Description | Test Case Procedure | Expected Results | Pre-conditions | Round 1 | Test date | Tester | Note |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **TC_AS_L_VL_001** | View list successfully | Client → GET `/api/v1/learner/assignments` with LEARNER token. | HTTP 200 OK.<br>Returns assignments mapped to Learner's classes. | Learner is in class. | | | | |
| **TC_AS_L_VL_002** | DRAFT assignment not visible to Learner | Client → GET `/api/v1/learner/assignments`. | HTTP 200 OK.<br>DRAFT assignments are excluded from the list. | | | | | |
| **TC_AS_L_VL_003** | CLOSED assignment remains visible (for history view) | Client → GET `/api/v1/learner/assignments`. | HTTP 200 OK.<br>CLOSED assignments are included. | | | | | |
| **TC_AS_L_VL_004** | Learner belongs to no class → empty list | Client → GET `/api/v1/learner/assignments`. | HTTP 200 OK.<br>Empty list returned. | Learner has no classes. | | | | |
| **TC_AS_L_VL_005** | Teacher calls Learner endpoint → 403 | Client → GET `/api/v1/learner/assignments` with TEACHER token. | HTTP 403 Forbidden. | | | | | |

### 2.2 Do Practice Task (startAssignment + submit)

| Test Case ID | Test Case Description | Test Case Procedure | Expected Results | Pre-conditions | Round 1 | Test date | Tester | Note |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **TC_AS_L_DO_001** | Start successfully → submission created with IN_PROGRESS | Client → POST `/api/v1/learner/assignments/{id}/start` | HTTP 200 OK.<br>Creates submission, `status = IN_PROGRESS`. | Assignment PUBLISHED, Learner eligible. | | | | |
| **TC_AS_L_DO_002** | Assignment not in PUBLISHED state → 400 | Client → POST `/api/v1/learner/assignments/{id}/start` on DRAFT | HTTP 400 Bad Request (ASSIGNMENT_NOT_PUBLISHED). | | | | | |
| **TC_AS_L_DO_003** | allowRetry = false, already has 1 graded attempt → 400 | Client → POST `/api/v1/learner/assignments/{id}/start`. | HTTP 400 Bad Request (NO_RETRY). | Learner already has 1 graded submission. | | | | |
| **TC_AS_L_DO_004** | allowRetry = true, attemptCount >= maxRetries → 400 | Client → POST `/api/v1/learner/assignments/{id}/start`. | HTTP 400 Bad Request (MAX_RETRIES). | `maxRetries` hit. | | | | |
| **TC_AS_L_DO_005** | Active submission IN_PROGRESS exists, start again → 400 | Client → POST `/api/v1/learner/assignments/{id}/start` while an attempt is active. | HTTP 400 Bad Request (ALREADY_IN_PROGRESS). | Has active `IN_PROGRESS` sub. | | | | |
| **TC_AS_L_DO_006** | Submit Quiz on time, correct answers → GRADED, score=100 | Client → POST `/api/v1/learner/assignments/submit` with correct answers. | HTTP 200 OK.<br>Status is `GRADED`, correct score. | Valid IN_PROGRESS. | | | | |
| **TC_AS_L_DO_007** | Submit Quiz with wrong answers → GRADED, score=0 | Client → POST `/api/v1/learner/assignments/submit` with incorrect answers. | HTTP 200 OK.<br>Status is `GRADED`, `score=0`. | Valid IN_PROGRESS. | | | | |
| **TC_AS_L_DO_008** | Submit Quiz past dueDate → OVERDUE | Client → POST `/api/v1/learner/assignments/submit` past due date. | HTTP 200 OK.<br>Status tracked as `OVERDUE`. | Now > dueDate. | | | | |
| **TC_AS_L_DO_009** | Submit Writing on time → SUBMITTED (awaiting grading) | Client → POST `/api/v1/learner/assignments/{id}/submit-writing` with `data` part. | HTTP 200 OK.<br>Status `SUBMITTED`. | Assignment type: WRITING. | | | | Multipart request. |
| **TC_AS_L_DO_010** | Submit Writing past dueDate → OVERDUE | Client → POST `/api/v1/learner/assignments/{id}/submit-writing` past due date. | HTTP 200 OK.<br>Status `OVERDUE`. | | | | | |
| **TC_AS_L_DO_011** | Submit Writing missing text/image → 400 | Client → POST `/api/v1/learner/assignments/{id}/submit-writing` with empty answer string and no images. | HTTP 400 Bad Request. | | | | | |
| **TC_AS_L_DO_012** | Teacher calls Learner submit endpoint → 403 | Client → POST submit endpoints with TEACHER. | HTTP 403 Forbidden. | | | | | |
| **TC_AS_L_DO_013** | Start assignment when CLOSED → 400 | Client → POST `/api/v1/learner/assignments/{id}/start` on CLOSED. | HTTP 400 Bad Request. | Assignment is CLOSED. | | | | |
| **TC_AS_L_DO_014** | Submit assignment when CLOSED → 400 | Client → POST `/api/v1/learner/assignments/submit` on CLOSED. | HTTP 400 Bad Request. | Assignment is CLOSED. | | | | |

### 2.3 View Practice Task Results (Learner) — `GET .../submissions`

| Test Case ID | Test Case Description | Test Case Procedure | Expected Results | Pre-conditions | Round 1 | Test date | Tester | Note |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **TC_AS_L_VS_001** | View own submission → successfully | Client → GET `/api/v1/learner/assignments/submissions/{subId}`. | HTTP 200 OK.<br>Returns details. | Submission belongs to Learner. | | | | |
| **TC_AS_L_VS_002** | Learner B views Learner A's submission → 400 | Client → GET `/api/v1/learner/assignments/submissions/{subId}` belonging to Learner A. | HTTP 400 Bad Request (ACCESS_DENIED). | Tokens don't match owner. | | | | |
| **TC_AS_L_VS_003** | Submission SUBMITTED (ungraded) → status=SUBMITTED, score=null | Client → GET `/api/v1/learner/assignments/submissions/{subId}`. | HTTP 200 OK.<br>`data.score` is null, `status: SUBMITTED`. | Submission not graded by Teacher yet. | | | | |
| **TC_AS_L_VS_004** | Submission OVERDUE → status=OVERDUE | Client → GET `/api/v1/learner/assignments/submissions/{subId}`. | HTTP 200 OK.<br>Status: `OVERDUE`. | Submission marked overdue. | | | | |
| **TC_AS_L_VS_005** | Submission does not exist → 404 | Client → GET invalid `{subId}`. | HTTP 404 Not Found. | | | | | |

---

## 3. SYSTEM INTEGRATION: EXTERNAL APIs (OPENAI / AZURE)

| Test Case ID | Test Case Description | Test Case Procedure | Expected Results | Pre-conditions | Round 1 | Test date | Tester | Note |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **TC_AS_AI_001** | AI Generate Questions (Automated Mock) | Client → POST `/api/v1/teacher/assignments/questions/ai-generate`<br>`@MockBean` configured to return predefined Question list. | HTTP 200 OK.<br>- `data` contains mock questions. | `@MockBean openaiService` injected returning predefined responses. | | | | Test via `@SpringBootTest` |
| **TC_AS_AI_002** | AI Generate Explanation (Automated Mock) | Client → POST `/api/v1/teacher/assignments/questions/{id}/ai-explain`<br>`@MockBean` configured to return predefined explanation. | HTTP 200 OK.<br>- `data.explanation` matches mock. | Question ID exists. | | | | Test via `@SpringBootTest` |
| **TC_AS_AI_003** | AI Generate Questions (validation) missing teacherPrompt → 400 | Client → POST `/api/v1/teacher/assignments/questions/ai-generate` missing prompt. | HTTP 400 Bad Request. | | | | |
| **TC_AS_AI_004** | Learner calls ai-generate endpoint → 403 | Client → POST `/api/v1/teacher/assignments/questions/ai-generate` with LEARNER token. | HTTP 403 Forbidden. | | | | |
| **TC_AS_AI_005** | AI Generate Questions (Postman Live) | Client → POST `/api/v1/teacher/assignments/questions/ai-generate` via Postman targeting local/dev with LIVE OpenAI key. | HTTP 200 OK.<br>- Realistic generated questions returned dynamically from OpenAI. | Valid OpenAI API Key configured in `application.properties`. | | | | **MANUAL TEST ONLY** |

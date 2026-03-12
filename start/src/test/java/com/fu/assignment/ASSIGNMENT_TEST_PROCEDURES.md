# Practice Task (Assignment) — Test Procedures
> Format: Tabular Test Scenarios
> Focus: Component Integration and System Integration Mocking
> Last updated: 2026-03-12

## 1. TEACHER SIDE

### 1.1 View Practice Task List — `GET /api/v1/teacher/assignments` & `/api/v1/teacher/assignments/class/{classId}`

| Test Case ID | Test Case Description | Test Case Procedure | Expected Results | Pre-conditions | Round 1 | Test date | Tester | Note |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **TC_AS_T_VL_001** | Lấy list thành công, trả đúng fields | Client → GET `/api/v1/teacher/assignments/class/{classId}` with TEACHER token.<br>System → return paginated list of assignments created for the class. | HTTP 200 OK.<br>`success = true`.<br>`data.content` covers all active assignments with valid base fields (id, title, type, dueDate). | Class exists and has active assignments. Teacher is creator/owner. | | | | |
| **TC_AS_T_VL_002** | Class không tồn tại → 404 | Client → GET with non-existent `{classId}`. | HTTP 404 Not Found. | | | | | |
| **TC_AS_T_VL_003** | Class tồn tại nhưng chưa có assignment nào → empty list | Client → GET `/api/v1/teacher/assignments/class/{classId}`. | HTTP 200 OK.<br>`data.content` is empty array `[]`. | Class exists. No assignments created. | | | | |
| **TC_AS_T_VL_004** | Teacher không phải owner của class → 403 | Client → GET `/api/v1/teacher/assignments/class/{classId}` with another Teacher's token. | HTTP 403 Forbidden. | Class exists. Caller is not the class owner. | | | | |
| **TC_AS_T_VL_005** | Learner gọi endpoint Teacher → 403 (role guard) | Client → GET `/api/v1/teacher/assignments` with LEARNER token. | HTTP 403 Forbidden. | Learner account exists. | | | | |

### 1.2 View Practice Task Info — `GET /api/v1/teacher/assignments/{id}`

| Test Case ID | Test Case Description | Test Case Procedure | Expected Results | Pre-conditions | Round 1 | Test date | Tester | Note |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **TC_AS_T_VI_001** | Xem thành công, response đúng fields | Client → GET `/api/v1/teacher/assignments/{id}` with TEACHER token. | HTTP 200 OK.<br>`success = true`. Contains all info and linked questions/prompts. | Assignment exists. Teacher is owner. | | | | |
| **TC_AS_T_VI_002** | Assignment không tồn tại → 404 | Client → GET with non-existent `{id}`. | HTTP 404 Not Found. | | | | | |
| **TC_AS_T_VI_003** | Assignment thuộc class khác (không phải của teacher này) → 403 | Client → GET `{id}` for an assignment in a class the teacher does not manage. | HTTP 403 Forbidden. | Assignment exists. | | | | |
| **TC_AS_T_VI_004** | Teacher B xem assignment của Teacher A → 403 | Client → GET `{id}` with Teacher B token for Teacher A's assignment. | HTTP 403 Forbidden. | Two teachers exist. | | | | |
| **TC_AS_T_VI_005** | Learner gọi endpoint Teacher → 403 | Client → GET `/api/v1/teacher/assignments/{id}` with LEARNER token. | HTTP 403 Forbidden. | Learner account exists. | | | | |

### 1.3 Create Practice Task — `POST /api/v1/teacher/assignments/class/{classId}/...`

| Test Case ID | Test Case Description | Test Case Procedure | Expected Results | Pre-conditions | Round 1 | Test date | Tester | Note |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **TC_AS_T_CR_001** | Tạo QUIZ thành công → status DRAFT | Client → POST `.../quiz` with valid data. | HTTP 200/201 OK.<br>`data.type = QUIZ`.<br>`data.status = DRAFT`. | Valid class. Logged in Teacher is owner. | | | | |
| **TC_AS_T_CR_002** | Tạo WRITING/TRANSLATION thành công → status DRAFT | Client → POST `.../writing` (and `.../translation`) with valid data. | HTTP 200/201 OK.<br>`data.type` covers Writing/Translation.<br>`data.status = DRAFT`. | Valid class. Logged in Teacher is owner. | | | | |
| **TC_AS_T_CR_003** | Thiếu field bắt buộc (title, dueDate null) → 400 | Client → POST without `title` or `dueDate`. | HTTP 400 Bad Request (Validation Error). | | | | | |
| **TC_AS_T_CR_004** | dueDate đã quá hạn khi tạo → 400 | Client → POST with `dueDate` set to past date. | HTTP 400 Bad Request. | | | | | |
| **TC_AS_T_CR_005** | classId không tồn tại → 404 | Client → POST to non-existent `classId`. | HTTP 404 Not Found. | | | | | |
| **TC_AS_T_CR_006** | allowRetry = true nhưng maxRetries không set → default = 1 | Client → POST with `allowRetry: true` and `maxRetries: null`. | HTTP 200 OK.<br>`data.maxRetries = 1`. | | | | | Verify DB behavior. |
| **TC_AS_T_CR_007** | Learner gọi endpoint Teacher → 403 | Client → POST `.../quiz` with LEARNER token. | HTTP 403 Forbidden. | | | | | |
| **TC_AS_T_CR_008** | Teacher B tạo assignment vào class của Teacher A → 403 | Client → POST to Teacher A's `classId` using Teacher B's token. | HTTP 403 Forbidden. | | | | | |

### 1.4 Update Practice Task — metadata & content — `PUT /api/v1/teacher/assignments/{id}/...`

| Test Case ID | Test Case Description | Test Case Procedure | Expected Results | Pre-conditions | Round 1 | Test date | Tester | Note |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **TC_AS_T_UP_001** | Update metadata thành công khi DRAFT | Client → PUT with new `title`/`description`. | HTTP 200 OK.<br>Assignment metadata updated. | Assignment = DRAFT. | | | | |
| **TC_AS_T_UP_002** | Update metadata thành công khi PUBLISHED | Client → PUT with new `title`/`description` (metadata not locked). | HTTP 200 OK.<br>Assignment metadata updated. | Assignment = PUBLISHED. | | | | |
| **TC_AS_T_UP_003** | Update content (câu hỏi) khi chưa có submission → thành công | Client → PUT `.../quiz` changing questions while no submissions exist. | HTTP 200 OK.<br>Questions updated. | `hasSubmissions = false`. | | | | |
| **TC_AS_T_UP_004** | Update content khi hasSubmissions = true → 409/400 (block) | Client → PUT changing questions when submissions exist. | HTTP 409/400.<br>Block modifications to active task content. | `hasSubmissions = true`. | | | | |
| **TC_AS_T_UP_005** | Update dueDate — rút ngắn khi hasSubmissions = true → block | Client → PUT shrinking `dueDate` while submissions exist. | HTTP 409/400.<br>Block shrinking timeline. | `hasSubmissions = true`. | | | | |
| **TC_AS_T_UP_006** | Update dueDate — gia hạn khi hasSubmissions = true → thành công | Client → PUT extending `dueDate` while submissions exist. | HTTP 200 OK.<br>Extension allowed. | `hasSubmissions = true`. | | | | |
| **TC_AS_T_UP_007** | Update passingScore — tăng khi hasSubmissions = true → block | Client → PUT increasing `passingScore` while submissions exist. | HTTP 409/400 block. | `hasSubmissions = true`. | | | | |

### 1.5 Update Practice Task — status transitions — `PATCH .../status` (or endpoint changing status)

| Test Case ID | Test Case Description | Test Case Procedure | Expected Results | Pre-conditions | Round 1 | Test date | Tester | Note |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **TC_AS_T_ST_001** | DRAFT → PUBLISHED thành công | Client → switch status to PUBLISHED. | HTTP 200 OK.<br>`data.status = PUBLISHED`. | Assignment has content & valid `dueDate`. | | | | |
| **TC_AS_T_ST_002** | DRAFT → PUBLISHED thiếu content → 400 | Client → switch status to PUBLISHED for empty Quiz. | HTTP 400 Bad Request. | Quiz `questions` size = 0. | | | | |
| **TC_AS_T_ST_003** | DRAFT → PUBLISHED dueDate null/quá hạn → 400 | Client → switch status to PUBLISHED with invalid `dueDate`. | HTTP 400 Bad Request. | `dueDate` < Now. | | | | |
| **TC_AS_T_ST_004** | PUBLISHED → DRAFT → block cứng → 409 | Client → downgrade PUBLISHED back to DRAFT. | HTTP 409/400.<br>Transitions backward generally prevented. | Assignment = PUBLISHED. | | | | |
| **TC_AS_T_ST_005** | PUBLISHED → CLOSED → thành công | Client → switch PUBLISHED to CLOSED. | HTTP 200 OK.<br>`data.status = CLOSED`. | Assignment = PUBLISHED. | | | | |
| **TC_AS_T_ST_006** | CLOSED → bất kỳ → block terminal → 409 | Client → switch CLOSED to anything else (DRAFT/PUBLISHED). | HTTP 409.<br>CLOSED is a terminal state. | Assignment = CLOSED. | | | | |

### 1.6 Delete Practice Task — `DELETE /api/v1/teacher/assignments/{id}`

| Test Case ID | Test Case Description | Test Case Procedure | Expected Results | Pre-conditions | Round 1 | Test date | Tester | Note |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **TC_AS_T_DL_001** | Xóa DRAFT chưa có submission → soft delete thành công | Client → DELETE `{id}`. | HTTP 200 OK.<br>`deleted_at` populated. | Assignment = DRAFT, `hasSubmissions = false`. | | | | |
| **TC_AS_T_DL_002** | Xóa DRAFT đã có submission → expected-to-fail (block) | Client → DELETE `{id}` knowing submissions exist. | HTTP 409 Conflict.<br>(Check if logic blocks this scenario correctly). | Assignment = DRAFT, `hasSubmissions = true`. | | | | Bug test according to user request. |
| **TC_AS_T_DL_003** | Xóa PUBLISHED → block bởi requireDraft() → 409 | Client → DELETE `{id}`. | HTTP 400/409.<br>Blocks deleting active assignment. | Assignment = PUBLISHED. | | | | |
| **TC_AS_T_DL_004** | Xóa CLOSED → block bởi requireDraft() → 409 | Client → DELETE `{id}`. | HTTP 400/409.<br>Blocks deleting concluded assignment. | Assignment = CLOSED. | | | | |
| **TC_AS_T_DL_005** | Teacher B xóa assignment của Teacher A → 403 | Client → DELETE `{id}` using Teacher B's token. | HTTP 403 Forbidden. | Assignment belongs to Teacher A. | | | | |
| **TC_AS_T_DL_006** | Learner gọi endpoint Teacher DELETE → 403 | Client → DELETE `{id}` with LEARNER token. | HTTP 403 Forbidden. | | | | | |

### 1.7 View Practice Task Results (Teacher) — `GET .../submissions`

| Test Case ID | Test Case Description | Test Case Procedure | Expected Results | Pre-conditions | Round 1 | Test date | Tester | Note |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **TC_AS_T_VS_001** | Xem list tất cả submissions → trả đúng | Client → GET `/api/v1/teacher/assignments/{id}/submissions`. | HTTP 200 OK.<br>`data.content` lists submissions. | Assignment has subs. | | | | |
| **TC_AS_T_VS_002** | Assignment không tồn tại → 404 | Client → GET with invalid `{id}`. | HTTP 404 Not Found. | | | | | |
| **TC_AS_T_VS_003** | Assignment chưa có submission nào → empty list | Client → GET `/api/v1/teacher/assignments/{id}/submissions`. | HTTP 200 OK.<br>`data.content` = `[]`. | Assignment has no subs. | | | | |
| **TC_AS_T_VS_004** | Xem 1 submission cụ thể → đúng fields (không check ownership) | Client → GET `/api/v1/teacher/assignments/submissions/{subId}`. | HTTP 200 OK.<br>Returns full details (answers, score, feedback). | Teacher can view Learner's submission. | | | | |
| **TC_AS_T_VS_005** | Submission không tồn tại → 404 | Client → GET invalid `{subId}`. | HTTP 404 Not Found. | | | | | |
| **TC_AS_T_VS_006** | Learner gọi endpoint Teacher → 403 | Client → GET the submissions endpoint with LEARNER token. | HTTP 403 Forbidden. | | | | | |

---

## 2. LEARNER SIDE

### 2.1 View Assigned Practice Tasks — `GET /api/v1/learner/assignments` & `{id}`

| Test Case ID | Test Case Description | Test Case Procedure | Expected Results | Pre-conditions | Round 1 | Test date | Tester | Note |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **TC_AS_L_VL_001** | Xem list thành công, chỉ thấy assignment của class mình | Client → GET `/api/v1/learner/assignments` with LEARNER token. | HTTP 200 OK.<br>Returns only assignments mapped to Learner's classes. | Learner is in class. | | | | |
| **TC_AS_L_VL_002** | Assignment DRAFT không hiện với Learner | Client → GET `/api/v1/learner/assignments`. | HTTP 200 OK.<br>DRAFT assignments are excluded from the list. | | | | | |
| **TC_AS_L_VL_003** | Assignment CLOSED vẫn hiện (để xem lịch sử) | Client → GET `/api/v1/learner/assignments`. | HTTP 200 OK.<br>CLOSED assignments are included. | | | | | |
| **TC_AS_L_VL_004** | Learner không thuộc class nào → empty list | Client → GET `/api/v1/learner/assignments`. | HTTP 200 OK.<br>Empty list returned. | Learner has no classes. | | | | |
| **TC_AS_L_VL_005** | Teacher gọi endpoint Learner → 403 | Client → GET `/api/v1/learner/assignments` with TEACHER token. | HTTP 403 Forbidden. | | | | | |

### 2.2 Do Practice Task (startAssignment + submit)

| Test Case ID | Test Case Description | Test Case Procedure | Expected Results | Pre-conditions | Round 1 | Test date | Tester | Note |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **TC_AS_L_DO_001** | Start thành công → submission tạo với IN_PROGRESS | Client → POST `/api/v1/learner/assignments/{id}/start` | HTTP 200 OK.<br>Creates submission, `status = IN_PROGRESS`. | Assignment PUBLISHED, Learner eligible. | | | | |
| **TC_AS_L_DO_002** | Assignment không ở PUBLISHED → 400 | Client → POST `.../start` on DRAFT | HTTP 400 Bad request (Cannot start un-published). | | | | | |
| **TC_AS_L_DO_003** | Assignment quá dueDate + lateSubmissionAllowed = false → block | Client → POST `.../start` after due date. | HTTP 400/403 Blocked. | `lateSubmissionAllowed = false`, Now > dueDate. | | | | |
| **TC_AS_L_DO_004** | allowRetry = false, đã có 1 attempt → NO_RETRY → 400 | Client → POST `.../start`. | HTTP 400 error: NO_RETRY. | Learner already has 1 submission. | | | | |
| **TC_AS_L_DO_005** | allowRetry = true, attemptCount >= maxRetries → MAX_RETRIES → 400 | Client → POST `.../start`. | HTTP 400 error: MAX_RETRIES. | `maxRetries` hit. | | | | |
| **TC_AS_L_DO_006** | Đang có submission IN_PROGRESS, start lại → ALREADY_IN_PROGRESS | Client → POST `.../start` while an attempt is active. | HTTP 400 error: ALREADY_IN_PROGRESS. | Has active `IN_PROGRESS` sub. | | | | |
| **TC_AS_L_DO_007** | Submit Quiz đúng hạn → GRADED (auto-score) | Client → POST `/api/v1/learner/assignments/submit` with answers. | HTTP 200 OK.<br>Status is `GRADED`, correct score. | Valid IN_PROGRESS. | | | | |
| **TC_AS_L_DO_008** | Submit Quiz quá dueDate + lateAllowed = false → OVERDUE | Client → POST submit past due date. | HTTP 200 OK / 400 (if blocked by policy).<br>Status tracked as `OVERDUE` or rejected. | `lateSubmissionAllowed = false`, Now > dueDate. | | | | Depending on system rule handling. |
| **TC_AS_L_DO_009** | Submit Writing đúng hạn → SUBMITTED (chờ chấm) | Client → POST `../submit-writing` with image payload. | HTTP 200 OK.<br>Status `SUBMITTED`, not graded yet. | Assignment type: WRITING. | | | | |
| **TC_AS_L_DO_010** | Submit Writing quá dueDate + lateAllowed = false → OVERDUE | Client → POST `../submit-writing` past due date. | HTTP 200 OK / 400.<br>Status `OVERDUE` or rejected. | | | | | |
| **TC_AS_L_DO_011** | Teacher gọi endpoint Learner submit → 403 | Client → POST submit endpoints with TEACHER. | HTTP 403 Forbidden. | | | | | |

### 2.3 View Practice Task Results (Learner) — `GET .../submissions`

| Test Case ID | Test Case Description | Test Case Procedure | Expected Results | Pre-conditions | Round 1 | Test date | Tester | Note |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **TC_AS_L_VS_001** | Xem submission của chính mình → thành công | Client → GET `/api/v1/learner/assignments/submissions/{subId}`. | HTTP 200 OK.<br>Returns `GRADED`/`SUBMITTED` details. | Submission belongs to Learner. | | | | |
| **TC_AS_L_VS_002** | Xem submission của Learner khác → ACCESS_DENIED → 403 | Client → GET `{subId}` belonging to User B. | HTTP 403 Forbidden. | Tokens don't match owner. | | | | |
| **TC_AS_L_VS_003** | Submission chưa GRADED (đang SUBMITTED) → đúng status, no score | Client → GET `{subId}`. | HTTP 200 OK.<br>`data.score` is null, `status: SUBMITTED`. | Submission not graded by Teacher yet. | | | | |
| **TC_AS_L_VS_004** | Submission OVERDUE → trả về đúng status | Client → GET `{subId}`. | HTTP 200 OK.<br>Status: `OVERDUE`. | Submission marked overdue. | | | | |
| **TC_AS_L_VS_005** | Submission không tồn tại → 404 | Client → GET invalid `{subId}`. | HTTP 404 Not Found. | | | | | |

---

## 3. SYSTEM INTEGRATION: EXTERNAL APIs (OPENAI / AZURE)

| Test Case ID | Test Case Description | Test Case Procedure | Expected Results | Pre-conditions | Round 1 | Test date | Tester | Note |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **TC_AS_AI_001** | AI Generate Questions (Automated Mock) | Client → POST `/api/v1/teacher/assignments/questions/ai-generate`<br>`@MockBean` configured to return predefined Question list.<br>System → returns mocked generation. | HTTP 200 OK.<br>- `data` contains mock questions. | `@MockBean openaiService` injected returning predefined responses. | | | | Test via `@SpringBootTest` |
| **TC_AS_AI_002** | AI Generate Explanation (Automated Mock) | Client → POST `/api/v1/teacher/assignments/questions/{id}/ai-explain`<br>`@MockBean` configured to return predefined explanation.<br>System → updates question explanation. | HTTP 200 OK.<br>- `data.explanation` matches mock. | Question ID exists. | | | | Test via `@SpringBootTest` |
| **TC_AS_AI_003** | AI Generate Questions (Postman Live) | Client → POST `/api/v1/teacher/assignments/questions/ai-generate` via Postman targeting local/dev with LIVE OpenAI key.<br>Verify real API interactions. | HTTP 200 OK.<br>- Realistic generated questions returned dynamically from OpenAI. | Valid OpenAI API Key configured in `application.properties`. | | | | **MANUAL TEST ONLY** |

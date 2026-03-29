# Learning Content Management Integration Test Script

## Overview
This document contains detailed test scripts for all learning content management API endpoints (lessons, vocabulary, grammar points, curriculum imports) in Satori Learning Support Application backend.

## Test Environment Setup

### Pre-conditions
- Test DB running via `application-test.properties`
- PostgreSQL `satori_test` database created and initialized
- Docker environment up and running
- AI services (ImportOrchestrator, EmbeddingService) mocked
- Cloudinary service mocked for file uploads
- Email service mocked

---

## Section 1 — View Lesson List Tests (VL)

### Test Case ID: TC_LCM_VL_001
**Test Case Description:** View lesson list successfully for a specific course (Detailed expected results)

**Pre-conditions:**
- Content Manager accessToken is available (from prior login)
- Course created via saveCourse()
- One lesson created for the course via saveLesson()

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/lessons?courseId={courseId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves CONTENT_MANAGER identity.
3. ManagerLessonController forwards the request to LessonService.
4. LessonService retrieves lessons for the course via LessonRepository (excluding deleted records).
5. LessonRepository queries the database and returns the list of lessons.
6. System returns the lesson list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- message exists
- data.content.length() = 1
- data.content[0].id exists (UUID)
- data.content[0].title = "Lesson 1"
- data.content[0].lessonType = "VOCABULARY"
- data.content[0].status = "DRAFT"
- data.content[0].orderIndex = 0
- data.totalElements = 1
- data.totalPages = 1
- data.pageNumber = 0
- data.pageSize = 10
- data.last = true

---

### Test Case ID: TC_LCM_VL_002
**Test Case Description:** Search lessons by keyword in title

**Pre-conditions:**
- Content Manager accessToken is available (from prior login)
- Course created
- Lessons "Apple Lesson" and "Banana Lesson" created for the course

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/lessons?courseId={courseId}&keyword=Apple with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves CONTENT_MANAGER identity.
3. ManagerLessonController forwards the request to LessonService with keyword filter.
4. LessonService searches lessons by keyword in title via LessonRepository.
5. LessonRepository queries the database and returns matching records.
6. System returns filtered lesson list.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content.length() = 1
- data.content[0].title = "Apple Lesson"

---

### Test Case ID: TC_LCM_VL_003
**Test Case Description:** Filter lessons by status (PUBLISHED)

**Pre-conditions:**
- Content Manager accessToken is available (from prior login)
- Course created
- One DRAFT lesson and one PUBLISHED lesson created for the course

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/lessons?courseId={courseId}&status=PUBLISHED with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves CONTENT_MANAGER identity.
3. ManagerLessonController forwards the request to LessonService with status filter.
4. LessonService filters lessons by status via LessonRepository.
5. LessonRepository queries the database with status constraint and returns results.
6. System returns filtered lesson list.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- All returned lessons have status = "PUBLISHED"

---

### Test Case ID: TC_LCM_VL_004
**Test Case Description:** Pagination works correctly (page and size)

**Pre-conditions:**
- Content Manager accessToken is available (from prior login)
- Course created
- 15 lessons created for the course

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/lessons?courseId={courseId}&page=1&size=10 with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves CONTENT_MANAGER identity.
3. ManagerLessonController forwards the request to LessonService with pagination parameters.
4. LessonService retrieves paginated lessons via LessonRepository.
5. LessonRepository performs paginated database query.
6. System returns paginated results.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content.length() = 5
- data.pageNumber = 1
- data.pageSize = 10

---

### Test Case ID: TC_LCM_VL_005
**Test Case Description:** Visibility logic based on curriculum import status

**Pre-conditions:**
- Content Manager accessToken is available (from prior login)
- Course created
- One PENDING_APPROVAL import with a lesson
- One COMPLETED import with a lesson

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/lessons?courseId={courseId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves CONTENT_MANAGER identity.
3. ManagerLessonController forwards the request to LessonService.
4. LessonService filters lessons by checking associated CurriculumImport status.
5. LessonRepository returns only lessons with COMPLETED or null import status.
6. System returns lesson list.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- Returned lessons match approved visibility rules

---

### Test Case ID: TC_LCM_VL_006
**Test Case Description:** No result found for course with no lessons

**Pre-conditions:**
- Content Manager accessToken is available (from prior login)
- Course created but has no lessons

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/lessons?courseId={courseId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves CONTENT_MANAGER identity.
3. ManagerLessonController forwards the request to LessonService.
4. LessonService attempts to retrieve lessons via LessonRepository.
5. LessonRepository returns an empty list.
6. System returns empty list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content.length() = 0

---

## Section 2 — View Lesson Detail Tests (VD)

### Test Case ID: TC_LCM_VD_001
**Test Case Description:** View lesson detail successfully (Detailed expected results)

**Pre-conditions:**
- Content Manager accessToken is available
- Lesson exists in the database

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/lessons/{id} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves CONTENT_MANAGER identity.
3. ManagerLessonController forwards the request to LessonService.
4. LessonService retrieves lesson metadata and associated content via LessonRepository.
5. LessonRepository queries the database and returns the lesson object.
6. System returns the lesson details response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.id = requested lesson UUID
- data.courseId exists
- data.title = "Detail Lesson"
- data.lessonType exists ("VOCABULARY", "GRAMMAR", etc.)
- data.status exists ("DRAFT", "PUBLISHED", "ARCHIVED")
- data.orderIndex exists
- data.createdAt exists
- data.updatedAt exists
- data.vocabulary exists (if type is VOCABULARY)
- data.grammarPoints exists (if type is GRAMMAR)

---

### Test Case ID: TC_LCM_VD_002
**Test Case Description:** Lesson not found with non-existent UUID

**Pre-conditions:**
- Content Manager accessToken is available
- No lesson exists with the provided UUID

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/lessons/{randomId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves CONTENT_MANAGER identity.
3. ManagerLessonController forwards the request to LessonService.
4. LessonService attempts to find lesson via LessonRepository.
5. LessonRepository returns empty (null).
6. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND
Response JSON:
- success = false
- code = "RESOURCE_NOT_FOUND"

---

## Section 3 — Import Curriculum Tests (IM)

### Test Case ID: TC_LCM_IM_001
**Test Case Description:** Create import job successfully with valid PDF (Detailed expected results)

**Pre-conditions:**
- Content Manager accessToken is available
- Valid PDF file ready for multipart upload

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/curriculum-imports with Authorization: Bearer {token} and multipart file.
2. AuthFilter validates token and resolves CONTENT_MANAGER identity.
3. ManagerCurriculumImportController forwards the request to ImportOrchestrator.
4. ImportOrchestrator validates file, saves basic record via CurriculumImportRepository, and starts async processing.
5. System returns the accepted response.

**Expected Results:** HTTP 202 ACCEPTED
Response JSON:
- success = true
- message = "Import accepted and queued for processing"
- data.id exists (import UUID)
- data.originalFilename = "test.pdf"
- data.status = "PENDING"
- data.fileSizeBytes exists
- data.createdBy exists
- data.createdAt exists

---

### Test Case ID: TC_LCM_IM_002
**Test Case Description:** Reject invalid file type (txt)

**Pre-conditions:**
- Content Manager accessToken available

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/curriculum-imports with Authorization: Bearer {token} and a .txt file.
2. AuthFilter validates token and resolves CONTENT_MANAGER identity.
3. ManagerCurriculumImportController detects unsupported extension before forwarding to service.
4. System returns the invalid file error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "INVALID_FILE"
- message contains "Invalid file type" or similar

---

### Test Case ID: TC_LCM_IM_003
**Test Case Description:** Reject file exceeding size limit (100MB)

**Pre-conditions:**
- Content Manager accessToken available

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/curriculum-imports with Authorization: Bearer {token} and a 101MB file.
2. AuthFilter validates token and resolves CONTENT_MANAGER identity.
3. ManagerCurriculumImportController detects file size violation.
4. System returns the invalid file error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "INVALID_FILE"

---

## Section 4 — Import History / Detail Tests (IH)

### Test Case ID: TC_LCM_IH_001
**Test Case Description:** View import history list (Detailed expected results)

**Pre-conditions:**
- Content Manager accessToken available
- One or more curriculum imports exist

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/curriculum-imports with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves CONTENT_MANAGER identity.
3. ManagerCurriculumImportController forwards the request to CurriculumImportService.
4. CurriculumImportService retrieves imports via CurriculumImportRepository.
5. CurriculumImportRepository queries the database and returns paginated data.
6. System returns paginated history response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content is an array
- data.content[0].id exists
- data.content[0].originalFilename exists
- data.content[0].status = "COMPLETED"
- data.content[0].createdBy exists
- data.content[0].createdAt exists
- data.totalElements exists
- data.totalPages exists
- data.pageNumber = 0
- data.pageSize = 10

---

### Test Case ID: TC_LCM_IH_002
**Test Case Description:** View import detail successfully (Detailed expected results)

**Pre-conditions:**
- Content Manager accessToken available
- Specific import ID exists

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/curriculum-imports/{id} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves CONTENT_MANAGER identity.
3. ManagerCurriculumImportController forwards the request to CurriculumImportService.
4. CurriculumImportService retrieves import by ID via CurriculumImportRepository.
5. CurriculumImportRepository returns the import record.
6. System returns the detail response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.id = requested UUID
- data.originalFilename = "test.pdf"
- data.fileUrl exists
- data.status = "COMPLETED"
- data.fileSizeBytes exists
- data.createdBy exists
- data.createdAt exists
- data.updatedAt exists

---

### Test Case ID: TC_LCM_IH_003
**Test Case Description:** Import not found

**Pre-conditions:**
- Content Manager accessToken available

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/curriculum-imports/{randomId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves CONTENT_MANAGER identity.
3. ManagerCurriculumImportController forwards the request to CurriculumImportService.
4. CurriculumImportService attempts to find import via CurriculumImportRepository.
5. CurriculumImportRepository returns empty.
6. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND
Response JSON:
- success = false
- code = "RESOURCE_NOT_FOUND"

---

## Section 5 — Approve / Rollback Import Tests (AI)

### Test Case ID: TC_LCM_AI_001
**Test Case Description:** Approve import successfully (Detailed expected results)

**Pre-conditions:**
- Content Manager accessToken available
- Import exists with status PENDING_APPROVAL

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/curriculum-imports/{id}/approve with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves CONTENT_MANAGER identity.
3. ManagerCurriculumImportController forwards the request to CurriculumImportService.
4. CurriculumImportService validates status and updates to COMPLETED via CurriculumImportRepository.
5. System returns the success response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.id = requested UUID
- data.status = "COMPLETED"
- DB Check: CurriculumImport.status is COMPLETED

---

### Test Case ID: TC_LCM_AI_002
**Test Case Description:** Reject approve request for already COMPLETED import

**Pre-conditions:**
- Content Manager accessToken available
- Import status is already COMPLETED

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/curriculum-imports/{id}/approve with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves CONTENT_MANAGER identity.
3. ManagerCurriculumImportController forwards the request to CurriculumImportService.
4. CurriculumImportService detects invalid state transition (already COMPLETED).
5. System returns the bad request error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "BUSINESS_ERROR" (or specific status transition error code)

---

### Test Case ID: TC_LCM_AI_003
**Test Case Description:** Rollback import successfully (Detailed expected results)

**Pre-conditions:**
- Content Manager accessToken available
- Import status is COMPLETED
- Lesson(s) associated with this import exist

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/curriculum-imports/{id}/rollback with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves CONTENT_MANAGER identity.
3. ManagerCurriculumImportController forwards the request to CurriculumImportService.
4. CurriculumImportService deletes (soft delete) associated lessons via LessonRepository.
5. CurriculumImportService updates import status to ROLLED_BACK via CurriculumImportRepository.
6. System returns the success response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.id = requested UUID
- data.status = "ROLLED_BACK"
- DB Check: Status is ROLLED_BACK, associated lessons are deleted (deletedAt is not null)

---

### Test Case ID: TC_LCM_AI_004
**Test Case Description:** Rollback non-existent import ID

**Pre-conditions:**
- Content Manager accessToken available

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/curriculum-imports/{randomId}/rollback with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves CONTENT_MANAGER identity.
3. ManagerCurriculumImportController forwards the request to CurriculumImportService.
4. CurriculumImportService attempts to find import via CurriculumImportRepository.
5. CurriculumImportRepository returns empty.
6. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND
Response JSON:
- success = false
- code = "RESOURCE_NOT_FOUND"

---

## Section 6 — Lesson Lifecycle Tests (LL)

### Test Case ID: TC_LCM_LL_001
**Test Case Description:** Publish DRAFT lesson successfully (Detailed expected results)

**Pre-conditions:**
- Content Manager accessToken available
- Lesson exists with status DRAFT

**Test Case Procedure:**
1. Client sends PATCH /api/v1/manager/lessons/{id}/publish with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves CONTENT_MANAGER identity.
3. ManagerLessonController forwards the request to LessonService.
4. LessonService validates current status and updates to PUBLISHED via LessonRepository.
5. System returns the success response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.id = requested UUID
- data.status = "PUBLISHED"
- data.updatedAt exists
- DB Check: Lesson status is PUBLISHED

---

### Test Case ID: TC_LCM_LL_002
**Test Case Description:** Reject publish request for already PUBLISHED lesson

**Pre-conditions:**
- Content Manager accessToken available
- Lesson status is PUBLISHED

**Test Case Procedure:**
1. Client sends PATCH /api/v1/manager/lessons/{id}/publish with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves CONTENT_MANAGER identity.
3. ManagerLessonController forwards the request to LessonService.
4. LessonService detects invalid status (already PUBLISHED).
5. System returns the bad request error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "BUSINESS_ERROR" (or specific status error code)

---

### Test Case ID: TC_LCM_LL_003
**Test Case Description:** Archive (Unpublish) PUBLISHED lesson successfully (Detailed expected results)

**Pre-conditions:**
- Content Manager accessToken available
- Lesson status is PUBLISHED

**Test Case Procedure:**
1. Client sends PATCH /api/v1/manager/lessons/{id}/archive with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves CONTENT_MANAGER identity.
3. ManagerLessonController forwards the request to LessonService.
4. LessonService updates lesson status to ARCHIVED via LessonRepository.
5. System returns the success response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.id = requested UUID
- data.status = "ARCHIVED"
- DB Check: Lesson status is ARCHIVED

---

### Test Case ID: TC_LCM_LL_004
**Test Case Description:** Reject archive request for DRAFT lesson

**Pre-conditions:**
- Content Manager accessToken available
- Lesson status is DRAFT

**Test Case Procedure:**
1. Client sends PATCH /api/v1/manager/lessons/{id}/archive with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves CONTENT_MANAGER identity.
3. ManagerLessonController forwards the request to LessonService.
4. LessonService detects invalid state transition (DRAFT cannot be archived).
5. System returns the bad request error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "BUSINESS_ERROR"

---

## Section 7 — Lesson CRUD Tests (LS)

### Test Case ID: TC_LCM_LS_001
**Test Case Description:** Create lesson successfully (Detailed expected results)

**Pre-conditions:**
- Content Manager accessToken available
- Course exists

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/lessons with Authorization: Bearer {token} and JSON body: courseId, title, lessonType.
2. AuthFilter validates token and resolves CONTENT_MANAGER identity.
3. ManagerLessonController forwards the request to LessonService.
4. LessonService creates a new Lesson entity and saves it via LessonRepository.
5. System returns the created response.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.id exists (UUID)
- data.courseId = requested courseId
- data.title = requested title
- data.lessonType = requested type
- data.status = "DRAFT"
- data.orderIndex exists
- data.createdAt exists
- data.updatedAt exists

---

### Test Case ID: TC_LCM_LS_002
**Test Case Description:** Create lesson with missing required fields

**Pre-conditions:**
- Content Manager accessToken available

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/lessons with Authorization: Bearer {token} and invalid JSON (missing courseId).
2. AuthFilter validates token and resolves CONTENT_MANAGER identity.
3. Spring validation detects missing required fields in the request body.
4. System returns the validation error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "VALIDATION_ERROR"
- message = "Validation failed"
- data (Map of field errors) exists

---

### Test Case ID: TC_LCM_LS_003
**Test Case Description:** Update lesson title successfully (Detailed expected results)

**Pre-conditions:**
- Content Manager accessToken available
- Lesson exists

**Test Case Procedure:**
1. Client sends PUT /api/v1/manager/lessons/{id} with Authorization: Bearer {token} and JSON: {"title": "Updated Name"}.
2. AuthFilter validates token and resolves CONTENT_MANAGER identity.
3. ManagerLessonController forwards the request to LessonService.
4. LessonService retrieves lesson, updates fields, and saves via LessonRepository.
5. System returns the success response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.id = requested UUID
- data.title = "Updated Name"
- data.updatedAt > data.createdAt

---

### Test Case ID: TC_LCM_LS_004
**Test Case Description:** Delete lesson (soft delete)

**Pre-conditions:**
- Content Manager accessToken available
- Lesson exists

**Test Case Procedure:**
1. Client sends DELETE /api/v1/manager/lessons/{id} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves CONTENT_MANAGER identity.
3. ManagerLessonController forwards the request to LessonService.
4. LessonService performs soft delete by setting deletedAt via LessonRepository.
5. System returns the success response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- DB Check: Lesson is not found by standard query (deletedAt is set)

---

## Section 8 — Vocabulary CRUD Tests (VO)

### Test Case ID: TC_LCM_VO_001
**Test Case Description:** Create vocabulary successfully (Detailed expected results)

**Pre-conditions:**
- Content Manager accessToken available

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/vocabulary with Authorization: Bearer {token} and JSON: japaneseText, jlptLevel, meaningVi.
2. AuthFilter validates token and resolves CONTENT_MANAGER identity.
3. ManagerVocabularyController forwards the request to VocabularyService.
4. VocabularyService saves new record via VocabularyRepository.
5. System returns the created response.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.id exists
- data.japaneseText = requested text
- data.jlptLevel = requested level
- data.meaningVi = requested meaning
- data.createdAt exists

---

### Test Case ID: TC_LCM_VO_002
**Test Case Description:** Update vocabulary successfully (Detailed expected results)

**Pre-conditions:**
- Content Manager accessToken available
- Vocabulary record exists

**Test Case Procedure:**
1. Client sends PUT /api/v1/manager/vocabulary/{id} with Authorization: Bearer {token} and updated fields.
2. AuthFilter validates token and resolves CONTENT_MANAGER identity.
3. ManagerVocabularyController forwards the request to VocabularyService.
4. VocabularyService updates record via VocabularyRepository.
5. System returns the success response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.id = requested id
- data.meaningVi matches update
- data.updatedAt exists

---

### Test Case ID: TC_LCM_VO_003
**Test Case Description:** Delete vocabulary

**Pre-conditions:**
- Content Manager accessToken available
- Vocabulary record exists

**Test Case Procedure:**
1. Client sends DELETE /api/v1/manager/vocabulary/{id} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves CONTENT_MANAGER identity.
3. ManagerVocabularyController forwards the request to VocabularyService.
4. VocabularyService deletes record (soft delete) via VocabularyRepository.
5. System returns the success response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- DB Check: Vocabulary record soft-deleted (deletedAt IS NOT NULL)

---

## Section 9 — Grammar Point CRUD Tests (GR)

### Test Case ID: TC_LCM_GR_001
**Test Case Description:** Create grammar point successfully (Detailed expected results)

**Pre-conditions:**
- Content Manager accessToken available

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/grammar-points with Authorization: Bearer {token} and JSON: pattern, jlptLevel, meaningVi.
2. AuthFilter validates token and resolves CONTENT_MANAGER identity.
3. ManagerGrammarController forwards the request to GrammarPointService.
4. GrammarPointService saves new record via GrammarPointRepository.
5. System returns the created response.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.id exists
- data.pattern = requested pattern
- data.jlptLevel = requested level
- data.meaningVi = requested meaning
- data.createdAt exists

---

### Test Case ID: TC_LCM_GR_002
**Test Case Description:** Update grammar point successfully (Detailed expected results)

**Pre-conditions:**
- Content Manager accessToken available
- Grammar point record exists

**Test Case Procedure:**
1. Client sends PUT /api/v1/manager/grammar-points/{id} with Authorization: Bearer {token} and updated field.
2. AuthFilter validates token and resolves CONTENT_MANAGER identity.
3. ManagerGrammarController forwards the request to GrammarPointService.
4. GrammarPointService updates record via GrammarPointRepository.
5. System returns the success response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.id = requested id
- data.meaningVi matches update
- data.updatedAt exists

---

### Test Case ID: TC_LCM_GR_003
**Test Case Description:** Delete grammar point

**Pre-conditions:**
- Content Manager accessToken available
- Grammar point record exists

**Test Case Procedure:**
1. Client sends DELETE /api/v1/manager/grammar-points/{id} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves CONTENT_MANAGER identity.
3. ManagerGrammarController forwards the request to GrammarPointService.
4. GrammarPointService deletes record (soft delete) via GrammarPointRepository.
5. System returns the success response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- DB Check: Grammar point soft-deleted (deletedAt IS NOT NULL)

---

## Section 10 — Security & Role-Based Access (SR)

### Test Case ID: TC_LCM_SR_001
**Test Case Description:** Access manager endpoint without token → 401 UNAUTHORIZED

**Pre-conditions:**
- No accessToken provided

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/lessons?courseId={id} without Authorization header.
2. AuthFilter detects missing token.
3. System returns unauthorized response via AuthenticationEntryPoint.

**Expected Results:** HTTP 401 UNAUTHORIZED
Response JSON:
- success = false
- code = "UNAUTHORIZED"
- message = "Vui lòng đăng nhập để tiếp tục"

---

### Test Case ID: TC_LCM_SR_002
**Test Case Description:** Access manager endpoint with invalid/expired token → 401 UNAUTHORIZED

**Pre-conditions:**
- Invalid or expired accessToken

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/lessons?courseId={id} with Authorization: Bearer invalid_token.
2. AuthFilter fails to validate token.
3. System returns unauthorized response.

**Expected Results:** HTTP 401 UNAUTHORIZED
Response JSON:
- success = false
- code = "UNAUTHORIZED"

---

### Test Case ID: TC_LCM_SR_003
**Test Case Description:** Access manager endpoint with insufficient role (LEARNER) → 403 FORBIDDEN

**Pre-conditions:**
- Learner accessToken is available

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/lessons with Authorization: Bearer {learnerToken}.
2. AuthFilter validates token and resolves LEARNER identity.
3. Security layer (Method Security) detects that @PreAuthorize("hasRole('CONTENT_MANAGER')") is not met.
4. System returns forbidden response via AccessDeniedHandler.

**Expected Results:** HTTP 403 FORBIDDEN
Response JSON:
- success = false
- code = "FORBIDDEN"
- message = "Bạn không có quyền truy cập tài nguyên này"

---

## Section 11 — Public / Learner Facing API (PB)

### Test Case ID: TC_LCM_PB_001
**Test Case Description:** View published lessons as public/learner (Detailed expected results)

**Pre-conditions:**
- Course exists
- Multiple lessons exists (some DRAFT, some PUBLISHED)

**Test Case Procedure:**
1. Client sends GET /api/v1/courses/{courseId}/lessons (No auth required).
2. PublicLessonController forwards the request to LessonService.
3. LessonService retrieves only PUBLISHED lessons for the course.
4. System returns the list of published lessons.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data is an array
- All items in data have status = "PUBLISHED"
- data[0].id exists
- data[0].title exists
- data[0].lessonType exists
- data[0].orderIndex exists

---

### Test Case ID: TC_LCM_PB_002
**Test Case Description:** View published lesson detail as public/learner

**Pre-conditions:**
- Lesson created and status is PUBLISHED

**Test Case Procedure:**
1. Client sends GET /api/v1/courses/{courseId}/lessons/{lessonId}.
2. PublicLessonController forwards the request to LessonService.
3. LessonService retrieves lesson details.
4. System returns the lesson response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.id = requested lessonId
- data.status = "PUBLISHED"
- data.title exists
- data.content (vocabulary/grammar) exists if applicable

---

### Test Case ID: TC_LCM_PB_003
**Test Case Description:** Access DRAFT lesson via public API → Failure or filtered

**Pre-conditions:**
- Lesson exists with status DRAFT

**Test Case Procedure:**
1. Client sends GET /api/v1/courses/{courseId}/lessons.
2. Verify if the DRAFT lesson is included in the list.

**Expected Results:** HTTP 200 OK
- Response JSON: data array does NOT contain the DRAFT lesson id.

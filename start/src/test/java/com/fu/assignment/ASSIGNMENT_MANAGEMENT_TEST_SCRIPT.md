# Assignment Management Integration Test Script

## Overview
This document contains detailed test scripts for all assignment management API endpoints in Satori Learning Support Application backend.

## Test Environment Setup

### Pre-conditions
- Test DB running via `application-test.properties`
- PostgreSQL `satori_test` database created and initialized
- Docker environment up and running
- AI services mocked to prevent actual HTTP calls
- Cloudinary service mocked for file uploads

---

## Section 1 — Teacher View Assignment List Tests

### Test Case ID: TC_AS_T_VL_001
**Test Case Description:** Teacher view assignment list success with all required fields

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course and class created via saveCourse() and saveClass()
- Assignments inserted via repository:
- expiredQuiz (type=QUIZ, dueDate in past, hasSubmissions=true)
- ongoingWriting (type=WRITING, dueDate in future, hasSubmissions=true)
- ongoingTranslation (type=TRANSLATION, dueDate in future, hasSubmissions=true)

**Test Case Procedure:**
1. Client sends GET /api/v1/teacher/assignments/class/{classId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService retrieves assignments from AssignmentRepository (excluding deleted records).
5. AssignmentRepository queries the database and returns paginated data.
6. System returns paginated assignment list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content.length() = 3
- data.content[0].title = "Expired Quiz"
- data.content[0].assignmentType = "QUIZ"
- data.content[0].status exists
- data.content[0].dueDate exists
- data.content[0].questionCount exists
- data.content[0].submissionCount exists
- data.content[0].ungradedCount exists
- data.content[0].createdAt exists
- data.content[0].hasSubmissions exists

---

### Test Case ID: TC_AS_T_VL_002
**Test Case Description:** Class ID does not exist → 404 NOT FOUND

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- No class exists with the provided UUID

**Test Case Procedure:**
1. Client sends GET /api/v1/teacher/assignments/class/{nonExistentClassId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService attempts to find class via ClassRepository.
5. ClassRepository returns empty (class not found).
6. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND

---

### Test Case ID: TC_AS_T_VL_003
**Test Case Description:** Class exists but has no assignments → empty list

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course and class created via saveCourse() and saveClass()
- No assignments exist for the class

**Test Case Procedure:**
1. Client sends GET /api/v1/teacher/assignments/class/{classId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService retrieves assignments from AssignmentRepository (excluding deleted records).
5. AssignmentRepository queries the database and returns empty list.
6. System returns the empty paginated assignment list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content.length() = 0

---

### Test Case ID: TC_AS_T_VL_004
**Test Case Description:** Teacher B views Teacher A's class → 403 FORBIDDEN

**Pre-conditions:**
- Teacher A accessToken is available (from prior login)
- Teacher B accessToken is available (from prior login)
- Class created by Teacher A
- Teacher B attempts to access Teacher A's class

**Test Case Procedure:**
1. Client sends GET /api/v1/teacher/assignments/class/{teacherAClassId} with Authorization: Bearer {teacherBToken}.
2. AuthFilter validates token and resolves TEACHER B identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService checks if teacher is owner of the class via ClassRepository.
5. ClassRepository returns class owned by Teacher A.
6. System detects access denied and throws BusinessException with ACCESS_DENIED.
7. System returns the access denied error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_T_VL_006
**Test Case Description:** Get My Assignments (all classes) success

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Multiple classes created by the same teacher
- Assignments created across all classes:
- Published quiz, Published writing in class1
- Published quiz, Published writing in class2
- Quiz draft in class3

**Test Case Procedure:**
1. Client sends GET /api/v1/teacher/assignments with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService retrieves all assignments for the teacher via AssignmentRepository.
5. AssignmentRepository queries the database and returns paginated assignment list.
6. System returns the paginated assignment list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content.length() = 5
- data.totalElements = 5
- data.totalPages = 1
- data.pageNumber = 1
- data.pageSize = 10
- data.last = true
- data.first = false
- data.content contains assignments from all classes
- data.content[*].title exists
- data.content[*].assignmentType exists
- data.content[*].status exists
- data.content[*].questionCount exists
- data.content[*].className exists
- data.content[*].createdAt exists
- 3 assignments with type = QUIZ
- 2 assignments with type = WRITING
- 1 assignment with status = DRAFT
- 4 assignments with status = PUBLISHED

---

### Test Case ID: TC_AS_T_VL_006b
**Test Case Description:** Get My Assignments with filtering by class

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Multiple classes created by the same teacher
- Assignments created across different classes

**Test Case Procedure:**
1. Client sends GET /api/v1/teacher/assignments?classId={classId1} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService with class filter.
4. AssignmentService retrieves assignments for the teacher and class via AssignmentRepository.
5. AssignmentRepository queries the database with class filter and returns filtered results.
6. System returns the filtered paginated assignment list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content.length() = 2
- data.totalElements = 2
- All returned assignments belong to the specified class

---

### Test Case ID: TC_AS_T_VL_006c
**Test Case Description:** Get My Assignments with filtering by assignment type

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Multiple classes created by the same teacher
- Assignments with different types created

**Test Case Procedure:**
1. Client sends GET /api/v1/teacher/assignments?assignmentType=QUIZ with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService with type filter.
4. AssignmentService retrieves assignments for the teacher and type via AssignmentRepository.
5. AssignmentRepository queries the database with type filter and returns filtered results.
6. System returns the filtered paginated assignment list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content.length() = 2
- data.totalElements = 2
- All returned assignments have assignmentType = QUIZ

---

### Test Case ID: TC_AS_T_VL_006d
**Test Case Description:** Get My Assignments with title search

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Multiple classes created by the same teacher
- Assignments with specific titles created

**Test Case Procedure:**
1. Client sends GET /api/v1/teacher/assignments?keyword=Writing with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService with keyword filter.
4. AssignmentService searches assignments by keyword in title via AssignmentRepository.
5. AssignmentRepository queries the database with keyword search and returns matching results.
6. System returns the filtered paginated assignment list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content.length() = 1
- data.totalElements = 1
- data.content[0].title = "Published Writing"

---

### Test Case ID: TC_AS_T_VL_007
**Test Case Description:** Filter by title

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course and class created
- Published quiz and published writing assignments created

**Test Case Procedure:**
1. Client sends GET /api/v1/teacher/assignments/class/{classId}?title=Published Quiz with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService with title filter.
4. AssignmentService retrieves assignments by title filter via AssignmentRepository.
5. AssignmentRepository queries the database with title filter and returns matching results.
6. System returns the filtered assignment list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content.length() = 1

---

### Test Case ID: TC_AS_T_VL_007b
**Test Case Description:** Filter by type

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course and class created
- Published quiz and published writing assignments created

**Test Case Procedure:**
1. Client sends GET /api/v1/teacher/assignments/class/{classId}?assignmentType=QUIZ with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService with type filter.
4. AssignmentService retrieves assignments by type filter via AssignmentRepository.
5. AssignmentRepository queries the database with type filter and returns matching results.
6. System returns the filtered assignment list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content.length() = 1

---

### Test Case ID: TC_AS_T_VL_007c
**Test Case Description:** Filter by status

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course and class created
- Quiz draft and published quiz assignments created

**Test Case Procedure:**
1. Client sends GET /api/v1/teacher/assignments/class/{classId}?status=DRAFT with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService with status filter.
4. AssignmentService retrieves assignments by status filter via AssignmentRepository.
5. AssignmentRepository queries the database with status filter and returns matching results.
6. System returns the filtered assignment list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content.length() = 1

---

### Test Case ID: TC_AS_T_VL_007d
**Test Case Description:** Pagination test (page/size)

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course and class created
- Published quiz assignment created

**Test Case Procedure:**
1. Client sends GET /api/v1/teacher/assignments/class/{classId}?page=1&size=1 with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService with pagination parameters.
4. AssignmentService retrieves paginated assignments via AssignmentRepository.
5. AssignmentRepository queries the database with pagination and returns paginated results.
6. System returns the paginated assignment list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.pageNumber = 1
- data.pageSize = 1
- data.totalElements = 2

---

### Test Case ID: TC_AS_T_VL_008
**Test Case Description:** TypeMismatch error when passing UUID in wrong format

**Pre-conditions:**
- Teacher accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends GET /api/v1/teacher/assignments/class/{invalidUuid} with Authorization: Bearer {token}.
2. Spring MVC fails to convert invalid UUID string to UUID type.
3. System returns the type mismatch error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_T_VL_009
**Test Case Description:** ConstraintViolation error when page = -1

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course and class created

**Test Case Procedure:**
1. Client sends GET /api/v1/teacher/assignments/class/{classId}?page=-1 with Authorization: Bearer {token}.
2. Spring validation detects invalid page value (must be >= 0).
3. System returns the constraint violation error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_T_VL_010
**Test Case Description:** ConstraintViolation error when size = 0

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course and class created

**Test Case Procedure:**
1. Client sends GET /api/v1/teacher/assignments/class/{classId}?size=0 with Authorization: Bearer {token}.
2. Spring validation detects invalid size value (must be > 0).
3. System returns the constraint violation error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_T_VL_011
**Test Case Description:** MethodArgumentTypeMismatch error when Enum type is wrong

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course and class created

**Test Case Procedure:**
1. Client sends GET /api/v1/teacher/assignments/class/{classId}?assignmentType=INVALID_TYPE with Authorization: Bearer {token}.
2. Spring MVC fails to convert invalid string to AssignmentType enum.
3. System returns the method argument type mismatch error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_T_VL_012
**Test Case Description:** 401 Unauthorized error due to missing Authorization header

**Pre-conditions:**
- Course and class created

**Test Case Procedure:**
1. Client sends GET /api/v1/teacher/assignments/class/{classId} without Authorization header.
2. Spring Security detects missing authentication token.
3. System returns the unauthorized error response.

**Expected Results:** HTTP 401 UNAUTHORIZED

---

## Section 2 — Teacher View Assignment Information Tests

### Test Case ID: TC_AS_T_VI_001
**Test Case Description:** View success, response has correct fields

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course and class created
- Published quiz assignment created with questions

**Test Case Procedure:**
1. Client sends GET /api/v1/teacher/assignments/{assignmentId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService retrieves assignment by ID via AssignmentRepository.
5. AssignmentRepository queries the database and returns the assignment object.
6. AssignmentService retrieves assignment questions via AssignmentQuestionRepository.
7. AssignmentService retrieves quiz questions via QuizQuestionRepository.
8. System returns the assignment details response with questions.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.id exists
- data.title = "Published Quiz"
- data.assignmentType = "QUIZ"
- data.status = "PUBLISHED"
- data.dueDate exists
- data.instructions exists
- data.questionCount = 1
- data.questions array contains quiz question details
- data.questions[0].questionText exists
- data.questions[0].questionType = "multiple_choice"
- data.questions[0].options exists
- data.questions[0].correctAnswer = "A"
- data.questions[0].explanation exists

---

### Test Case ID: TC_AS_T_VI_002
**Test Case Description:** Assignment does not exist → 404

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- No assignment exists with the provided UUID

**Test Case Procedure:**
1. Client sends GET /api/v1/teacher/assignments/{nonExistentAssignmentId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService attempts to find assignment via AssignmentRepository.
5. AssignmentRepository returns empty (assignment not found).
6. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND

---

### Test Case ID: TC_AS_T_VI_004
**Test Case Description:** Teacher B views Teacher A's assignment → 403

**Pre-conditions:**
- Teacher A accessToken is available (from prior login)
- Teacher B accessToken is available (from prior login)
- Assignment created by Teacher A
- Teacher B attempts to access Teacher A's assignment

**Test Case Procedure:**
1. Client sends GET /api/v1/teacher/assignments/{teacherAAssignmentId} with Authorization: Bearer {teacherBToken}.
2. AuthFilter validates token and resolves TEACHER B identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService retrieves assignment via AssignmentRepository.
5. AssignmentRepository returns assignment owned by Teacher A.
6. System detects access denied and throws BusinessException with ACCESS_DENIED.
7. System returns the access denied error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_T_VI_006
**Test Case Description:** TypeMismatch error when passing UUID in wrong format

**Pre-conditions:**
- Teacher accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends GET /api/v1/teacher/assignments/{invalidUuid} with Authorization: Bearer {token}.
2. Spring MVC fails to convert invalid UUID string to UUID type.
3. System returns the type mismatch error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

## Section 3 — Teacher Create Assignment Tests

### Test Case ID: TC_AS_T_CR_001
**Test Case Description:** Create QUIZ success → status DRAFT

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course and class created

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/class/{classId}/quiz with Authorization: Bearer {token} and multipart data:
   - data: JSON with title, dueDate, status=DRAFT, questions=[]
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates teacher owns the class via ClassRepository.
5. AssignmentService creates new Assignment entity with QUIZ type and DRAFT status.
6. AssignmentRepository saves the new assignment.
7. AssignmentTargetRepository saves the assignment target linking to class.
8. System returns the created assignment response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.assignmentType = "QUIZ"
- data.status = "DRAFT"
- data.title = "Quiz Title"
- data.dueDate exists
- data.questionCount = 0
- data.id exists
- data.createdAt exists
- data.updatedAt exists

---

### Test Case ID: TC_AS_T_CR_002a
**Test Case Description:** Create WRITING success → status DRAFT

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course and class created

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/class/{classId}/writing with Authorization: Bearer {token} and JSON body:
   - title, dueDate, status=DRAFT, prompt
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates teacher owns the class via ClassRepository.
5. AssignmentService creates new Assignment entity with WRITING type and DRAFT status.
6. AssignmentRepository saves the new assignment.
7. AssignmentTargetRepository saves the assignment target linking to class.
8. WritingAssignmentRepository saves the writing assignment details.
9. System returns the created assignment response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.assignmentType = "WRITING"
- data.status = "DRAFT"
- data.title = "Writing Title"
- data.dueDate exists

---

### Test Case ID: TC_AS_T_CR_002b
**Test Case Description:** Create TRANSLATION success → status DRAFT

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course and class created

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/class/{classId}/translation with Authorization: Bearer {token} and JSON body:
   - title, dueDate, status=DRAFT, prompt
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates teacher owns the class via ClassRepository.
5. AssignmentService creates new Assignment entity with TRANSLATION type and DRAFT status.
6. AssignmentRepository saves the new assignment.
7. AssignmentTargetRepository saves the assignment target linking to class.
8. System returns the created assignment response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.assignmentType = "TRANSLATION"
- data.status = "DRAFT"
- data.title = "Translation Title"

---

### Test Case ID: TC_AS_T_CR_003
**Test Case Description:** Missing title → 400 validation error

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course and class created

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/class/{classId}/quiz with Authorization: Bearer {token} and multipart data:
   - data: JSON missing title field
2. Spring validation detects missing required field.
3. System returns the validation error response.

**Expected Results:** HTTP 400 BAD REQUEST
    
---

### Test Case ID: TC_AS_T_CR_004
**Test Case Description:** dueDate is in the past → 400 (@Future violation)

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course and class created

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/class/{classId}/quiz with Authorization: Bearer {token} and multipart data:
   - data: JSON with dueDate in the past
2. Spring validation detects @Future constraint violation.
3. System returns the validation error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_T_CR_005
**Test Case Description:** classId does not exist → 400 (CLASS_NOT_FOUND)

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- No class exists with the provided UUID

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/class/{nonExistentClassId}/quiz with Authorization: Bearer {token} and multipart data.
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService attempts to find class via ClassRepository.
5. ClassRepository returns empty (class not found).
6. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND

---

### Test Case ID: TC_AS_T_CR_006
**Test Case Description:** Create QUIZ success with instructions

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course and class created

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/class/{classId}/quiz with Authorization: Bearer {token} and multipart data:
   - data: JSON with title, instructions, dueDate, status=DRAFT, questions=[]
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService creates new Assignment entity with instructions field.
5. AssignmentRepository saves the new assignment.
6. System returns the created assignment response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.instructions = "Read carefully."

---

### Test Case ID: TC_AS_T_CR_007
**Test Case Description:** Learner calls Teacher create endpoint → 403

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Course and class created

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/class/{classId}/quiz with Authorization: Bearer {learnerToken} and multipart data.
2. AuthFilter validates token and resolves LEARNER identity.
3. Spring Security denies access to TEACHER endpoint.
4. System returns the forbidden error response.

**Expected Results:** HTTP 403 FORBIDDEN

---

### Test Case ID: TC_AS_T_CR_008
**Test Case Description:** Teacher B generate at class of Teacher A → 403 (ACCESS_DENIED)

**Pre-conditions:**
- Teacher A accessToken is available (from prior login)
- Teacher B accessToken is available (from prior login)
- Class created by Teacher A
- Teacher B attempts to create assignment in Teacher A's class

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/class/{teacherAClassId}/quiz with Authorization: Bearer {teacherBToken} and multipart data.
2. AuthFilter validates token and resolves TEACHER B identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates teacher owns the class via ClassRepository.
5. ClassRepository returns class owned by Teacher A.
6. System detects access denied and throws BusinessException with ACCESS_DENIED.
7. System returns the access denied error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_T_CR_009
**Test Case Description:** Generate QUIZ success with audio file

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course and class created
- CloudinaryService mocked to return upload URL

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/class/{classId}/quiz with Authorization: Bearer {token} and multipart data:
   - data: JSON with quiz details
   - audioFile: MP3 file
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService uploads audio file via CloudinaryService.
5. CloudinaryService returns mock upload URL.
6. AssignmentRepository saves the new assignment with audio URL.
7. System returns the created assignment response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.assignmentType = "QUIZ"

---

### Test Case ID: TC_AS_T_CR_010
**Test Case Description:** Create WRITING with no title → validation error

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course and class created

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/class/{classId}/writing with Authorization: Bearer {token} and JSON body missing title.
2. Spring validation detects missing required field.
3. System returns the validation error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_T_CR_011
**Test Case Description:**  HttpMessageNotReadable error cause by dueDate parse fail

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course and class created

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/class/{classId}/writing with Authorization: Bearer {token} and JSON body with invalid dueDate format.
2. Jackson fails to parse invalid date string.
3. System returns the HTTP message not readable error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

## Section 4 — Teacher Update Assignment Tests

### Test Case ID: TC_AS_T_UP_001
**Test Case Description:** Update metadata success when DRAFT

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course and class created
- Quiz draft assignment created

**Test Case Procedure:**
1. Client sends PUT /api/v1/teacher/assignments/{assignmentId}/quiz with Authorization: Bearer {token} and multipart data:
   - data: JSON with updated title, dueDate, status=DRAFT, questions=[]
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates assignment is in DRAFT status (allows full update).
5. AssignmentService updates assignment fields and saves via AssignmentRepository.
6. System returns the updated assignment response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.title = "Updated Title"

---

### Test Case ID: TC_AS_T_UP_002
**Test Case Description:** Update metadata success when PUBLISHED

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course and class created
- Published quiz assignment created

**Test Case Procedure:**
1. Client sends PUT /api/v1/teacher/assignments/{assignmentId}/quiz with Authorization: Bearer {token} and multipart data:
   - data: JSON with updated title, dueDate, status=PUBLISHED, questions=[]
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates assignment is PUBLISHED (allows limited metadata update).
5. AssignmentService updates allowed fields and saves via AssignmentRepository.
6. System returns the updated assignment response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.title = "Updated Published Title"

---

### Test Case ID: TC_AS_T_UP_003
**Test Case Description:** Update content (question) when no submission → success

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course and class created
- Quiz draft assignment created (no submissions)

**Test Case Procedure:**
1. Client sends PUT /api/v1/teacher/assignments/{assignmentId}/quiz with Authorization: Bearer {token} and multipart data:
   - data: JSON with questions array containing one question
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates assignment has no submissions (hasSubmissions=false).
5. AssignmentService deletes old questions and creates new ones via QuizQuestionRepository.
6. AssignmentQuestionRepository updates question links.
7. AssignmentRepository updates question count and saves.
8. System returns the updated assignment response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.actualQuestionCount = 1

---

### Test Case ID: TC_AS_T_UP_004
**Test Case Description:** Update content when hasSubmissions=true → 400 (HAS_SUBMISSIONS)

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course and class created
- Published quiz assignment created
- Learner enrolled and started submission (hasSubmissions=true)

**Test Case Procedure:**
1. Client sends PUT /api/v1/teacher/assignments/{assignmentId}/quiz with Authorization: Bearer {token} and multipart data:
   - data: JSON with questions array
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService detects assignment has submissions (hasSubmissions=true).
5. System throws BusinessException with HAS_SUBMISSIONS.
6. System returns the business error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_T_UP_005
**Test Case Description:** Shorten dueDate when hasSubmissions=true → 400

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course and class created
- Published quiz assignment created
- Learner enrolled and started submission (hasSubmissions=true)

**Test Case Procedure:**
1. Client sends PUT /api/v1/teacher/assignments/{assignmentId}/quiz with Authorization: Bearer {token} and multipart data:
   - data: JSON with earlier dueDate (shortened)
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService detects dueDate is earlier than current dueDate with submissions.
5. System throws BusinessException with INVALID_DUE_DATE_CHANGE.
6. System returns the business error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_T_UP_006
**Test Case Description:** Extend dueDate when hasSubmissions=true → success
**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course and class created
- Published quiz assignment created
- Learner enrolled and started submission (hasSubmissions=true)

**Test Case Procedure:**
1. Client sends PUT /api/v1/teacher/assignments/{assignmentId}/quiz with Authorization: Bearer {token} and multipart data:
   - data: JSON with later dueDate (extended)
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates dueDate is later than current dueDate (allowed).
5. AssignmentService updates dueDate and saves via AssignmentRepository.
6. System returns the updated assignment response.

**Expected Results:** HTTP 200 OK

---

### Test Case ID: TC_AS_T_UP_007
**Test Case Description:** update instructions when hasSubmissions=true → thành công

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course and class created
- Published quiz assignment created
- Learner enrolled and started submission (hasSubmissions=true)

**Test Case Procedure:**
1. Client sends PUT /api/v1/teacher/assignments/{assignmentId}/quiz with Authorization: Bearer {token} and multipart data:
   - data: JSON with updated instructions
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates instructions update is allowed with submissions.
5. AssignmentService updates instructions and saves via AssignmentRepository.
6. System returns the updated assignment response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.instructions = "Do not use dictionary."

---

### Test Case ID: TC_AS_T_UP_008
**Test Case Description:** Update dueDate to past date → 400 (INVALID_DUE_DATE)

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course and class created
- Published quiz assignment created

**Test Case Procedure:**
1. Client sends PUT /api/v1/teacher/assignments/{assignmentId}/quiz with Authorization: Bearer {token} and multipart data:
   - data: JSON with dueDate in the past
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService detects dueDate is in the past.
5. System throws BusinessException with INVALID_DUE_DATE.
6. System returns the business error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_T_UP_009
**Test Case Description:** Update WRITING success

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course and class created
- Published writing assignment created

**Test Case Procedure:**
1. Client sends PUT /api/v1/teacher/assignments/{assignmentId}/writing with Authorization: Bearer {token} and JSON body:
   - title, dueDate, status=PUBLISHED, updated prompt
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService updates assignment fields and writing prompt.
5. AssignmentRepository saves the updated assignment.
6. WritingAssignmentRepository saves the updated writing details.
7. System returns the updated assignment response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.title = "Updated Writing"

---

### Test Case ID: TC_AS_T_UP_010
**Test Case Description:** Update TRANSLATION success

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course and class created
- Published translation assignment created

**Test Case Procedure:**
1. Client sends PUT /api/v1/teacher/assignments/{assignmentId}/translation with Authorization: Bearer {token} and JSON body:
   - title, dueDate, status=PUBLISHED, updated prompt, sourceText
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService updates assignment fields and translation details.
5. AssignmentRepository saves the updated assignment.
6. System returns the updated assignment response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.title = "Updated Translation"

---

### Test Case ID: TC_AS_T_UP_011
**Test Case Description:** Update writing prompt when has submissions → 400 HAS_SUBMISSIONS

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course and class created
- Published writing assignment created with submissions

**Test Case Procedure:**
1. Client sends PUT /api/v1/teacher/assignments/{assignmentId}/writing with Authorization: Bearer {token} and JSON body:
   - title, dueDate, status=PUBLISHED, updated prompt
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService detects assignment has submissions.
5. System throws BusinessException with HAS_SUBMISSIONS.
6. System returns the business error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

## Section 5 — Learner Assignment Tests

### Test Case ID: TC_AS_L_VL_001
**Test Case Description:** Learner view assignment list success

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Course and class created
- Learner enrolled in class
- Multiple assignments created (published, draft, expired)

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/assignments with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates learner is enrolled in class via ClassMemberRepository.
5. AssignmentService retrieves published assignments via AssignmentRepository.
6. AssignmentRepository queries the database and returns filtered assignment list.
7. System returns the filtered assignment list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content contains only PUBLISHED assignments
- DRAFT assignments are filtered out
- EXPIRED assignments may be included with appropriate status

---

## Section 6 — AI Services Tests

### Test Case ID: TC_AS_AI_001
**Test Case Description:** AI generate quiz questions success

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course and class created
- QuizAiGenerationService mocked to return questions

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/questions/ai-generate with Authorization: Bearer {token} and JSON body:
   - teacherPrompt, classId, questionCount, questionTypes
2. AuthFilter validates token and resolves TEACHER identity.
3. TeacherAssignmentController forwards the request to AssignmentService.aiGenerateQuestions().
4. AssignmentService calls QuizAiGenerationService.generateQuestions().
5. Mocked AI service returns generated questions.
6. System returns the generated questions response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data array contains AI-generated questions
- data[*].questionText exists
- data[*].options exists
- data[*].correctAnswer exists

---

### Test Case ID: TC_AS_AI_002
**Test Case Description:** AI grading writing assignment success

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Writing assignment submitted
- AiGradingService mocked to return evaluation

**Test Case Procedure:**
1. AssignmentService calls AiGradingService after submission.
2. Mocked AI service returns writing evaluation.
3. AssignmentService updates submission with AI score and feedback.
4. System returns the submission result with AI evaluation.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.aiScore exists
- data.aiFeedback exists
- data.grammarScore exists
- data.vocabularyScore exists
- data.contentScore exists

---

## Section 7 — Assignment Status Management Tests

### Test Case ID: TC_AS_T_ST_001
**Test Case Description:** Publish assignment success

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Draft assignment created with questions

**Test Case Procedure:**
1. Client sends PUT /api/v1/teacher/assignments/{assignmentId}/status with Authorization: Bearer {token} and JSON body:
   - status = PUBLISHED
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates assignment has questions and is ready for publishing.
5. AssignmentService updates assignment status to PUBLISHED.
6. AssignmentRepository saves the updated assignment.
7. System returns the updated assignment response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.status = PUBLISHED
- data.publishedAt exists

---

### Test Case ID: TC_AS_T_ST_002
**Test Case Description:** Publish assignment without questions → 400

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Draft assignment created without questions

**Test Case Procedure:**
1. Client sends PUT /api/v1/teacher/assignments/{assignmentId}/status with Authorization: Bearer {token} and JSON body:
   - status = PUBLISHED
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService detects assignment has no questions.
5. System throws BusinessException with NO_QUESTIONS.
6. System returns the business error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

## Section 8 — Assignment Deletion Tests

### Test Case ID: TC_AS_T_DL_001
**Test Case Description:** Delete assignment success (no submissions)

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Draft assignment created (no submissions)

**Test Case Procedure:**
1. Client sends DELETE /api/v1/teacher/assignments/{assignmentId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates assignment has no submissions.
5. AssignmentService performs soft delete: sets deletedAt, deletedBy, status=DELETED.
6. AssignmentRepository saves the updated assignment.
7. System returns the delete success response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- message = "Xóa bài tập thành công"

---

## Section 9 — Assignment Results and Analytics Tests

---

## Section 10 — Teacher Assignment Clone Tests

### Test Case ID: TC_AS_T_CL_001
**Test Case Description:** Clone assignment to different class success

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Source assignment created with questions
- Target class created by same teacher

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/{assignmentId}/clone with Authorization: Bearer {token} and JSON body:
   - targetClassId: {targetClassId}
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates teacher owns both classes.
5. AssignmentService clones assignment with new ID but same content.
6. AssignmentRepository saves the cloned assignment.
7. QuizQuestionRepository clones questions for new assignment.
8. System returns the cloned assignment response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.id exists (different from source)
- data.title = "Cloned Assignment"
- data.classId = targetClassId
- data.questionCount = same as source

---

### Test Case ID: TC_AS_T_CL_002
**Test Case Description:** Clone sang class không tồn tại → 404

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Source assignment created

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/{assignmentId}/clone with Authorization: Bearer {token} and JSON body:
   - targetClassId: {nonExistentClassId}
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService attempts to find target class via ClassRepository.
5. ClassRepository returns empty (class not found).
6. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND

---

### Test Case ID: TC_AS_T_CL_003
**Test Case Description:** Clone WRITING assignment → thành công, status DRAFT

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Source WRITING assignment created
- Target class created by same teacher

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/{assignmentId}/clone with Authorization: Bearer {token} and JSON body:
   - targetClassId: {targetClassId}
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates teacher owns both classes.
5. AssignmentService clones WRITING assignment with DRAFT status.
6. WritingAssignmentRepository clones writing details.
7. System returns the cloned assignment response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.assignmentType = "WRITING"
- data.status = "DRAFT"

---

### Test Case ID: TC_AS_T_CL_004
**Test Case Description:** Teacher B clone assignment Teacher A → 400 ACCESS_DENIED

**Pre-conditions:**
- Teacher A accessToken is available (from prior login)
- Teacher B accessToken is available (from prior login)
- Assignment created by Teacher A
- Target class created by Teacher B

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/{teacherAAssignmentId}/clone with Authorization: Bearer {teacherBToken} and JSON body:
   - targetClassId: {teacherBClassId}
2. AuthFilter validates token and resolves TEACHER B identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService detects teacher doesn't own source assignment.
5. System throws BusinessException with ACCESS_DENIED.
6. System returns the access denied error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

## Section 11 — Teacher Assignment Grading Tests

### Test Case ID: TC_AS_T_GR_001
**Test Case Description:** Teacher chấm điểm bài Writing hợp lệ → thành công, status GRADED

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Writing assignment submitted by learner

**Test Case Procedure:**
1. Client sends PUT /api/v1/teacher/assignments/submissions/{submissionId}/grade with Authorization: Bearer {token} and JSON body:
   - score: 85, feedback: "Good work"
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates teacher can grade the submission.
5. AssignmentService updates submission with GRADED status and score.
6. AssignmentSubmissionRepository saves the graded submission.
7. System returns the grading success response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.status = GRADED
- data.score = 85
- data.feedback = "Good work"

---

### Test Case ID: TC_AS_T_GR_002
**Test Case Description:** Grade QUIZ submission → 400 (INVALID_TYPE)

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Quiz assignment submitted by learner

**Test Case Procedure:**
1. Client sends PUT /api/v1/teacher/assignments/submissions/{submissionId}/grade with Authorization: Bearer {token} and JSON body:
   - score: 80, feedback: "Good"
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService detects assignment type is QUIZ (auto-graded).
5. System throws BusinessException with INVALID_TYPE.
6. System returns the business error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_T_GR_003
**Test Case Description:** Grade submission IN_PROGRESS → 400 INVALID_STATUS

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Assignment submission in IN_PROGRESS status

**Test Case Procedure:**
1. Client sends PUT /api/v1/teacher/assignments/submissions/{submissionId}/grade with Authorization: Bearer {token} and JSON body:
   - score: 75, feedback: "Needs improvement"
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService detects submission is not SUBMITTED.
5. System throws BusinessException with INVALID_STATUS.
6. System returns the business error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_T_GR_004
**Test Case Description:** Grade submission đã GRADED → 400 INVALID_STATUS

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Assignment submission already GRADED

**Test Case Procedure:**
1. Client sends PUT /api/v1/teacher/assignments/submissions/{submissionId}/grade with Authorization: Bearer {token} and JSON body:
   - score: 90, feedback: "Excellent"
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService detects submission is already GRADED.
5. System throws BusinessException with INVALID_STATUS.
6. System returns the business error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_T_GR_005
**Test Case Description:** Teacher B chấm bài class Teacher A → 400 ACCESS_DENIED

**Pre-conditions:**
- Teacher A accessToken is available (from prior login)
- Teacher B accessToken is available (from prior login)
- Assignment submission in Teacher A's class

**Test Case Procedure:**
1. Client sends PUT /api/v1/teacher/assignments/submissions/{submissionId}/grade with Authorization: Bearer {teacherBToken} and JSON body:
   - score: 80, feedback: "Good"
2. AuthFilter validates token and resolves TEACHER B identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService detects teacher cannot grade this class.
5. System throws BusinessException with ACCESS_DENIED.
6. System returns the access denied error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

## Section 12 — Teacher Assignment Status Management (Additional Tests)

### Test Case ID: TC_AS_T_ST_003
**Test Case Description:** DRAFT → PUBLISHED với dueDate quá hạn trong request → 400

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Draft assignment created with questions

**Test Case Procedure:**
1. Client sends PUT /api/v1/teacher/assignments/{assignmentId}/status with Authorization: Bearer {token} and JSON body:
   - status = PUBLISHED, dueDate = {pastDate}
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService detects dueDate is in the past.
5. System throws BusinessException with INVALID_DUE_DATE.
6. System returns the business error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_T_ST_004
**Test Case Description:** PUBLISHED → DRAFT → 400 (INVALID_STATUS_TRANSITION)

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Published assignment created

**Test Case Procedure:**
1. Client sends PUT /api/v1/teacher/assignments/{assignmentId}/status with Authorization: Bearer {token} and JSON body:
   - status = DRAFT
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService detects invalid status transition.
5. System throws BusinessException with INVALID_STATUS_TRANSITION.
6. System returns the business error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_T_ST_005
**Test Case Description:** PUBLISHED → CLOSED → thành công

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Published assignment created

**Test Case Procedure:**
1. Client sends PUT /api/v1/teacher/assignments/{assignmentId}/status with Authorization: Bearer {token} and JSON body:
   - status = CLOSED
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates status transition is allowed.
5. AssignmentService updates assignment status to CLOSED.
6. AssignmentRepository saves the updated assignment.
7. System returns the updated assignment response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.status = CLOSED

---

### Test Case ID: TC_AS_T_ST_006
**Test Case Description:** CLOSED → PUBLISHED → 400 (terminal state)

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Closed assignment created

**Test Case Procedure:**
1. Client sends PUT /api/v1/teacher/assignments/{assignmentId}/status with Authorization: Bearer {token} and JSON body:
   - status = PUBLISHED
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService detects assignment is in terminal state.
5. System throws BusinessException with INVALID_STATUS_TRANSITION.
6. System returns the business error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

## Section 13 — Teacher Assignment Statistics Tests

### Test Case ID: TC_AS_T_STAT_001
**Test Case Description:** Get statistics thành công

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Multiple assignments with different statuses and submission counts

**Test Case Procedure:**
1. Client sends GET /api/v1/teacher/assignments/statistics with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService aggregates statistics across all teacher's assignments.
5. AssignmentRepository queries database for assignment counts by status.
6. AssignmentSubmissionRepository queries for submission statistics.
7. System returns the statistics response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.totalAssignments exists
- data.draftAssignments exists
- data.publishedAssignments exists
- data.closedAssignments exists
- data.totalSubmissions exists
- data.pendingGrading exists

---

## Section 14 — Teacher View Submissions Tests

### Test Case ID: TC_AS_T_VS_001
**Test Case Description:** Xem list submissions thành công

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Assignment with multiple submissions created

**Test Case Procedure:**
1. Client sends GET /api/v1/teacher/assignments/{assignmentId}/submissions with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates teacher owns the assignment.
5. AssignmentService retrieves submissions via AssignmentSubmissionRepository.
6. AssignmentRepository queries the database and returns submission list.
7. System returns the submissions list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content array contains submission details
- data.content[*].learnerName exists
- data.content[*].status exists
- data.content[*].submittedAt exists
- data.content[*].score exists (if graded)

---

### Test Case ID: TC_AS_T_VS_002
**Test Case Description:** Assignment chưa có submission → empty list

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Assignment created without submissions

**Test Case Procedure:**
1. Client sends GET /api/v1/teacher/assignments/{assignmentId}/submissions with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService retrieves submissions via AssignmentSubmissionRepository.
5. AssignmentRepository queries the database and returns empty list.
6. System returns the empty submissions list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content.length() = 0

---

### Test Case ID: TC_AS_T_VS_003
**Test Case Description:** Assignment ID không tồn tại → 404

**Pre-conditions:**
- Teacher accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends GET /api/v1/teacher/assignments/{nonExistentAssignmentId}/submissions with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService attempts to find assignment via AssignmentRepository.
5. AssignmentRepository returns empty (assignment not found).
6. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND

---

### Test Case ID: TC_AS_T_VS_004
**Test Case Description:** Xem 1 submission cụ thể → đúng fields

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Assignment submission created

**Test Case Procedure:**
1. Client sends GET /api/v1/teacher/assignments/submissions/{submissionId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates teacher can view the submission.
5. AssignmentService retrieves submission details via AssignmentSubmissionRepository.
6. System returns the submission details response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.id exists
- data.learnerName exists
- data.status exists
- data.submittedAt exists
- data.score exists (if graded)
- data.answers array exists (for quiz)

---

### Test Case ID: TC_AS_T_VS_005
**Test Case Description:** Submission không tồn tại → 404

**Pre-conditions:**
- Teacher accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends GET /api/v1/teacher/assignments/submissions/{nonExistentSubmissionId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService attempts to find submission via AssignmentSubmissionRepository.
5. AssignmentSubmissionRepository returns empty (submission not found).
6. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND

---

## Section 15 — Teacher Assignment Deletion (Additional Tests)

### Test Case ID: TC_AS_T_DL_003
**Test Case Description:** Xóa khi có submissions → 400 (requireDraft block)

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Draft assignment with submissions created

**Test Case Procedure:**
1. Client sends DELETE /api/v1/teacher/assignments/{assignmentId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService detects assignment has submissions.
5. System throws BusinessException with HAS_SUBMISSIONS.
6. System returns the business error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_T_DL_004
**Test Case Description:** Xóa PUBLISHED không có submissions → 200 (OK)

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Published assignment created without submissions

**Test Case Procedure:**
1. Client sends DELETE /api/v1/teacher/assignments/{assignmentId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates assignment has no submissions.
5. AssignmentService performs soft delete: sets deletedAt, deletedBy, status=DELETED.
6. AssignmentRepository saves the updated assignment.
7. System returns the delete success response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- message = "Xóa bài tập thành công"

---

### Test Case ID: TC_AS_T_DL_005
**Test Case Description:** Teacher B deletes Teacher A's assignment → 400 (ACCESS_DENIED)

**Pre-conditions:**
- Teacher A accessToken is available (from prior login)
- Teacher B accessToken is available (from prior login)
- Assignment created by Teacher A

**Test Case Procedure:**
1. Client sends DELETE /api/v1/teacher/assignments/{teacherAAssignmentId} with Authorization: Bearer {teacherBToken}.
2. AuthFilter validates token and resolves TEACHER B identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService detects teacher doesn't own the assignment.
5. System throws BusinessException with ACCESS_DENIED.
6. System returns the access denied error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

## Section 16 — Learner Assignment Tests (Additional)

### Test Case ID: TC_AS_L_VL_002
**Test Case Description:** DRAFT assignment không hiển thị với Learner

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Course and class created
- Learner enrolled in class
- Draft assignment created

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/assignments/class/{classId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates learner is enrolled in class via ClassMemberRepository.
5. AssignmentService retrieves published assignments via AssignmentRepository.
6. AssignmentRepository queries the database and filters out DRAFT assignments.
7. System returns the filtered assignment list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content.length() = 0 (DRAFT assignments filtered out)

---

### Test Case ID: TC_AS_L_VL_003
**Test Case Description:** Assignment CLOSED vẫn hiển thị (để xem lịch sử)

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Course and class created
- Learner enrolled in class
- Closed assignment created

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/assignments/class/{classId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates learner is enrolled in class via ClassMemberRepository.
5. AssignmentService retrieves assignments via AssignmentRepository.
6. AssignmentRepository queries the database and includes CLOSED assignments.
7. System returns the assignment list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content contains CLOSED assignments
- data.content[*].status = "CLOSED"

---

### Test Case ID: TC_AS_L_VL_004
**Test Case Description:** Learner không thuộc class nào → empty list

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Course and class created
- Learner NOT enrolled in class

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/assignments/class/{classId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates learner is enrolled in class via ClassMemberRepository.
5. ClassMemberRepository returns empty (learner not enrolled).
6. System returns the empty assignment list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content.length() = 0

---

### Test Case ID: TC_AS_L_VL_006
**Test Case Description:** Filter by title

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Course and class created
- Learner enrolled in class
- Multiple assignments with different titles created

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/assignments/class/{classId}?title=Published Quiz with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService with title filter.
4. AssignmentService validates learner is enrolled and retrieves assignments by title filter via AssignmentRepository.
5. AssignmentRepository queries the database with title filter and returns matching results.
6. System returns the filtered assignment list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content.length() = 1
- data.content[0].title = "Published Quiz"

---

### Test Case ID: TC_AS_L_VL_006b
**Test Case Description:** Filter by type

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Course and class created
- Learner enrolled in class
- Multiple assignments with different types created

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/assignments/class/{classId}?assignmentType=QUIZ with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService with type filter.
4. AssignmentService validates learner is enrolled and retrieves assignments by type filter via AssignmentRepository.
5. AssignmentRepository queries the database with type filter and returns matching results.
6. System returns the filtered assignment list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content[*].assignmentType = "QUIZ"

---

### Test Case ID: TC_AS_L_VL_006c
**Test Case Description:** Pagination test (page/size)

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Course and class created
- Learner enrolled in class
- Multiple assignments created

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/assignments/class/{classId}?page=0&size=5 with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService with pagination parameters.
4. AssignmentService validates learner is enrolled and retrieves paginated assignments via AssignmentRepository.
5. AssignmentRepository queries the database with pagination and returns paginated results.
6. System returns the paginated assignment list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.pageNumber = 0
- data.pageSize = 5

---

## Section 17 — Learner Assignment Doing Tests

### Test Case ID: TC_AS_L_DO_001
**Test Case Description:** Start QUIZ thành công

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Course and class created
- Learner enrolled in class
- Published quiz assignment created

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/{assignmentId}/start with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates learner is enrolled and assignment is published.
5. AssignmentService creates new AssignmentSubmission with IN_PROGRESS status.
6. AssignmentSubmissionRepository saves the new submission.
7. AssignmentRepository updates hasSubmissions flag.
8. System returns the assignment start response with questions.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.submissionId exists
- data.questions array contains quiz questions
- data.timeLimit exists (if applicable)

---

### Test Case ID: TC_AS_L_DO_002
**Test Case Description:** Start WRITING thành công

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Course and class created
- Learner enrolled in class
- Published writing assignment created

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/{assignmentId}/start with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates learner is enrolled and assignment is published.
5. AssignmentService creates new AssignmentSubmission with IN_PROGRESS status.
6. AssignmentSubmissionRepository saves the new submission.
7. AssignmentRepository updates hasSubmissions flag.
8. System returns the assignment start response with writing prompt.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.submissionId exists
- data.prompt exists
- data.timeLimit exists (if applicable)

---

### Test Case ID: TC_AS_L_DO_003
**Test Case Description:** Start assignment đã start → 400 (ALREADY_IN_PROGRESS)

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Assignment submission already IN_PROGRESS

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/{assignmentId}/start with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService detects learner already has IN_PROGRESS submission.
5. System throws BusinessException with ALREADY_IN_PROGRESS.
6. System returns the business error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_L_DO_004
**Test Case Description:** Start DRAFT assignment → 400 (ASSIGNMENT_NOT_PUBLISHED)

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Course and class created
- Learner enrolled in class
- Draft assignment created

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/{assignmentId}/start with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService detects assignment is not published.
5. System throws BusinessException with ASSIGNMENT_NOT_PUBLISHED.
6. System returns the business error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_L_DO_005
**Test Case Description:** Start assignment không tồn tại → 404

**Pre-conditions:**
- Learner accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/{nonExistentAssignmentId}/start with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService attempts to find assignment via AssignmentRepository.
5. AssignmentRepository returns empty (assignment not found).
6. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND

---

### Test Case ID: TC_AS_L_DO_006
**Test Case Description:** Start assignment không enrolled → 400 (NOT_ENROLLED)

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Course and class created
- Learner NOT enrolled in class
- Published assignment created

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/{assignmentId}/start with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService detects learner is not enrolled in the class.
5. System throws BusinessException with NOT_ENROLLED.
6. System returns the business error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_L_DO_007
**Test Case Description:** Submit QUIZ thành công

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Assignment submission started (IN_PROGRESS status)

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/submissions/{submissionId}/submit with Authorization: Bearer {token} and JSON body:
   - answers array with selected answers
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates submission belongs to learner and is IN_PROGRESS.
5. AssignmentService grades quiz answers automatically.
6. AssignmentService updates submission status to SUBMITTED with score.
7. AssignmentSubmissionRepository saves the updated submission.
8. System returns the submission result response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.score exists
- data.correctCount exists
- data.totalQuestions exists
- data.status = SUBMITTED

---

### Test Case ID: TC_AS_L_DO_008
**Test Case Description:** Submit WRITING thành công

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Writing assignment submission started (IN_PROGRESS status)

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/submissions/{submissionId}/submit with Authorization: Bearer {token} and JSON body:
   - content: "Learner's writing content"
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates submission belongs to learner and is IN_PROGRESS.
5. AssignmentService updates submission status to SUBMITTED.
6. AssignmentSubmissionRepository saves the updated submission.
7. System returns the submission result response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.status = SUBMITTED
- data.submittedAt exists

---

### Test Case ID: TC_AS_L_DO_009
**Test Case Description:** Submit không có submission → 404

**Pre-conditions:**
- Learner accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/submissions/{nonExistentSubmissionId}/submit with Authorization: Bearer {token} and JSON body:
   - answers array
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService attempts to find submission via AssignmentSubmissionRepository.
5. AssignmentSubmissionRepository returns empty (submission not found).
6. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND

---

### Test Case ID: TC_AS_L_DO_010
**Test Case Description:** Submit submission khác người → 400 (ACCESS_DENIED)

**Pre-conditions:**
- Learner A accessToken is available (from prior login)
- Assignment submission created by Learner B

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/submissions/{learnerBSubmissionId}/submit with Authorization: Bearer {learnerAToken} and JSON body:
   - answers array
2. AuthFilter validates token and resolves LEARNER A identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService detects submission doesn't belong to learner A.
5. System throws BusinessException with ACCESS_DENIED.
6. System returns the access denied error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_L_DO_011
**Test Case Description:** Submit đã SUBMITTED → 400 (INVALID_STATUS)

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Assignment submission already SUBMITTED

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/submissions/{submissionId}/submit with Authorization: Bearer {token} and JSON body:
   - answers array
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService detects submission is already SUBMITTED.
5. System throws BusinessException with INVALID_STATUS.
6. System returns the business error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_L_DO_012
**Test Case Description:** Submit quá deadline → SUBMITTED nhưng flagged OVERDUE

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Assignment submission started (IN_PROGRESS status)
- Assignment due date is in the past

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/submissions/{submissionId}/submit with Authorization: Bearer {token} and JSON body:
   - answers array
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates submission belongs to learner and is IN_PROGRESS.
5. AssignmentService detects submission is after due date.
6. AssignmentService grades quiz and sets status to SUBMITTED with overdue flag.
7. AssignmentSubmissionRepository saves the updated submission.
8. System returns the submission result response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.status = SUBMITTED
- data.isOverdue = true

---

### Test Case ID: TC_AS_L_DO_013
**Test Case Description:** Submit Writing với images → thành công

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Writing assignment submission started (IN_PROGRESS status)

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/submissions/{submissionId}/submit with Authorization: Bearer {token} and multipart data:
   - content: "Writing content"
   - images: image files
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates submission belongs to learner and is IN_PROGRESS.
5. AssignmentService uploads images via CloudinaryService.
6. AssignmentService updates submission with content and image URLs.
7. AssignmentSubmissionRepository saves the updated submission.
8. System returns the submission result response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.status = SUBMITTED
- data.imageUrls array exists

---

### Test Case ID: TC_AS_L_DO_014
**Test Case Description:** Submit Writing quá time limit → 400 (TIME_LIMIT_EXCEEDED)

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Writing assignment with time limit started
- Time limit has been exceeded

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/submissions/{submissionId}/submit with Authorization: Bearer {token} and JSON body:
   - content: "Writing content"
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService detects time limit has been exceeded.
5. System throws BusinessException with TIME_LIMIT_EXCEEDED.
6. System returns the business error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_L_DO_015
**Test Case Description:** Evaluate Writing thành công

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Writing assignment submitted (SUBMITTED status)

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/submissions/{submissionId}/evaluate with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates submission belongs to learner and is SUBMITTED.
5. AssignmentService calls AiGradingService to evaluate writing.
6. AiGradingService returns evaluation with score and feedback.
7. AssignmentService updates submission with AI evaluation.
8. AssignmentSubmissionRepository saves the updated submission.
9. System returns the evaluation response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.aiScore exists
- data.aiFeedback exists
- data.grammarScore exists
- data.vocabularyScore exists

---

### Test Case ID: TC_AS_L_DO_016
**Test Case Description:** Evaluate Writing chưa SUBMITTED → 400

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Writing assignment submission IN_PROGRESS

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/submissions/{submissionId}/evaluate with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService detects submission is not SUBMITTED.
5. System throws BusinessException with INVALID_STATUS.
6. System returns the business error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_L_DO_017
**Test Case Description:** Evaluate QUIZ submission → 400 (INVALID_TYPE)

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Quiz assignment submitted

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/submissions/{submissionId}/evaluate with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService detects assignment type is QUIZ (auto-graded).
5. System throws BusinessException with INVALID_TYPE.
6. System returns the business error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_L_DO_018
**Test Case Description:** Evaluate Writing không có submission → 404

**Pre-conditions:**
- Learner accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/submissions/{nonExistentSubmissionId}/evaluate with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService attempts to find submission via AssignmentSubmissionRepository.
5. AssignmentSubmissionRepository returns empty (submission not found).
6. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND

---

### Test Case ID: TC_AS_L_DO_019
**Test Case Description:** Evaluate Writing thành công với image

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Writing assignment submitted with images

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/submissions/{submissionId}/evaluate with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates submission belongs to learner and is SUBMITTED.
5. AssignmentService calls AiGradingService with image URLs.
6. AiGradingService returns evaluation considering images.
7. AssignmentService updates submission with AI evaluation.
8. AssignmentSubmissionRepository saves the updated submission.
9. System returns the evaluation response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.aiScore exists
- data.aiFeedback exists
- data.imageAnalysis exists

---

### Test Case ID: TC_AS_L_DO_020
**Test Case Description:** Evaluate Writing thất bại khi chưa start submission

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Assignment created but not started

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/submissions/{submissionId}/evaluate with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService attempts to find submission via AssignmentSubmissionRepository.
5. AssignmentSubmissionRepository returns empty (no submission exists).
6. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND

---

### Test Case ID: TC_AS_L_DO_021
**Test Case Description:** Evaluate Writing với multiple images

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Writing assignment submitted with multiple images

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/submissions/{submissionId}/evaluate with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates submission belongs to learner and is SUBMITTED.
5. AssignmentService calls AiGradingService with multiple image URLs.
6. AiGradingService returns evaluation considering all images.
7. AssignmentService updates submission with AI evaluation.
8. AssignmentSubmissionRepository saves the updated submission.
9. System returns the evaluation response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.aiScore exists
- data.aiFeedback exists
- data.imageAnalysis exists
- data.imageUrls array length > 1

---

### Test Case ID: TC_AS_L_DO_CONCURRENCY_001
**Test Case Description:** Double-submit 'startAssignment' → chỉ 1 submission được tạo

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Course and class created
- Learner enrolled in class
- Published quiz assignment created

**Test Case Procedure:**
1. Client sends two concurrent POST /api/v1/learner/assignments/{assignmentId}/start requests with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity for both requests.
3. AssignmentController forwards both requests to AssignmentService concurrently.
4. AssignmentService validates learner is enrolled and assignment is published for both requests.
5. First request creates new AssignmentSubmission with IN_PROGRESS status.
6. Second request detects existing IN_PROGRESS submission and throws BusinessException.
7. Only one submission is saved to database.
8. System returns success for first request and error for second request.

**Expected Results:** 
- First request: HTTP 200 OK with submission details
- Second request: HTTP 400 BAD REQUEST (ALREADY_IN_PROGRESS)
- Database contains exactly 1 submission for the learner and assignment

---

## Section 18 — Learner View Submissions Tests

### Test Case ID: TC_AS_L_VS_001
**Test Case Description:** Xem submission của chính mình → thành công

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Assignment submitted by learner

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/assignments/submissions/{submissionId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates submission belongs to learner.
5. AssignmentService retrieves submission details via AssignmentSubmissionRepository.
6. System returns the submission details response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.id exists
- data.status exists
- data.submittedAt exists
- data.score exists (if graded)

---

### Test Case ID: TC_AS_L_VS_001b
**Test Case Description:** Xem submission quiz đã chấm điểm → có score, số câu đúng/sai

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Quiz assignment submitted and auto-graded

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/assignments/submissions/{submissionId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates submission belongs to learner.
5. AssignmentService retrieves submission details with grading results.
6. System returns the submission details response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.score exists
- data.correctCount exists
- data.totalQuestions exists
- data.answers array contains answer details

---

### Test Case ID: TC_AS_L_VS_002
**Test Case Description:** Learner B xem submission của Learner A → 400 (ACCESS_DENIED)

**Pre-conditions:**
- Learner A accessToken is available (from prior login)
- Learner B accessToken is available (from prior login)
- Assignment submission created by Learner A

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/assignments/submissions/{learnerASubmissionId} with Authorization: Bearer {learnerBToken}.
2. AuthFilter validates token and resolves LEARNER B identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService detects submission doesn't belong to learner B.
5. System throws BusinessException with ACCESS_DENIED.
6. System returns the access denied error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_L_VS_003
**Test Case Description:** Submission SUBMITTED (chưa chấm) → status=SUBMITTED, score=null

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Writing assignment submitted but not yet graded

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/assignments/submissions/{submissionId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates submission belongs to learner.
5. AssignmentService retrieves submission details.
6. System returns the submission details response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.status = SUBMITTED
- data.score = null
- data.feedback = null

---

### Test Case ID: TC_AS_L_VS_004
**Test Case Description:** Submission OVERDUE → status=OVERDUE

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Assignment submitted after due date

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/assignments/submissions/{submissionId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates submission belongs to learner.
5. AssignmentService retrieves submission details.
6. System returns the submission details response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.status = OVERDUE
- data.isOverdue = true

---

### Test Case ID: TC_AS_L_VS_005
**Test Case Description:** Submission không tồn tại → 404

**Pre-conditions:**
- Learner accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/assignments/submissions/{nonExistentSubmissionId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService attempts to find submission via AssignmentSubmissionRepository.
5. AssignmentSubmissionRepository returns empty (submission not found).
6. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND

---

### Test Case ID: TC_AS_L_VS_006
**Test Case Description:** Submission GRADED (đã chấm) → status=GRADED, có score & feedback

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Writing assignment submitted and graded by teacher

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/assignments/submissions/{submissionId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates submission belongs to learner.
5. AssignmentService retrieves submission details with grading.
6. System returns the submission details response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.status = GRADED
- data.score exists
- data.feedback exists
- data.gradedAt exists

---

### Test Case ID: TC_AS_L_VS_007
**Test Case Description:** Get My Submissions History thành công

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Multiple assignment submissions across different assignments

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/assignments/submissions with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService retrieves all learner's submissions via AssignmentSubmissionRepository.
5. AssignmentSubmissionRepository queries database and returns paginated submission list.
6. System returns the submissions history response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content array contains all learner's submissions
- data.content[*].assignmentTitle exists
- data.content[*].status exists
- data.content[*].submittedAt exists
- data.content[*].score exists (if graded)

---

## Section 19 — AI Services Tests (Additional)

### Test Case ID: TC_AS_AI_003
**Test Case Description:** AI generate quiz questions with empty response → 200 with empty data

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- QuizAiGenerationService mocked to return empty list

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/ai/generate-quiz with Authorization: Bearer {token} and JSON body:
   - topic: "Simple topic", difficulty: "EASY", questionCount: 5
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to QuizAiGenerationService.
4. Mocked AI service returns empty questions list.
5. System returns the generated questions response with empty data.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.questions array is empty
- data.questions.length() = 0

---

### Test Case ID: TC_AS_AI_004
**Test Case Description:** AI generate quiz questions with invalid parameters → 400

**Pre-conditions:**
- Teacher accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/ai/generate-quiz with Authorization: Bearer {token} and JSON body:
   - topic: "", difficulty: "INVALID", questionCount: -1
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to QuizAiGenerationService.
4. Service validates input parameters and throws validation exception.
5. System returns the validation error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_AI_005
**Test Case Description:** AI generate quiz questions with network error → 400

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- QuizAiGenerationService mocked to throw network exception

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/ai/generate-quiz with Authorization: Bearer {token} and JSON body:
   - topic: "Test topic", difficulty: "MEDIUM", questionCount: 3
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to QuizAiGenerationService.
4. Mocked AI service throws network exception.
5. System handles exception and returns error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_AI_006
**Test Case Description:** AI generate quiz questions success with multiple choice

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- QuizAiGenerationService mocked to return multiple choice questions

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/ai/generate-quiz with Authorization: Bearer {token} and JSON body:
   - topic: "Japanese grammar", difficulty: "HARD", questionCount: 2
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to QuizAiGenerationService.
4. Mocked AI service returns multiple choice questions with options.
5. System returns the generated questions response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.questions array contains multiple choice questions
- data.questions[*].questionType = "multiple_choice"
- data.questions[*].options array exists
- data.questions[*].correctAnswer exists

---

### Test Case ID: TC_AS_AI_007
**Test Case Description:** AI explain quiz question success → saves explanation to DB

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- QuizExplanationService mocked to return explanation

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/ai/explain with Authorization: Bearer {token} and JSON body:
   - questionText: "Test question", selectedAnswer: "A"
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to QuizExplanationService.
4. Mocked AI service returns explanation.
5. QuizExplanationRepository saves explanation to database.
6. System returns the explanation response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.explanation exists
- data.explanationVi exists
- data.explanation.savedToDatabase = true

---

### Test Case ID: TC_AS_AI_008
**Test Case Description:** AI explain draft assignment → success

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Draft assignment created
- QuizExplanationService mocked to return explanation

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/{assignmentId}/ai/explain with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to QuizExplanationService.
4. Mocked AI service returns explanation for draft assignment.
5. System returns the explanation response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.explanation exists
- data.assignmentStatus = "DRAFT"

---

### Test Case ID: TC_AS_AI_009
**Test Case Description:** AI provider throws exception → 400

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- AI services mocked to throw provider exception

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/ai/generate-quiz with Authorization: Bearer {token} and JSON body:
   - topic: "Test topic", difficulty: "MEDIUM", questionCount: 3
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to QuizAiGenerationService.
4. Mocked AI service throws provider exception.
5. System handles provider exception and returns error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_AI_010
**Test Case Description:** AI generate: service throw BusinessException → 400

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- QuizAiGenerationService mocked to throw BusinessException

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/ai/generate-quiz with Authorization: Bearer {token} and JSON body:
   - topic: "Test topic", difficulty: "MEDIUM", questionCount: 3
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to QuizAiGenerationService.
4. Mocked AI service throws BusinessException.
5. GlobalExceptionHandler handles BusinessException and returns error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_AI_011
**Test Case Description:** AI generate returns empty list → 200 with empty data

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- QuizAiGenerationService mocked to return empty list

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/ai/generate-quiz with Authorization: Bearer {token} and JSON body:
   - topic: "Very specific topic", difficulty: "EXPERT", questionCount: 10
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to QuizAiGenerationService.
4. Mocked AI service returns empty questions list.
5. System returns success response with empty data.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.questions array is empty
- message = "No questions generated for the given criteria"

---

## Section 20 — Cloudinary Services Tests

### Test Case ID: TC_AS_CLOUD_001
**Test Case Description:** Upload audio file success → returns URL

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- CloudinaryService mocked to return upload URL

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/{assignmentId}/upload-audio with Authorization: Bearer {token} and multipart data:
   - audioFile: MP3 file
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to CloudinaryService.
4. Mocked CloudinaryService returns upload URL.
5. System returns the upload success response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.audioUrl exists
- data.audioUrl starts with "https://"

---

### Test Case ID: TC_AS_CLOUD_002
**Test Case Description:** Upload image file success → returns URL

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- CloudinaryService mocked to return upload URL

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/submissions/{submissionId}/upload-image with Authorization: Bearer {token} and multipart data:
   - imageFile: JPEG file
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to CloudinaryService.
4. Mocked CloudinaryService returns upload URL.
5. System returns the upload success response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.imageUrl exists
- data.imageUrl starts with "https://"

---

### Test Case ID: TC_AS_CLOUD_003
**Test Case Description:** Upload file with invalid format → 400

**Pre-conditions:**
- Teacher accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/{assignmentId}/upload-audio with Authorization: Bearer {token} and multipart data:
   - audioFile: TXT file (invalid format)
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to CloudinaryService.
4. CloudinaryService detects invalid file format and throws exception.
5. System returns the validation error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_CLOUD_004
**Test Case Description:** Upload file too large → 400

**Pre-conditions:**
- Teacher accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/{assignmentId}/upload-audio with Authorization: Bearer {token} and multipart data:
   - audioFile: Large MP3 file (>10MB)
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to CloudinaryService.
4. CloudinaryService detects file size limit exceeded and throws exception.
5. System returns the file size error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

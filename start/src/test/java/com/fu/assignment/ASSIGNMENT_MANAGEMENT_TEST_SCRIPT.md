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
**Test Case Description:** Class ID does not exist → 404 NOT FOUN
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

### Test Case ID: TC_AS_T_VL_006c
**Test Case Description:** Get My Assignments with filtering by assignment type

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Multiple classes created by the same teacher
- Assignments with different types created

**Test Case Procedure:**
1. Client sends GET /api/v1/teacher/assignments?type=QUIZ with Authorization: Bearer {token}.
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
1. Client sends GET /api/v1/teacher/assignments?title=Writing with Authorization: Bearer {token}.
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
1. Client sends GET /api/v1/teacher/assignments/class/{classId}?type=WRITING with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService with type filter.
4. AssignmentService retrieves assignments by type filter via AssignmentRepository.
5. AssignmentRepository queries the database with type filter and returns matching results.
6. System returns the filtered assignment list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content.length() = 1
- data.content[0].id = published writing assignment UUID

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
- data.id = assignment UUID
- data.title = "Published Quiz"
- data.description = "desc"
- data.instructions = null
- data.assignmentType = "QUIZ"
- data.startDate = null
- data.dueDate exists
- data.audioUrl = null
- data.questionCount = 1
- data.actualQuestionCount = 1
- data.status = "PUBLISHED"
- data.createdBy exists
- data.createdAt exists
- data.updatedAt exists
- data.hasSubmissions = false
- data.submissionCount = 0
- data.writingContent = null
- data.targets = null
- data.questions array length = 1
- data.questions[0].assignmentQuestionId exists
- data.questions[0].orderIndex = 0
- data.questions[0].points = null
- data.questions[0].questionId exists
- data.questions[0].questionText = "テストの問題"
- data.questions[0].questionType = "multiple_choice"
- data.questions[0].options array length = 2
- data.questions[0].options[0].id = "A"
- data.questions[0].options[0].text = "正しい"
- data.questions[0].options[0].isCorrect = true
- data.questions[0].options[1].id = "B"
- data.questions[0].options[1].text = "間違い"
- data.questions[0].options[1].isCorrect = false
- data.questions[0].explanation = null
- data.questions[0].imageUrl = null
- data.questions[0].jlptLevel = null
- data.questions[0].correctAnswer = "A"
- data.questions[0].correctAnswers = null

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
   - data: JSON with title, dueDate, status=DRAFT, questions=[2 multiple-choice questions]
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates teacher owns the class via ClassRepository.
5. AssignmentService creates new Assignment entity with QUIZ type and DRAFT status.
6. AssignmentRepository saves the new assignment with both questions.
7. AssignmentTargetRepository saves the assignment target linking to class.
8. System returns the created assignment response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.assignmentType = "QUIZ"
- data.status = "DRAFT"
- data.questionCount = 2

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
**Test Case Description:** update instructions when hasSubmissions=true → success

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

---

### Test Case ID: TC_AS_AI_002
**Test Case Description:** AI generate explanation for existing question

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- QuizQuestion entity exists in database
- QuizExplanationService mocked to return updated question with explanation

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/questions/{questionId}/ai-explain with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to QuizExplanationService.
4. QuizExplanationService.generateExplanation() returns updated QuizQuestion with explanation.
5. System returns the question details response with generated explanation.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.id = question UUID
- data.questionText exists
- data.questionType exists
- data.options exists
- data.correctAnswer exists
- data.explanation is not empty

---

## Section 7 — Assignment Status Management Tests

### Test Case ID: TC_AS_T_ST_001
**Test Case Description:** DRAFT → PUBLISHED success (QUIZ with questions)

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Quiz DRAFT assignment created (no questions initially)

**Test Case Procedure:**
1. Client sends PUT /api/v1/teacher/assignments/{assignmentId}/quiz with Authorization: Bearer {token} and multipart data:
   - data: JSON with status=PUBLISHED, questions=[1 valid question]
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates status transition DRAFT → PUBLISHED and that questions exist.
5. AssignmentService updates assignment status to PUBLISHED.
6. AssignmentRepository saves the updated assignment.
7. System returns the updated assignment response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.status = "PUBLISHED"

---

### Test Case ID: TC_AS_T_ST_002
**Test Case Description:** DRAFT → PUBLISHED without questions → 400 (NO_QUESTIONS)

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Quiz DRAFT assignment created without questions

**Test Case Procedure:**
1. Client sends PUT /api/v1/teacher/assignments/{assignmentId}/quiz with Authorization: Bearer {token} and multipart data:
   - data: JSON with status=PUBLISHED, questions=[]
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService detects assignment has no questions.
5. System throws BusinessException with NO_QUESTIONS.
6. System returns the business error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

## Section 8 — Assignment Deletion Tests

### Test Case ID: TC_AS_T_DL_001
**Test Case Description:** Delete DRAFT assignment with no submissions → soft delete success

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Quiz DRAFT assignment created (no submissions)

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
- Assignment is no longer findable via findByIdAndNotDeleted (DB verified)

---

## Section 9 — Assignment Results and Analytics Tests

---

## Section 10 — Teacher Assignment Clone Tests

### Test Case ID: TC_AS_T_CL_001
**Test Case Description:** Clone QUIZ assignment to different class → success, status DRAFT, new title

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Published QUIZ assignment created in sourceClass
- Another class (destClass) created by the same teacher

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/{assignmentId}/clone with Authorization: Bearer {token} and JSON body:
   - targetClassId: {destClassId}
   - newDueDate: {futureDate}
   - newTitle: "Cloned Quiz"
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService validates teacher owns both source assignment and target class.
5. AssignmentService clones assignment with new ID, new title, new dueDate, status=DRAFT.
6. AssignmentRepository saves the cloned assignment.
7. QuizQuestionRepository clones questions for new assignment.
8. System returns the cloned assignment response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.title = "Cloned Quiz"
- data.status = "DRAFT"

---

### Test Case ID: TC_AS_T_CL_002
**Test Case Description:** Clone to non-exist class → 404

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
**Test Case Description:** Clone WRITING assignment → success, status DRAFT

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
**Test Case Description:** Teacher's grading of the Writing assignment is successful → status GRADED

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Writing assignment submitted by learner

**Test Case Procedure:**
1. Client sends PUT /api/v1/teacher/assignments/submissions/{submissionId}/grade with Authorization: Bearer {token} and JSON body:
   - score: 85, feedback: "Bài viết rất tốt."
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
- data.feedback = "Bài viết rất tốt."

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
**Test Case Description:** Grade submission has been GRADED → 400 INVALID_STATUS

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
**Test Case Description:** Teacher B grades Teacher A's class → 400 ACCESS_DENIED

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
**Test Case Description:** DRAFT → PUBLISHED with overdue dueDate in request → 400

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
**Test Case Description:** PUBLISHED → CLOSED → success

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
**Test Case Description:** Get class statistics thành công

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Teacher's class created (no assignments yet)

**Test Case Procedure:**
1. Client sends GET /api/v1/teacher/assignments/class/{classId}/statistics with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService aggregates statistics for the given class.
5. AssignmentRepository queries database for assignment counts by status.
6. AssignmentSubmissionRepository queries for submission statistics.
7. System returns the statistics response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.classId = class UUID
- data.totalAssignments = 0
- data.draftAssignments = 0
- data.publishedAssignments = 0
- data.closedAssignments = 0
- data.totalSubmissions = 0
- data.ungradedSubmissions = 0
- data.gradedSubmissions = 0
- data.inProgressSubmissions = 0
- data.overdueSubmissions = 0

---

## Section 14 — Teacher View Submissions Tests

### Test Case ID: TC_AS_T_VS_001
**Test Case Description:** View list of successful submissions

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
**Test Case Description:** Assignment has no submission → empty list

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
**Test Case Description:** Assignment ID does not exist → 404

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
**Test Case Description:** Check a specific submission → correct fields

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
**Test Case Description:** Submission does not exist → 404

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
**Test Case Description:** Delete when submissions occur → 400 (requireDraft block)

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
**Test Case Description:** Delete PUBLISHED with no submissions → 200 (OK)

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
**Test Case Description:** DRAFT assignments are not showing up for Learner

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Course and class created
- Learner enrolled in class
- Draft assignment created

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/assignments with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService retrieves all assignments from classes the learner is enrolled in.
5. AssignmentRepository queries the database and filters out DRAFT assignments.
6. System returns the filtered assignment list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content.length() = 0 (DRAFT assignments filtered out)

---

### Test Case ID: TC_AS_L_VL_003
**Test Case Description:** Assignment CLOSED is still displayed (to view history)

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Course and class created
- Learner enrolled in class
- Closed assignment created

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/assignments with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService retrieves assignments from classes the learner is enrolled in.
5. AssignmentRepository queries the database and includes CLOSED assignments.
6. System returns the assignment list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content.length() = 1

---

### Test Case ID: TC_AS_L_VL_004
**Test Case Description:** Learner not belonging to any class → empty list

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Course and class created
- Learner NOT enrolled in class

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/assignments with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService retrieves assignments from classes the learner is enrolled in (none).
5. AssignmentRepository returns empty.
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
1. Client sends GET /api/v1/learner/assignments?title=Published Quiz with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService with title filter.
4. AssignmentService retrieves assignments of the learner's classes, filtered by title.
5. AssignmentRepository queries the database with title filter and returns matching results.
6. System returns the filtered assignment list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content.length() = 1
- data.content[0].id = published quiz assignment UUID

---

### Test Case ID: TC_AS_L_VL_006b
**Test Case Description:** Filter by type

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Course and class created
- Learner enrolled in class
- Multiple assignments with different types created

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/assignments?type=WRITING with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService with type filter.
4. AssignmentService retrieves assignments of the learner's classes, filtered by type.
5. AssignmentRepository queries the database with type filter and returns matching results.
6. System returns the filtered assignment list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content.length() = 1
- data.content[0].id = published writing assignment UUID

---

### Test Case ID: TC_AS_L_VL_006c
**Test Case Description:** Pagination test (page/size)

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Course and class created
- Learner enrolled in class
- Multiple assignments created

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/assignments?page=1&size=1 with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService with pagination parameters.
4. AssignmentService retrieves paginated assignments of the learner's classes.
5. AssignmentRepository queries the database with pagination and returns paginated results.
6. System returns the paginated assignment list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content.length() = 1
- data.pageNumber = 1
- data.pageSize = 1
- data.totalElements = 2

---

## Section 17 — Learner Assignment Doing Tests

### Test Case ID: TC_AS_L_DO_001
**Test Case Description:** Start QUIZ successful → submission IN_PROGRESS

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
7. System returns the assignment start response with questions.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.id = assignment UUID
- data.title = "Published Quiz"
- data.description exists
- data.instructions = null (not present)
- data.assignmentType = "QUIZ"
- data.startDate = null (not present)
- data.dueDate exists
- data.audioUrl = null (not present)
- data.actualQuestionCount = 1
- data.questions array length = 1
- data.questions[0].questionType = "multiple_choice"
- data.questions[0].options array length = 2
- data.writingContent = null (not present)
- DB: AssignmentSubmission saved with status = IN_PROGRESS

---

### Test Case ID: TC_AS_L_DO_002
**Test Case Description:** Assignment DRAFT → 400 (ASSIGNMENT_NOT_PUBLISHED)

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Course and class created
- Learner enrolled in class
- Draft quiz assignment created

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/{assignmentId}/start with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService detects assignment status is DRAFT (not published).
5. System throws BusinessException with ASSIGNMENT_NOT_PUBLISHED.
6. System returns the business error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_L_DO_003
**Test Case Description:** allowRetry=false, there was 1 attempt GRADED → 400 (NO_RETRY)

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Published quiz assignment with allowRetry=false
- 1 GRADED submission already exists for learner

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/{assignmentId}/start with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService detects learner already has a completed attempt and allowRetry=false.
5. System throws BusinessException with NO_RETRY.
6. System returns the business error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_L_DO_004
**Test Case Description:** There have been ≥2 attempts → 400 (NO_RETRY)

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Published quiz assignment
- 2 GRADED submissions already exist for learner

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/{assignmentId}/start with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService detects learner has reached maximum attempt count (≥ 2).
5. System throws BusinessException with NO_RETRY.
6. System returns the business error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_L_DO_005
**Test Case Description:** IN_PROGRESS → 200 (resume existing session)

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Published quiz assignment
- Existing IN_PROGRESS submission for learner

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/{assignmentId}/start with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService detects existing IN_PROGRESS submission and resumes it.
5. System returns the assignment response for the existing in-progress session.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.id = assignment UUID
- data.title = "Published Quiz"

---

### Test Case ID: TC_AS_L_DO_006
**Test Case Description:** Submit quiz on time, correct answers → GRADED, score=100

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Published quiz assignment with 1 question (correct answer = "A")
- Submission started (IN_PROGRESS)

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/{assignmentId}/submit-quiz with Authorization: Bearer {token} and JSON body:
   - answers: JSON string "[{...selectedAnswer:A}]"
   - timeSpentSeconds: 60
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService grades quiz: answer "A" is correct.
5. AssignmentService updates submission status to GRADED with score=100.
6. System returns the graded submission result.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.status = "GRADED"
- data.score = 100.00
- data.correctCount = 1

---

### Test Case ID: TC_AS_L_DO_007
**Test Case Description:** Submit Quiz with incorrect answer → GRADED, score=0

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Published quiz assignment with 1 question (correct answer = "A")
- Submission started (IN_PROGRESS)

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/{assignmentId}/submit-quiz with Authorization: Bearer {token} and JSON body:
   - answers: JSON string "[{...selectedAnswer:B}]"
   - timeSpentSeconds: 30
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService grades quiz: answer "B" is incorrect.
5. AssignmentService updates submission status to GRADED with score=0.
6. System returns the graded submission result.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.status = "GRADED"
- data.score = 0.00
- data.correctCount = 0

---

### Test Case ID: TC_AS_L_DO_008
**Test Case Description:** Submit Quiz overdue → 400 (PAST_DUE)

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Published quiz assignment with dueDate in the past
- Submission started (IN_PROGRESS)

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/{assignmentId}/submit-quiz with Authorization: Bearer {token} and JSON body:
   - answers: JSON string with selected answer
   - timeSpentSeconds: 60
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService detects assignment is past its dueDate.
5. System throws BusinessException with PAST_DUE.
6. System returns the business error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- code = "PAST_DUE"
- message = "Bài tập này đã hết hạn nộp."

---

    ### Test Case ID: TC_AS_L_DO_009
**Test Case Description:** Submit Writing with file too large (> 5MB) → 400 FILE_TOO_LARGE

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Published writing assignment
- Submission started (IN_PROGRESS)

**Test Case Procedure:**
1. Client sends multipart POST /api/v1/learner/assignments/{assignmentId}/submit-writing with Authorization: Bearer {token}:
   - images: image file larger than 5MB (e.g., 5.1MB PNG)
2. AuthFilter validates token and resolves LEARNER identity.
3. LearnerAssignmentController forwards the request to AssignmentService.
4. AssignmentService calls CloudinaryService.uploadImage to validate the image.
5. CloudinaryService detects file size exceeds 5MB limit.
6. CloudinaryService throws BusinessException("FILE_TOO_LARGE").
7. GlobalExceptionHandler catches the exception and returns 400 response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "FILE_TOO_LARGE"
- message = "Kích thước tệp không được vượt quá 5MB"

---

### Test Case ID: TC_AS_L_DO_010
**Test Case Description:** Submit Writing overdue → 400 (PAST_DUE)

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Published writing assignment with dueDate in the past (set after start)
- Submission started (IN_PROGRESS)

**Test Case Procedure:**
1. Client sends multipart POST /api/v1/learner/assignments/{assignmentId}/submit-writing with Authorization: Bearer {token}:
   - images: image file
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService detects assignment is past its dueDate.
5. System throws BusinessException with PAST_DUE.
6. System returns the business error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- code = "PAST_DUE"
- message = "Bài tập này đã hết hạn nộp."

---

### Test Case ID: TC_AS_L_DO_011
**Test Case Description:** Submit Writing missing image → 400

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Published writing assignment with valid dueDate
- Submission started (IN_PROGRESS)

**Test Case Procedure:**
1. Client sends multipart POST /api/v1/learner/assignments/{assignmentId}/submit-writing with Authorization: Bearer {token}:
   - data: JSON metadata (no images attached)
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService detects no images were provided.
5. System returns validation error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_L_DO_012
**Test Case Description:** Teacher gọi endpoint Learner submit → 403

**Pre-conditions:**
- Teacher accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/{randomId}/submit-quiz with Authorization: Bearer {teacherToken} and JSON body.
2. AuthFilter validates token and resolves TEACHER identity.
3. Security layer detects TEACHER role is not authorized to access learner endpoints.
4. System returns an access forbidden response.

**Expected Results:** HTTP 403 FORBIDDEN

---

### Test Case ID: TC_AS_L_DO_013
**Test Case Description:** Start assignment when CLOSED → 400 (ASSIGNMENT_NOT_PUBLISHED)

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Published quiz assignment manually set to CLOSED status

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/{assignmentId}/start with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards the request to AssignmentService.
4. AssignmentService detects assignment status is not PUBLISHED.
5. System throws BusinessException with ASSIGNMENT_NOT_PUBLISHED.
6. System returns the business error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_L_DO_014
**Test Case Description:** Submit assignment when CLOSED → vẫn chấm GRADED

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Published quiz assignment started (IN_PROGRESS), then assignment was CLOSED
- Submission exists in IN_PROGRESS state

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/{assignmentId}/submit-quiz with Authorization: Bearer {token} and JSON body:
   - answers: JSON string with correct answer
   - timeSpentSeconds: 60
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards to AssignmentService.
4. AssignmentService grades quiz even though assignment is now CLOSED.
5. System returns the graded submission result.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.status = "GRADED"
- data.score = 100.00

---

### Test Case ID: TC_AS_L_DO_015
**Test Case Description:** Submit WRITING assignment with image → SUBMITTED

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Published writing assignment
- Submission started (IN_PROGRESS)
- CloudinaryService mocked to return upload URL

**Test Case Procedure:**
1. Client sends multipart POST /api/v1/learner/assignments/{assignmentId}/submit-writing with Authorization: Bearer {token}:
   - data: JSON {"timeSpentSeconds": 120}
   - images: image file (PNG)
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards to AssignmentService.
4. AssignmentService calls CloudinaryService.uploadImage to upload the image.
5. Image URL returned is saved with submission.
6. AssignmentService updates submission status to SUBMITTED.
7. System returns the submitted result.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.status = "SUBMITTED"

---

### Test Case ID: TC_AS_L_DO_016
**Test Case Description:** Submit QUIZ answers failed due to JSON formatting error → 400

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Published quiz assignment
- Submission started (IN_PROGRESS)

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/{assignmentId}/submit-quiz with Authorization: Bearer {token} and JSON body:
   - answers: "invalid-json-array" (not valid JSON)
   - timeSpentSeconds: 60
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards to AssignmentService.
4. AssignmentService fails to parse the answers JSON string.
5. System returns a validation/parse error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_L_DO_017
**Test Case Description:** Learner submits quiz for WRITING assignment → 400

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Published WRITING assignment
- Submission started (IN_PROGRESS)

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/{assignmentId}/submit-quiz with Authorization: Bearer {token} and JSON body:
   - answers: "[]"
   - timeSpentSeconds: 60
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards to AssignmentService.
4. AssignmentService detects assignment type is WRITING, not QUIZ.
5. System throws BusinessException (wrong submission type).
6. System returns the business error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_L_DO_018
**Test Case Description:** Start assignment past due date → 400 PAST_DUE

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Published quiz assignment with dueDate in the past

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/assignments/{assignmentId}/start with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards to AssignmentService.
4. AssignmentService detects assignment dueDate has passed.
5. System throws BusinessException with PAST_DUE.
6. System returns the business error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_L_DO_019
**Test Case Description:** Evaluate Writing successfully with images.

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Published writing assignment
- Submission started (IN_PROGRESS)
- AiGradingService mocked to return feedback string

**Test Case Procedure:**
1. Client sends multipart POST /api/v1/learner/assignments/writing/evaluate with Authorization: Bearer {token}:
   - data: JSON {assignmentId, prompt}
   - images: image file (JPEG)
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards to AssignmentService.
4. AssignmentService calls AiGradingService.evaluateWriting with image.
5. Mocked AI returns feedback string.
6. System returns the evaluation response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.feedback = "Good writing with clear structure. Minor grammar issues."

---

### Test Case ID: TC_AS_L_DO_020
**Test Case Description:** Evaluate Writing fails before starting submission.

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Published writing assignment (no submission started yet)

**Test Case Procedure:**
1. Client sends multipart POST /api/v1/learner/assignments/writing/evaluate with Authorization: Bearer {token}:
   - data: JSON {assignmentId, prompt}
   - images: image file (JPEG)
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards to AssignmentService.
4. AssignmentService cannot find an active submission for the learner.
5. System throws BusinessException with SUBMISSION_NOT_FOUND.
6. System returns the business error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- code = "SUBMISSION_NOT_FOUND"

---

### Test Case ID: TC_AS_L_DO_021
**Test Case Description:** Evaluate Writing with multiple images

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Published writing assignment
- Submission started (IN_PROGRESS)
- AiGradingService mocked to return feedback string

**Test Case Procedure:**
1. Client sends multipart POST /api/v1/learner/assignments/writing/evaluate with Authorization: Bearer {token}:
   - data: JSON {assignmentId, prompt}
   - images: 2 image files (JPEG)
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards to AssignmentService.
4. AssignmentService calls AiGradingService.evaluateWriting with multiple images.
5. Mocked AI returns feedback string.
6. System returns the evaluation response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.feedback = "Excellent work with comprehensive content."

---

## Section 18 — Learner View Submissions Tests

### Test Case ID: TC_AS_L_VS_001
**Test Case Description:** View your own submission → successful

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
**Test Case Description:** View the graded submission quiz → you'll see your score and the number of correct/incorrect answers.

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
**Test Case Description:** Learner B views Learner A's submission → 400 (ACCESS_DENIED)

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
**Test Case Description:** Submission SUBMITTED (not yet scored) → status=SUBMITTED, score=null

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
- data.status = "OVERDUE"

---

### Test Case ID: TC_AS_L_VS_005
**Test Case Description:** Submission does not exist → 404

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
**Test Case Description:** Submission GRADED (graded) → status=GRADED, with score and feedback

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
**Test Case Description:** Get My Submissions History success

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
**Test Case Description:** AI generate quiz — missing teacherPrompt → 400

**Pre-conditions:**
- Teacher accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/questions/ai-generate with Authorization: Bearer {token} and JSON body:
   - teacherPrompt: null, questionCount: 1, questionTypes: ["multiple_choice"]
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to QuizAiGenerationService.
4. QuizAiGenerationService detects prompt is missing.
5. System returns validation error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_AI_004
**Test Case Description:** AI generate quiz — Learner calls endpoint → 403

**Pre-conditions:**
- Learner accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/questions/ai-generate with Authorization: Bearer {learnerToken} and JSON body:
   - teacherPrompt: "Test prompt", questionCount: 1, questionTypes: ["multiple_choice"]
2. AuthFilter validates token and resolves LEARNER identity.
3. Security layer detects LEARNER role is not authorized for /api/v1/teacher/** endpoints.
4. System returns forbidden response.

**Expected Results:** HTTP 403 FORBIDDEN


---


## Section 20 — Cloudinary Integration Tests

### Test Case ID: TC_AS_CLOUD_001
**Test Case Description:** Cloudinary upload audio fail → 500 INTERNAL_SERVER_ERROR

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course and class created
- CloudinaryService mocked to throw RuntimeException

**Test Case Procedure:**
1. Client sends multipart POST /api/v1/teacher/assignments/class/{classId}/quiz with Authorization: Bearer {token}:
   - data: JSON with title, dueDate, status=DRAFT, questions=[]
   - audioFile: MP3 file
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards to AssignmentService.
4. AssignmentService calls CloudinaryService.uploadAudio which throws RuntimeException ("Cloudinary connection timeout").
5. System propagates the uncaught runtime exception.

**Expected Results:** HTTP 500 INTERNAL SERVER ERROR

---

### Test Case ID: TC_AS_CLOUD_002
**Test Case Description:** Cloudinary upload image fail when submit writing → 400 UPLOAD_FAILED

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Published writing assignment
- Submission started (IN_PROGRESS)
- CloudinaryService mocked to throw RuntimeException ("Cloudinary upload failed")

**Test Case Procedure:**
1. Client sends multipart POST /api/v1/learner/assignments/{id}/submit-writing with Authorization: Bearer {token}:
   - data: JSON {"timeSpentSeconds": 60}
   - images: image file (PNG)
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards to AssignmentService.
4. AssignmentService calls CloudinaryService.uploadImage which throws RuntimeException.
5. System catches and wraps exception as BusinessException with UPLOAD_FAILED.
6. GlobalExceptionHandler returns 400 response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_CLOUD_003
**Test Case Description:** Submitting text with more than 10 images → 400 TOO_MANY_IMAGES

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Published writing assignment
- Submission started (IN_PROGRESS)

**Test Case Procedure:**
1. Client sends multipart POST /api/v1/learner/assignments/{id}/submit-writing with Authorization: Bearer {token}:
   - data: JSON {"timeSpentSeconds": 60}
   - images: 11 image files (exceeds MAX_SUBMISSION_IMAGES = 10)
2. AuthFilter validates token and resolves LEARNER identity.
3. AssignmentController forwards to AssignmentService.
4. AssignmentService detects image count > 10.
5. System throws BusinessException with TOO_MANY_IMAGES.
6. System returns the business error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_CLOUD_004
**Test Case Description:** Update the audio once the assignment has received submissions. → 400 HAS_SUBMISSIONS

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Published quiz assignment with existing learner submission

**Test Case Procedure:**
1. Client sends multipart PUT /api/v1/teacher/assignments/{id}/quiz with Authorization: Bearer {token}:
   - data: JSON with status=PUBLISHED, questions=[]
   - audioFile: new MP3 file
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards to AssignmentService.
4. AssignmentService detects that assignment already has submissions.
5. System throws BusinessException with HAS_SUBMISSIONS.
6. System returns the business error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

## Section 21 — Concurrency Tests

### Test Case ID: TC_AS_L_DO_CONCURRENCY_001
**Test Case Description:** Double-submit 'startAssignment' → only one submission is created.

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Course and class created
- Learner enrolled in class
- Published quiz assignment created


**Test Case Procedure:**
1. Two concurrent threads each call `assignmentService.startAssignment(assignmentId, learnerId)` simultaneously using a 2-thread ExecutorService + CountDownLatch.
2. Both threads wait for the latch signal to start at the same time.
3. Once latch is released, both threads execute concurrently.
4. One thread succeeds (returns true), the other catches BusinessException and returns false.
5. After both complete, verify database submission count.

**Expected Results:**
- `result1 XOR result2 = true` (exactly one thread succeeds)
- `submissionRepository.countByAssignmentIdAndUserId(assignmentId, learnerId) = 1`

---

## Section 8 — AI Explanation and Generation Tests

### Test Case ID: TC_AS_AI_006
**Test Case Description:** Explain question does not exist → 404

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- QuizExplanationService mocked to throw ResourceNotFoundException

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/questions/{questionId}/ai-explain with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to QuizExplanationService.
4. QuizExplanationService.generateExplanation() throws ResourceNotFoundException("Quiz question not found").
5. GlobalExceptionHandler handles ResourceNotFoundException and returns 404 response.

**Expected Results:** HTTP 404 NOT FOUND

---

### Test Case ID: TC_AS_AI_007
**Test Case Description:** AI explains how to save the explanation to the database.

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- QuizQuestion entity created with explanation=null and aiGenerated=false
- QuizExplanationService mocked to update and save the question

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/questions/{questionId}/ai-explain with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to QuizExplanationService.
4. QuizExplanationService.generateExplanation() updates question.setExplanation() and saves via QuizQuestionRepository.
5. System returns the updated question response with explanation.
6. Database contains the updated explanation for the question.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.explanation is not empty
Database verification:
- QuizQuestion.explanation = "Đáp án đúng là A vì..."
- QuizQuestion.aiGenerated = true (if set by service)

---

### Test Case ID: TC_AS_AI_008
**Test Case Description:** AI explain draft

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- QuizExplanationService mocked to return AiExplanationResponse

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/questions/ai-explain-draft with Authorization: Bearer {token} and JSON body:
   - questionText: "テスト"
   - questionType: "multiple_choice"
   - options: "A, B"
   - correctAnswer: "A"
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to QuizExplanationService.
4. QuizExplanationService.generateExplanationFromDraft() returns AiExplanationResponse with explanation text.
5. System returns the explanation response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.explanation = "Draft explanation text..."

---

### Test Case ID: TC_AS_AI_009
**Test Case Description:** AI provider throws exception → 400

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- QuizExplanationService mocked to throw BusinessException

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/questions/ai-explain-draft with Authorization: Bearer {token} and JSON body with draft question data.
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to QuizExplanationService.
4. QuizExplanationService.generateExplanationFromDraft() throws BusinessException("AI_RESPONSE_ERROR", "Failed to call AI: timeout").
5. GlobalExceptionHandler handles BusinessException and returns 400 response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_AI_010
**Test Case Description:** AI generate: service throw BusinessException → 400

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course and class created
- QuizAiGenerationService mocked to throw BusinessException

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/questions/ai-generate with Authorization: Bearer {token} and JSON body:
   - teacherPrompt: "N5 vocab"
   - classId: {classId}
   - questionCount: 1
   - questionTypes: ["multiple_choice"]
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to QuizAiGenerationService.
4. QuizAiGenerationService.generateQuestions() throws BusinessException("AI_PARSE_ERROR", "Failed to parse AI response.").
5. GlobalExceptionHandler handles BusinessException and returns 400 response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_AS_AI_011
**Test Case Description:** The AI generator returns an empty list of 200 with no data.

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Course and class created
- QuizAiGenerationService mocked to return empty list

**Test Case Procedure:**
1. Client sends POST /api/v1/teacher/assignments/questions/ai-generate with Authorization: Bearer {token} and JSON body with generation request.
2. AuthFilter validates token and resolves TEACHER identity.
3. AssignmentController forwards the request to QuizAiGenerationService.
4. QuizAiGenerationService.generateQuestions() returns empty List<QuizQuestion>.
5. System returns success response with empty data array.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.length() = 0
- data is an empty array

---

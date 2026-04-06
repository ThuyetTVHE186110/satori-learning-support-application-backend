# Practice with AI Integration Test Script

## Overview
This document contains detailed test scripts for all AI-powered practice session API endpoints in Satori Learning Support Application backend.

## Test Environment Setup

### Pre-conditions
- Test DB running via `application-test.properties`
- PostgreSQL `satori_test` database created and initialized
- Docker environment up and running
- ExerciseGeneratorAgent mocked to provide deterministic AI-generated exercises
- AnswerEvaluatorAgent mocked to provide deterministic answer evaluations
- SessionFeedbackAgent mocked to provide deterministic session feedback
- Course, Lesson, and Class entities pre-populated in database
- Test learners and teachers created with valid credentials

---

## Section 1 — Security & Authentication Tests (RBAC)

### Test Case ID: TC_PRC_SEC_001
**Test Case Description:** Reject request without JWT token (authentication required)

**Pre-conditions:**
- No authentication token provided
- Valid PracticeSession exists in database

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions without Authorization header.
2. AuthFilter detects missing token.
3. System returns the unauthorized error response.

**Expected Results:** HTTP 401 UNAUTHORIZED
Response JSON:
- success = false
- code = "UNAUTHORIZED"

---

### Test Case ID: TC_PRC_SEC_002
**Test Case Description:** Reject request from non-LEARNER role (TEACHER cannot access practice)

**Pre-conditions:**
- Teacher accessToken is available (from prior login with role=TEACHER)
- Valid Course and Lesson exist in database

**Test Case Procedure:**
1. Teacher user with role=TEACHER exists and is ACTIVE.
2. Teacher obtains accessToken via login at /api/v1/auth/login.
3. Client sends POST /api/v1/learner/practice/sessions with Authorization: Bearer {teacherToken} and JSON body:
   - lessonId: {lessonId}
   - sessionType: "MIXED_LESSON"
   - itemCount: 5
4. AuthFilter validates token and resolves TEACHER user identity.
5. SecurityConfig denies access due to insufficient permissions (RBAC).
6. System returns the access denied error response.

**Expected Results:** HTTP 403 FORBIDDEN
Response JSON:
- success = false
- code = "FORBIDDEN"

---

## Section 2 — View Lesson List Tests (UC 2.12.1)

### Test Case ID: TC_PRC_LESSONS_001
**Test Case Description:** Retrieve lesson list for enrolled course successfully

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Course exists in database (status=ACTIVE)
- Lesson exists in database (courseId matches the course, status=PUBLISHED)
- Learner is enrolled as ClassMember in a class belonging to the course

**Test Case Procedure:**
1. Client sends GET /api/v1/courses/{courseId}/lessons with Authorization: Bearer {learnerToken}.
2. AuthFilter validates token and resolves LEARNER user identity.
3. LessonController calls LessonService.getPublishedLessonsByCourseId(courseId).
4. LessonService queries LessonRepository for published lessons in the course.
5. LessonRepository returns paginated list of lessons.
6. System returns the lesson list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.length() = 1 (or count of lessons in course)
- data[0].id = lesson.id
- data[0].title = lesson.title
- data[0].status = "PUBLISHED"
Database state: Lesson persists with correct courseId and status

---

### Test Case ID: TC_PRC_LESSONS_002
**Test Case Description:** Retrieve lesson list for unenrolled course (public access allowed)

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Unenrolled Course exists in database (status=ACTIVE)
- Learner is NOT enrolled in any class of this course
- Lessons exist in the course

**Test Case Procedure:**
1. Client sends GET /api/v1/courses/{courseId}/lessons with Authorization: Bearer {learnerToken}.
2. AuthFilter validates token and resolves LEARNER user identity.
3. LessonController calls LessonService.getPublishedLessonsByCourseId(courseId).
4. LessonService queries LessonRepository (no enrollment check required for public GET).
5. LessonRepository returns lessons regardless of enrollment status.
6. System returns the lesson list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data is array (possibly empty if no lessons exist)
Database state: Lessons returned if they exist in the course

---

### Test Case ID: TC_PRC_LESSONS_003
**Test Case Description:** Retrieve lesson list for non-existent course returns empty list

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Requested courseId does not exist in database

**Test Case Procedure:**
1. Generate random UUID as invalidCourseId.
2. Client sends GET /api/v1/courses/{courseId}/lessons with Authorization: Bearer {learnerToken}.
3. AuthFilter validates token and resolves LEARNER user identity.
4. LessonController calls LessonService.getPublishedLessonsByCourseId(invalidCourseId).
5. LessonService queries LessonRepository (course check may not be strict).
6. LessonRepository returns empty list (no lessons found for non-existent courseId).
7. System returns empty lesson list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data = [] (empty array)
Database state: No lessons returned (course does not exist)

---

### Test Case ID: TC_PRC_LESSONS_004
**Test Case Description:** Retrieve lessons from course with no published lessons returns empty list

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Course exists in database (status=ACTIVE)
- No lessons exist in this course

**Test Case Procedure:**
1. Client sends GET /api/v1/courses/{courseId}/lessons with Authorization: Bearer {learnerToken}.
2. AuthFilter validates token and resolves LEARNER user identity.
3. LessonController calls LessonService.getPublishedLessonsByCourseId(courseId).
4. LessonService queries LessonRepository for lessons in course.
5. LessonRepository returns empty list (no lessons for this course).
6. System returns empty lesson list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data = [] (empty array)
Database state: No lessons exist for this course

---

## Section 3 — Config AI Practice Tests (UC 2.12.2)

### Test Case ID: TC_PRC_CONFIG_001
**Test Case Description:** Create practice session successfully with valid configuration

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Course exists in database (status=ACTIVE)
- Lesson exists in database (courseId matches the course, status=PUBLISHED)
- ExerciseGeneratorAgent mocked to return 5 GeneratedItem objects
- All required parameters are valid

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions with Authorization: Bearer {learnerToken} and JSON body:
   - lessonId: {lessonId}
   - sessionType: "MIXED_LESSON"
   - itemCount: 5
2. AuthFilter validates token and resolves LEARNER user identity.
3. PracticeController calls PracticeSessionService.startPracticeSession().
4. PracticeSessionService validates lessonId exists and is PUBLISHED.
5. PracticeSessionService calls ExerciseGeneratorAgent.generateItems(lesson, level, itemCount).
6. ExerciseGeneratorAgent returns list of 5 GeneratedItem objects.
7. PracticeSessionService creates new PracticeSession entity with status="IN_PROGRESS".
8. PracticeSessionService creates PracticeItem entities for each generated exercise.
9. PracticeSessionRepository saves session.
10. PracticeItemRepository saves items.
11. System returns the created session response with generated items.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.session.sessionId = non-empty UUID
- data.session.status = "IN_PROGRESS"
- data.items.length() = 5
Database state:
- PracticeSession created with userId=learner.id, status="IN_PROGRESS", totalItems=5
- 5 PracticeItem entities created with correct sessionId, itemType, and itemIndex

---

### Test Case ID: TC_PRC_CONFIG_002
**Test Case Description:** Reject practice session creation for non-existent lesson

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Requested lessonId does not exist in database
- itemCount = 5 (valid)

**Test Case Procedure:**
1. Generate random UUID as invalidLessonId.
2. Client sends POST /api/v1/learner/practice/sessions with Authorization: Bearer {learnerToken} and JSON body:
   - lessonId: {invalidLessonId}
   - sessionType: "MIXED_LESSON"
   - itemCount: 5
3. AuthFilter validates token and resolves LEARNER user identity.
4. PracticeSessionService validates lessonId existence.
5. PracticeSessionService does not find lesson in database.
6. PracticeSessionService throws ResourceNotFoundException.
7. GlobalExceptionHandler maps to API error response.
8. System returns the not found error response.

**Expected Results:** HTTP 400 BAD REQUEST (or 404 NOT FOUND)
Response JSON:
- success = false
- code = "LESSON_NOT_FOUND"
- message = "Không tìm thấy bài học. Vui lòng thử lại."
Database state: No PracticeSession created

---

### Test Case ID: TC_PRC_CONFIG_003
**Test Case Description:** Create two separate sessions on consecutive requests (BR-263)

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Course and Lesson exist in database
- ExerciseGeneratorAgent mocked to return generated items on each call

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions (Request 1) with valid body.
2. AuthFilter validates token.
3. PracticeSessionService creates first session with sessionId=UUID1.
4. System returns HTTP 201 with first session response.
5. Client sends POST /api/v1/learner/practice/sessions (Request 2) with same valid body.
6. AuthFilter validates token.
7. PracticeSessionService creates second session with sessionId=UUID2.
8. System returns HTTP 201 with second session response.
9. Two sessions are distinct (UUID1 != UUID2).

**Expected Results:** HTTP 201 CREATED for both requests
Response JSON (first): success=true, data.session.sessionId=UUID1
Response JSON (second): success=true, data.session.sessionId=UUID2
Database state:
- Two PracticeSession records exist with different IDs
- Both sessions have userId=learner.id, status="IN_PROGRESS"

---

### Test Case ID: TC_PRC_CONFIG_004
**Test Case Description:** Accept itemCount=1 (lower boundary valid value)

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Course and Lesson exist in database
- ExerciseGeneratorAgent mocked to return 1 GeneratedItem

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions with Authorization: Bearer {learnerToken} and JSON body:
   - lessonId: {lessonId}
   - sessionType: "MIXED_LESSON"
   - itemCount: 1
2. AuthFilter validates token.
3. Request validation passes (itemCount=1 is within valid range [1, 20]).
4. PracticeSessionService creates session.
5. ExerciseGeneratorAgent generates 1 item.
6. PracticeSessionService creates 1 PracticeItem.
7. System returns HTTP 201 with session response.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.items.length() = 1
Database state:
- PracticeSession created with totalItems=1
- 1 PracticeItem created

---

### Test Case ID: TC_PRC_CONFIG_005
**Test Case Description:** Reject itemCount=0 (below lower boundary)

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Course and Lesson exist in database

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions with Authorization: Bearer {learnerToken} and JSON body:
   - lessonId: {lessonId}
   - sessionType: "MIXED_LESSON"
   - itemCount: 0
2. AuthFilter validates token.
3. Request validation detects itemCount=0 is invalid (below minimum 1).
4. Validation fails at controller level.
5. System returns HTTP 400 BAD REQUEST.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "VALIDATION_ERROR" (or similar)
Database state: No PracticeSession created

---

### Test Case ID: TC_PRC_CONFIG_006
**Test Case Description:** Reject request with missing required parameters

**Pre-conditions:**
- Learner accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions with Authorization: Bearer {learnerToken} and JSON body:
   - itemCount: 5
   (missing lessonId and sessionType)
2. AuthFilter validates token.
3. Request validation detects missing required fields.
4. Validation fails at controller level (@Valid @RequestBody).
5. System returns HTTP 400 BAD REQUEST with field error details.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "VALIDATION_ERROR"
Database state: No PracticeSession created

---

### Test Case ID: TC_PRC_CONFIG_007
**Test Case Description:** Accept itemCount=20 (upper boundary valid value)

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Course and Lesson exist in database
- ExerciseGeneratorAgent mocked to return 20 GeneratedItems

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions with Authorization: Bearer {learnerToken} and JSON body:
   - lessonId: {lessonId}
   - sessionType: "MIXED_LESSON"
   - itemCount: 20
2. AuthFilter validates token.
3. Request validation passes (itemCount=20 is within valid range [1, 20]).
4. PracticeSessionService creates session.
5. ExerciseGeneratorAgent generates 20 items.
6. PracticeSessionService creates 20 PracticeItem entities.
7. System returns HTTP 201 with session response.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.items.length() = 20
Database state:
- PracticeSession created with totalItems=20
- 20 PracticeItem entities created

---

### Test Case ID: TC_PRC_CONFIG_008
**Test Case Description:** Reject itemCount=21 (above upper boundary)

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Course and Lesson exist in database

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions with Authorization: Bearer {learnerToken} and JSON body:
   - lessonId: {lessonId}
   - sessionType: "MIXED_LESSON"
   - itemCount: 21
2. AuthFilter validates token.
3. Request validation detects itemCount=21 is invalid (exceeds maximum 20).
4. Validation fails at controller level.
5. System returns HTTP 400 BAD REQUEST.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "VALIDATION_ERROR"
Database state: No PracticeSession created

---

## Section 4 — Complete AI Exercises Tests (UC 2.12.3)

### Test Case ID: TC_PRC_SUBMIT_001
**Test Case Description:** Learner submits final answer, session completes successfully (BR-265)

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- PracticeSession exists with status="IN_PROGRESS", totalItems=2, completedItems=1
- 2 PracticeItem entities exist:
  - Item 1: itemIndex=0, userAnswer="A", isCorrect=true
  - Item 2: itemIndex=1, userAnswer=null (unanswered)
- AnswerEvaluatorAgent mocked to return EvaluationResult(true, 100, "Good job!", null)
- SessionFeedbackAgent mocked to return "{}" (empty feedback JSON)

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer with Authorization: Bearer {learnerToken} and JSON body:
   - userAnswer: "A"
2. AuthFilter validates token and resolves LEARNER user identity.
3. PracticeSubmissionController calls PracticeSubmissionService.submitAnswer().
4. PracticeSubmissionService validates sessionId exists and belongs to learner.
5. PracticeSubmissionService validates itemId exists and belongs to session.
6. PracticeSubmissionService validates session status is "IN_PROGRESS".
7. AnswerEvaluatorAgent.evaluate(item, userAnswer) is called.
8. EvaluatorAgent returns EvaluationResult(true, 100, "Good job!", null).
9. PracticeSubmissionService updates PracticeItem with userAnswer="A", isCorrect=true, score=100.
10. PracticeSubmissionService increments session.completedItems = 2.
11. PracticeSubmissionService detects totalItems == completedItems and sets session.status="COMPLETED".
12. SessionFeedbackAgent.generateFeedback() is called for final feedback.
13. PracticeSessionRepository saves session.
14. PracticeItemRepository saves item.
15. System returns response with correct=true, feedback="Good job!", sessionCompleted=true.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.correct = true
- data.feedback = "Good job!"
- data.sessionCompleted = true
- data.score = 100
Database state:
- PracticeSession.status = "COMPLETED"
- PracticeSession.completedItems = 2
- PracticeItem.userAnswer = "A"
- PracticeItem.isCorrect = true
- PracticeItem.score = 100

---

### Test Case ID: TC_PRC_SUBMIT_002
**Test Case Description:** Learner submits partial answer (not final item), session remains IN_PROGRESS

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- PracticeSession exists with status="IN_PROGRESS", totalItems=5, completedItems=0
- 5 PracticeItem entities exist (all unanswered)
- AnswerEvaluatorAgent mocked to return EvaluationResult(false, 0, "Wrong", "A")

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer with Authorization: Bearer {learnerToken} and JSON body:
   - userAnswer: "B"
2. AuthFilter validates token.
3. PracticeSubmissionController calls PracticeSubmissionService.submitAnswer().
4. PracticeSubmissionService validates session and item.
5. AnswerEvaluatorAgent.evaluate(item, "B") is called.
6. EvaluatorAgent returns EvaluationResult(false, 0, "Wrong", "A").
7. PracticeSubmissionService updates PracticeItem with userAnswer="B", isCorrect=false, score=0.
8. PracticeSubmissionService increments session.completedItems = 1.
9. PracticeSubmissionService detects totalItems=5 > completedItems=1, keeps status="IN_PROGRESS".
10. PracticeSessionRepository saves session.
11. PracticeItemRepository saves item.
12. System returns response with correct=false, feedback="Wrong", sessionCompleted=false.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.correct = false
- data.feedback = "Wrong"
- data.sessionCompleted = false
- data.score = 0
Database state:
- PracticeSession.status = "IN_PROGRESS" (unchanged)
- PracticeSession.completedItems = 1
- PracticeItem.userAnswer = "B"
- PracticeItem.isCorrect = false
- PracticeItem.score = 0

---

### Test Case ID: TC_PRC_SUBMIT_003
**Test Case Description:** AI service timeout returns friendly error MSG-158

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- PracticeSession exists with status="IN_PROGRESS", totalItems=1, completedItems=0
- 1 PracticeItem exists (unanswered)
- AnswerEvaluatorAgent mocked to throw RuntimeException("OpenAI API Timeout Error")

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer with Authorization: Bearer {learnerToken} and JSON body:
   - userAnswer: "A"
2. AuthFilter validates token.
3. PracticeSubmissionController calls PracticeSubmissionService.submitAnswer().
4. PracticeSubmissionService validates session and item.
5. AnswerEvaluatorAgent.evaluate(item, "A") throws RuntimeException("OpenAI API Timeout Error").
6. PracticeSubmissionService catches exception.
7. PracticeSubmissionService does not update PracticeItem or PracticeSession.
8. System returns error response with code="INTERNAL_ERROR" (or similar).

**Expected Results:** HTTP 500 INTERNAL SERVER ERROR (or 400 depending on error handling)
Response JSON:
- success = false
- code = "INTERNAL_ERROR"
- message = "Hệ thống AI đang gặp sự cố, vui lòng thử lại sau."
Database state:
- PracticeSession.completedItems = 0 (unchanged)
- PracticeItem.userAnswer = null (unchanged)
- PracticeItem.isCorrect remains null (unchanged)

---

### Test Case ID: TC_PRC_SUBMIT_004
**Test Case Description:** Submit answer to non-existent session returns 404/400 error

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Requested sessionId does not exist in database
- Any valid itemId provided

**Test Case Procedure:**
1. Generate random UUID as invalidSessionId.
2. Client sends POST /api/v1/learner/practice/sessions/{invalidSessionId}/items/{itemId}/answer with Authorization: Bearer {learnerToken} and JSON body:
   - userAnswer: "A"
3. AuthFilter validates token.
4. PracticeSubmissionController calls PracticeSubmissionService.submitAnswer().
5. PracticeSubmissionService queries PracticeSessionRepository.findById(invalidSessionId).
6. Repository returns empty (no session found).
7. PracticeSubmissionService throws ResourceNotFoundException.
8. GlobalExceptionHandler maps to API error response.
9. System returns the not found error response.

**Expected Results:** HTTP 400 BAD REQUEST (or 404)
Response JSON:
- success = false
- code = "SESSION_NOT_FOUND"
- message = "Không tìm thấy phiên luyện tập."
Database state: No PracticeItem modified

---

### Test Case ID: TC_PRC_SUBMIT_005
**Test Case Description:** Submit answer with item ID from different session returns error

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- PracticeSession 1 exists with status="IN_PROGRESS"
- PracticeSession 2 exists with status="IN_PROGRESS"
- PracticeItem from Session 1 is itemId1
- Attempting to submit itemId1 to Session 2

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions/{sessionId2}/items/{itemId1}/answer with Authorization: Bearer {learnerToken} and JSON body:
   - userAnswer: "A"
2. AuthFilter validates token.
3. PracticeSubmissionController calls PracticeSubmissionService.submitAnswer().
4. PracticeSubmissionService validates sessionId2 exists.
5. PracticeSubmissionService validates itemId1 exists.
6. PracticeSubmissionService verifies itemId1.sessionId == sessionId2.
7. Validation fails (itemId1.sessionId != sessionId2).
8. PracticeSubmissionService throws BusinessException or validation error.
9. System returns error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "ITEM_SESSION_MISMATCH"
- message = "Câu hỏi không thuộc phiên luyện tập này."
Database state: No PracticeItem modified

---

### Test Case ID: TC_PRC_SUBMIT_006
**Test Case Description:** Attempt to submit answer to already COMPLETED session is rejected

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- PracticeSession exists with status="COMPLETED", totalItems=1, completedItems=1
- 1 PracticeItem exists with userAnswer already set

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer with Authorization: Bearer {learnerToken} and JSON body:
   - userAnswer: "B" (different from current answer)
2. AuthFilter validates token.
3. PracticeSubmissionController calls PracticeSubmissionService.submitAnswer().
4. PracticeSubmissionService validates sessionId and itemId.
5. PracticeSubmissionService checks session.status.
6. Detects session.status == "COMPLETED".
7. PracticeSubmissionService throws BusinessException(SESSION_COMPLETED, "Phiên luyện tập đã hoàn thành, không thể nộp thêm câu trả lời.").
8. GlobalExceptionHandler maps to API error response.
9. System returns the error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "SESSION_NOT_ACTIVE"
- message = "Phiên luyện tập đã kết thúc."
Database state: PracticeItem unchanged

---

### Test Case ID: TC_PRC_SUBMIT_007
**Test Case Description:** Learner A cannot submit answer to Learner B's session (access denied)

**Pre-conditions:**
- Learner accessToken is available (from prior login as learner)
- Other Learner exists (otherLearner)
- PracticeSession exists with userId=otherLearner.id, status="IN_PROGRESS"
- PracticeItem exists in that session
- Attempting access as learner (not otherLearner)

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer with Authorization: Bearer {learnerToken} and JSON body:
   - userAnswer: "A"
2. AuthFilter validates token and resolves learner user identity (userId1).
3. PracticeSubmissionController calls PracticeSubmissionService.submitAnswer().
4. PracticeSubmissionService validates sessionId exists.
5. PracticeSubmissionService verifies session.userId == authenticated userId1.
6. Validation fails (session.userId=otherLearner.id != userId1).
7. PracticeSubmissionService throws AccessDeniedException or SecurityException.
8. GlobalExceptionHandler maps to API error response.
9. System returns the access denied error response.

**Expected Results:** HTTP 400 BAD REQUEST (or 403 FORBIDDEN)
Response JSON:
- success = false
- code = "SESSION_ACCESS_DENIED"
- message = "Bạn không có quyền truy cập phiên luyện tập này."
Database state: No PracticeItem or PracticeSession modified

---

### Test Case ID: TC_PRC_SUBMIT_008
**Test Case Description:** Verify short-term storage strategy (BR-266)

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- PracticeSession exists with status="COMPLETED"
- Test setup complete

**Test Case Procedure:**
1. PracticeSession with COMPLETED status is created and saved to database.
2. Query PracticeSessionRepository.findById(sessionId).
3. Verify record exists in database (present initially).
4. (Optional) Wait for scheduled cleanup task (if implemented) or verify application allows short-term retention.
5. Assert that session is retrievable from database at query time.

**Expected Results:** Session record is present in database
Database state:
- PracticeSession with status="COMPLETED" persists in database
- May be subject to scheduled deletion/purge later (implementation-dependent)
- Present in DB confirms short-term storage is working

---

### Test Case ID: TC_PRC_SUBMIT_008
**Test Case Description:** Learner làm 5/10 câu rồi quit, quay lại tiếp tục vẫn giữ trạng thái (BR-????)

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- PracticeSession exists with status="IN_PROGRESS", totalItems=10, completedItems=0
- 10 PracticeItem entities exist (all unanswered)

**Test Case Procedure:**
1. **Phase 1 - First 5 submissions:**
   - Learner submits answers for items 0-4 (5 items)
   - Each submission: POST /api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer
   - All answers marked correct (100 points each)
   
2. **Verify state after 5 submissions:**
   - Query session from database
   - Assert: status = "IN_PROGRESS" (not completed)
   - Assert: completedItems = 5
   - Assert: totalItems = 10 (unchanged)

3. **Phase 2 - Session interruption:**
   - [Simulate learner quitting app or session timeout]
   - [Learner returns and resumes session]

4. **Phase 3 - Resume and complete remaining 5 items:**
   - Learner submits answers for items 5-9 (remaining 5 items)
   - Each submission: POST /api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer
   - Session still remembers completedItems=5, resumes from item 5

5. **Verify final state:**
   - Query session from database
   - Assert: status = "COMPLETED"
   - Assert: completedItems = 10
   - Assert: totalItems = 10
   - Query all items: verify all 10 have userAnswer set

**Expected Results:** HTTP 200 OK for all 10 submissions
- Phase 1 (items 0-4): success=true, 5 items answered
- Phase 3 (items 5-9): success=true, remaining items answered
- Final state: completedItems=10, status=COMPLETED

Database state:
- PracticeSession.completedItems progression: 0 → 5 → 10
- PracticeSession.status: IN_PROGRESS (after 5) → COMPLETED (after 10)
- PracticeItem: All 10 items have userAnswer set
- Score accumulates across sessions (5*100 + 5*100 = 1000 or avg/etc based on formula)

### Test Case A1 — Correct Answer Evaluation

### Test Case ID: TC_PRC_ALT_A1_001
**Test Case Description:** Evaluator confirms correct answer with full score

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- PracticeSession exists with status="IN_PROGRESS"
- PracticeItem exists with correctAnswer="A"
- AnswerEvaluatorAgent mocked to return EvaluationResult(true, 100, "Chính xác!", null)

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer with Authorization: Bearer {learnerToken} and JSON body:
   - userAnswer: "A"
2. AuthFilter validates token.
3. PracticeSubmissionService calls AnswerEvaluatorAgent.evaluate(item, "A").
4. EvaluatorAgent returns EvaluationResult(true, 100, "Chính xác!", null).
5. PracticeSubmissionService updates PracticeItem with isCorrect=true, score=100.
6. System returns response with correct=true, score=100.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.correct = true
- data.score = 100
- data.feedback = "Chính xác!"
Database state:
- PracticeItem.isCorrect = true
- PracticeItem.score = 100

---

### Test Case A2 — Incorrect Answer with Explanation

### Test Case ID: TC_PRC_ALT_A2_001
**Test Case Description:** Evaluator identifies incorrect answer and provides explanation

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- PracticeSession exists with status="IN_PROGRESS"
- PracticeItem exists with correctAnswer="A"
- AnswerEvaluatorAgent mocked to return EvaluationResult(false, 0, "Sai! Đáp án đúng là A", "A")

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer with Authorization: Bearer {learnerToken} and JSON body:
   - userAnswer: "B"
2. AuthFilter validates token.
3. PracticeSubmissionService calls AnswerEvaluatorAgent.evaluate(item, "B").
4. EvaluatorAgent returns EvaluationResult(false, 0, "Sai! Đáp án đúng là A", "A").
5. PracticeSubmissionService updates PracticeItem with isCorrect=false, score=0.
6. System returns response with correct=false, feedback including explanation.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.correct = false
- data.score = 0
- data.feedback = "Sai! Đáp án đúng là A"
Database state:
- PracticeItem.isCorrect = false
- PracticeItem.score = 0

---

### Test Case A3 — Partial Credit Scoring (IT-20: MATCHING)

### Test Case ID: TC_PRC_ALT_A3_001
**Test Case Description:** Evaluator awards partial credit for MATCHING with some pairs correct

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- PracticeSession exists with status="IN_PROGRESS", totalItems=1, completedItems=0
- PracticeItem exists with type=MATCHING, 4 pairs to match:
  - correctAnswer: "0:4,1:5,2:6,3:7" (left index : right index)
- AnswerEvaluatorAgent mocked to return EvaluationResult(false, 50, "Đúng 2/4 cặp", "0:4,1:5,2:6,3:7")

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer with Authorization: Bearer {learnerToken} and JSON body:
   - userAnswer: "0:4,1:5,2:8,3:9" (learner matches pairs, correct first 2, incorrect last 2)
2. AuthFilter validates token.
3. PracticeSubmissionService validates sessionId and itemId.
4. PracticeSubmissionService calls AnswerEvaluatorAgent.evaluate(item, "0:4,1:5,2:8,3:9").
5. EvaluatorAgent logic for MATCHING:
   - Parse userAnswer: {0→4, 1→5, 2→8, 3→9}
   - Parse correctAnswer: {0→4, 1→5, 2→6, 3→7}
   - Compare pairs: 0→4 ✅, 1→5 ✅, 2→8 ❌, 3→9 ❌
   - Calculate score: 2 correct / 4 total = 50
   - Return EvaluationResult(false, 50, "Đúng 2/4 cặp", ...)
6. PracticeSubmissionService updates PracticeItem with:
   - userAnswer = "0:4,1:5,2:8,3:9"
   - isCorrect = false (not all pairs correct)
   - score = 50 (partial score preserved)
   - aiFeedback = "Đúng 2/4 cặp"
7. PracticeSubmissionService increments session.completedItems = 1.
8. PracticeSubmissionService detects totalItems=1 == completedItems=1, but isCorrect=false.
9. Session status may be updated based on implementation (IN_PROGRESS or COMPLETED).
10. System returns response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.correct = false
- data.score = 50 (gradient score, not binary 0/100)
- data.feedback = "Đúng 2/4 cặp"
- data.correctAnswer = "0:4,1:5,2:6,3:7"
Database state:
- PracticeItem.itemType = MATCHING
- PracticeItem.userAnswer = "0:4,1:5,2:8,3:9"
- PracticeItem.isCorrect = false
- PracticeItem.score = 50 (gradient, verifies partial credit storage)
- PracticeSession.status = COMPLETED (all items answered: 1/1)
- PracticeSession.completedItems = 1

**Note:** MATCHING is the only item type in the system that supports true partial credit (gradient scores 0-100). MULTIPLE_CHOICE, TRUE_FALSE always return binary (0 or 100). FILL_BLANK, TRANSLATION, SENTENCE_ORDER may support partial credit if LLM evaluation is used, but rule-based evaluation returns binary.

---

## Section 6 — Session Feedback Tests

### Test Case ID: TC_PRC_FEEDBACK_001
**Test Case Description:** Generate and verify feedback content upon session completion (IT-21)

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- PracticeSession exists with status="IN_PROGRESS", totalItems=2, completedItems=1
- 2 PracticeItem entities exist with scores [100, null]
- Final answer being submitted will complete the session
- AnswerEvaluatorAgent mocked to return successful evaluation (correct, 100 points)
- SessionFeedbackAgent mocked to return feedback JSON:
  ```json
  {"summary":"Bạn làm rất tốt","strengths":["Nhanh"],"weaknesses":[],"nextLessonSuggestion":"Học tiếp bài N5"}
  ```

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer with Authorization: Bearer {learnerToken} (final item).
2. AuthFilter validates token and resolves LEARNER user identity.
3. PracticeSubmissionService validates sessionId and itemId.
4. AnswerEvaluatorAgent.evaluate(item, userAnswer) returns successful result (true, 100, "Excellent!", null).
5. PracticeSubmissionService updates PracticeItem with userAnswer, isCorrect=true, score=100.
6. PracticeSubmissionService increments session.completedItems = 2.
7. PracticeSubmissionService detects completedItems (2) == totalItems (2), session completes.
8. finishSession() sets session.status="COMPLETED", calculates final score (100.0).
9. PracticeSessionRepository saves completed session.
10. Controller calls generateAndSaveFeedbackAsync(sessionId) asynchronously (does not block HTTP response).
11. System returns response immediately with sessionCompleted=true, correct=true, feedback="Excellent!".
12. In background, SessionFeedbackAgent.generateFeedback() is called with session data.
13. SessionFeedbackAgent returns feedback JSON.
14. generateAndSaveFeedback() persists feedback JSON to session.aiFeedback field.

**Expected Results:** HTTP 200 OK
Response JSON (immediate response, before async feedback generation):
- success = true
- data.sessionCompleted = true
- data.correct = true
- data.score = 100
- data.feedback = "Excellent!" (from evaluator, not session feedback)
Database state:
- PracticeSession.status = "COMPLETED"
- PracticeSession.completedItems = 2
- PracticeSession.score = 100.0 (or similar based on calculation)
- PracticeSession.aiFeedback = will be populated asynchronously with JSON containing summary, strengths, weaknesses, nextLessonSuggestion

**Note:** Session feedback is generated asynchronously after the transaction commits, so it may not be immediately visible in the HTTP response. The test verifies:
1. Session completion is successful
2. AnswerEvaluator feedback is returned in response
3. Session status is persisted as COMPLETED
4. AsyncService is triggered for feedback generation (can verify via repository query after delay, or via mock verification)

---

## Section 7 — Database State Verification Tests

### Test Case ID: TC_PRC_DBSTATE_001
**Test Case Description:** Verify correct persistence of practice session and items

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Course and Lesson exist

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions with valid body.
2. AuthFilter validates token.
3. PracticeSessionService creates session and items.
4. System returns HTTP 201 with session response.
5. Test queries PracticeSessionRepository and PracticeItemRepository directly.
6. Verifies correct data persistence.

**Expected Results:** HTTP 201 CREATED
Database state:
- PracticeSession row exists with:
  - id = non-null UUID
  - userId = learner.id
  - courseId = course.id
  - lessonId = lesson.id
  - sessionType = "MIXED_LESSON"
  - status = "IN_PROGRESS"
  - totalItems = itemCount
  - completedItems = 0
  - score = 0 or null
  - createdAt = not null
- PracticeItem rows (itemCount count):
  - sessionId = session.id
  - itemType = generated type
  - itemIndex = 0..itemCount-1
  - question = generated question
  - options = generated options (JSON)
  - correctAnswer = generated answer
  - userAnswer = null (initially)
  - isCorrect = null (initially)
  - score = 0 or null (initially)

---

### Test Case ID: TC_PRC_DBSTATE_002
**Test Case Description:** Verify correct persistence of answer submission

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- PracticeSession exists with 1 PracticeItem (unanswered)
- AnswerEvaluatorAgent mocked to return successful evaluation

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer with valid body.
2. AuthFilter validates token.
3. PracticeSubmissionService evaluates answer.
4. System returns HTTP 200.
5. Test queries PracticeItemRepository directly.
6. Verifies correct data persistence.

**Expected Results:** HTTP 200 OK
Database state:
- PracticeItem row updated with:
  - userAnswer = submitted answer
  - isCorrect = evaluation result boolean
  - score = evaluation score
  - updatedAt = current timestamp
- PracticeSession row updated with:
  - completedItems = incremented
  - updatedAt = current timestamp

---

## Test Environment Teardown

After each test:
- All PracticeItem entities are deleted
- All PracticeSession entities are deleted
- All Lesson entities are deleted
- All ClassMember entities are deleted
- All Class entities are deleted
- All Course entities are deleted
- All User session entities are deleted
- All User entities are deleted
- All mocked agents are reset


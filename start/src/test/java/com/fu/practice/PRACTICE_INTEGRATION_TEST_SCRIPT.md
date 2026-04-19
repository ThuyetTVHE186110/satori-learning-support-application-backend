# Practice Integration Test Script

## Overview
This document contains detailed test scripts for all practice API endpoints in Satori Learning Support Application backend.

## Test Environment Setup

### Pre-conditions
- Test DB running via `application-test.properties`
- PostgreSQL `satori_test` database created and initialized
- Docker environment up and running
- EmailService mocked to prevent actual email sending
- ExerciseGeneratorAgent mocked to return controlled exercise items
- SessionFeedbackAgent mocked to return controlled feedback

---

## Section 0 — RBAC Guard Tests (Learner-Only Access)

### Test Case ID: TC_PAC_001
**Test Case Description:** Unauthenticated request to start practice session → returns 401 UNAUTHORIZED

**Pre-conditions:**
- No authentication token provided

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions without Authorization header and body:
   - lessonId: {publishedLesson.getId()}
   - sessionType: "VOCAB_DRILL"
2. AuthFilter processes request and detects missing token in Authorization header.
3. AuthFilter returns 401 error response before reaching PracticeController.
4. System returns the unauthorized error response.

**Expected Results:** HTTP 401 UNAUTHORIZED

---

### Test Case ID: TC_PAC_002
**Test Case Description:** CONTENT_MANAGER requests learner-only endpoint → returns 403 FORBIDDEN

**Pre-conditions:**
- CONTENT_MANAGER accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions with Authorization: Bearer {managerToken} and body:
   - lessonId: {publishedLesson.getId()}
   - sessionType: "VOCAB_DRILL"
2. AuthFilter validates token and resolves CONTENT_MANAGER user identity.
3. SecurityConfig checks role annotation @PreAuthorize("hasRole('LEARNER')") on PracticeController.
4. SecurityConfig denies access due to insufficient permissions (CONTENT_MANAGER role ≠ LEARNER).
5. PracticeController is NOT reached.
6. System returns the access denied error response.

**Expected Results:** HTTP 403 FORBIDDEN

---

### Test Case ID: TC_PAC_003
**Test Case Description:** TEACHER requests learner-only endpoint → returns 403 FORBIDDEN

**Pre-conditions:**
- TEACHER accessToken is available (from prior login)
- Lesson exists in database

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/practice/lessons/{lessonId}/items with Authorization: Bearer {teacherToken}.
2. AuthFilter validates token and resolves TEACHER user identity.
3. SecurityConfig checks role annotation @PreAuthorize("hasRole('LEARNER')") on endpoint.
4. SecurityConfig denies access due to insufficient permissions (TEACHER role ≠ LEARNER).
5. PracticeController is NOT reached.
6. System returns the access denied error response.

**Expected Results:** HTTP 403 FORBIDDEN

---

### Test Case ID: TC_PAC_004
**Test Case Description:** ADMIN requests learner-only endpoint → returns 403 FORBIDDEN

**Pre-conditions:**
- ADMIN accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/practice/sessions/{sessionId}/summary with Authorization: Bearer {adminToken}.
2. AuthFilter validates token and resolves ADMIN user identity.
3. SecurityConfig checks role annotation @PreAuthorize("hasRole('LEARNER')") on endpoint.
4. SecurityConfig denies access due to insufficient permissions (ADMIN role ≠ LEARNER).
5. PracticeController is NOT reached.
6. System returns the access denied error response.

**Expected Results:** HTTP 403 FORBIDDEN

---

## Section 1 — View Lesson List Tests (GET /api/v1/courses/{courseId}/lessons)

### Test Case ID: TC_PVL_001
**Test Case Description:** Learner retrieves published lessons from course → all 3 lessons returned with required fields

**Pre-conditions:**
- Learner is authenticated
- Course exists with 3 PUBLISHED lessons (lesson 1 from setUp, lesson 2, lesson 3)

**Test Case Procedure:**
1. Client sends GET /api/v1/courses/{courseId}/lessons with Authorization: Bearer {learnerToken}.
2. AuthFilter validates token and resolves LEARNER user identity.
3. CourseController calls CourseService.getLessonsByCourseId() with courseId.
4. CourseService retrieves lessons via LessonRepository with ContentStatus.PUBLISHED filter and soft-delete filter (deletedAt IS NULL).
5. LessonRepository executes database query and returns 3 PUBLISHED lessons.
6. System returns the list of published lessons with required fields.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.length() = 3
- Each item in data contains:
  - id = non-empty UUID
  - title = non-blank string

---

### Test Case ID: TC_PVL_002
**Test Case Description:** Learner retrieves lesson list with mixed statuses → only PUBLISHED lessons returned (BR-260)

**Pre-conditions:**
- Learner is authenticated
- Course contains:
  - 1 PUBLISHED lesson
  - 1 DRAFT lesson
  - 1 ARCHIVED lesson

**Test Case Procedure:**
1. Client sends GET /api/v1/courses/{courseId}/lessons with Authorization: Bearer {learnerToken}.
2. AuthFilter validates token and resolves LEARNER user identity.
3. CourseController calls CourseService.getLessonsByCourseId() with courseId.
4. CourseService retrieves lessons via LessonRepository with PUBLISHED filter only.
5. LessonRepository executes database query with ContentStatus.PUBLISHED filter.
6. LessonRepository returns only PUBLISHED lessons, excluding DRAFT and ARCHIVED.
7. System returns filtered list.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.length() = 1
- data[0].title = "Lesson 1 — Greetings"

---

### Test Case ID: TC_PVL_003
**Test Case Description:** Learner retrieves lesson list with soft-deleted lesson → soft-deleted lesson not returned

**Pre-conditions:**
- Learner is authenticated
- Course has 1 PUBLISHED lesson that is soft-deleted (deletedAt is not null)

**Test Case Procedure:**
1. Client sends GET /api/v1/courses/{courseId}/lessons with Authorization header.
2. AuthFilter validates token and resolves LEARNER user identity.
3. CourseController calls CourseService.getLessonsByCourseId().
4. CourseService retrieves lessons via LessonRepository with soft-delete filter (deletedAt IS NULL).
5. LessonRepository executes database query and returns no lessons (deleted lesson excluded).
6. System returns empty list.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.length() = 0

---

### Test Case ID: TC_PVL_004
**Test Case Description:** Learner retrieves lesson list from empty course → empty list returned

**Pre-conditions:**
- Learner is authenticated
- Course exists but has no lessons

**Test Case Procedure:**
1. Client sends GET /api/v1/courses/{courseId}/lessons with Authorization header.
2. AuthFilter validates token and resolves LEARNER user identity.
3. CourseController calls CourseService.getLessonsByCourseId().
4. CourseService retrieves lessons via LessonRepository with ContentStatus.PUBLISHED filter.
5. LessonRepository executes database query and returns empty list (no lessons exist).
6. System returns empty list.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.length() = 0

---

### Test Case ID: TC_PVL_005
**Test Case Description:** Learner retrieves lessons from non-existent course → empty list returned (no crash)

**Pre-conditions:**
- Learner is authenticated
- Course ID does not exist in database

**Test Case Procedure:**
1. Client sends GET /api/v1/courses/{nonExistentId}/lessons with Authorization header.
2. AuthFilter validates token and resolves LEARNER user identity.
3. CourseController calls CourseService.getLessonsByCourseId() with non-existent courseId.
4. CourseService retrieves lessons via LessonRepository with course filter.
5. LessonRepository executes database query for non-existent course and returns empty list.
6. System returns empty list (no crash).

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.length() = 0

---

## Section 2 — Lesson Items Picker Tests (GET /api/v1/learner/practice/lessons/{id}/items)

### Test Case ID: TC_PLI_001
**Test Case Description:** Learner retrieves lesson items → vocabulary and grammar items returned with summary

**Pre-conditions:**
- Learner is authenticated
- Learner is enrolled in the course
- Lesson contains:
  - 2 vocabulary items
  - 1 grammar item

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/practice/lessons/{lessonId}/items with Authorization: Bearer {learnerToken}.
2. AuthFilter validates token and resolves LEARNER user identity.
3. PracticeController validates learner enrollment via ClassRepository.findByClassIdAndUserId().
4. PracticeController calls PracticeService.getLessonItems().
5. PracticeService retrieves vocabulary items via LessonVocabularyRepository.
6. PracticeService retrieves grammar items via LessonGrammarRepository.
7. PracticeService builds summary with totalVocab and totalGrammar counts.
8. System returns vocabulary list, grammar list, and summary counts.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.vocabulary = array
- data.grammar = array
- data.summary.totalVocab = 2
- data.summary.totalGrammar = 1
- Each vocabulary item contains:
  - id = non-empty UUID
  - japaneseText = non-blank string
  - reading = non-blank string
  - meaningVi = non-blank string

---

### Test Case ID: TC_PLI_002
**Test Case Description:** Learner filters lesson items by masteryLevel=0 → only unpracticed items returned

**Pre-conditions:**
- Learner is authenticated
- Learner is enrolled in the course
- Lesson items have no mastery records (effectiveLevel=0)

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/practice/lessons/{lessonId}/items?masteryLevel=0 with Authorization: Bearer {learnerToken}.
2. AuthFilter validates token and resolves LEARNER user identity.
3. PracticeController validates learner enrollment via ClassRepository.
4. PracticeController calls PracticeService.getLessonItems() with masteryLevel filter.
5. PracticeService retrieves items and filters via ItemMasteryRepository.
6. ItemMasteryRepository executes database query with masteryLevel=0 filter.
7. System returns items matching the filter.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.vocabulary.length() = 2
- data.grammar.length() = 1

---

### Test Case ID: TC_PLI_003
**Test Case Description:** Learner filters lesson items by multiple masteryLevel values → OR filter logic works correctly

**Pre-conditions:**
- Learner is authenticated
- Learner is enrolled in the course
- Lesson items have no mastery records (level 0)

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/practice/lessons/{lessonId}/items?masteryLevel=0&masteryLevel=1 with Authorization: Bearer {learnerToken}.
2. AuthFilter validates token and resolves LEARNER user identity.
3. PracticeController validates learner enrollment via ClassRepository.
4. PracticeController calls PracticeService.getLessonItems() with multi-value masteryLevel filter.
5. PracticeService retrieves items and applies OR logic for masteryLevel values via ItemMasteryRepository.
6. ItemMasteryRepository executes database query with masteryLevel IN (0, 1) filter.
7. System returns items with effectiveLevel matching 0 or 1.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.vocabulary.length() = 2

---

### Test Case ID: TC_PLI_004
**Test Case Description:** Learner attempts to access lesson from unenrolled course → returns 4xx error

**Pre-conditions:**
- Learner is authenticated
- Learner is enrolled in Course A
- Lesson exists in Course B (not enrolled)

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/practice/lessons/{lessonIdInCourseB}/items with Authorization: Bearer {learnerToken}.
2. AuthFilter validates token and resolves LEARNER user identity.
3. PracticeController validates learner enrollment via ClassRepository.findByClassIdAndUserId().
4. ClassRepository executes database query and returns no enrollment record (learner not in Course B).
5. PracticeController throws enrollment validation exception.
6. System returns 4xx error response.

**Expected Results:** HTTP 4XX CLIENT ERROR
Response JSON:
- success = false

---

### Test Case ID: TC_PLI_005
**Test Case Description:** Learner retrieves items from non-existent lesson → returns 4xx error

**Pre-conditions:**
- Learner is authenticated
- Lesson ID does not exist in database

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/practice/lessons/{nonExistentId}/items with Authorization: Bearer {learnerToken}.
2. AuthFilter validates token and resolves LEARNER user identity.
3. PracticeController validates learner enrollment via ClassRepository.
4. ClassRepository executes database query with non-existent lesson ID and returns no enrollment.
5. PracticeController calls PracticeService.getLessonItems() with non-existent lessonId.
6. PracticeService retrieves lesson via LessonRepository.findById().
7. LessonRepository returns empty (lesson not found).
8. PracticeService throws LESSON_NOT_FOUND exception.
9. System returns 4xx error response.

**Expected Results:** HTTP 4XX CLIENT ERROR
Response JSON:
- success = false

---

## Section 3 — Start Practice Session Tests (POST /api/v1/learner/practice/sessions)

### Test Case ID: TC_PSS_001
**Test Case Description:** Learner starts VOCAB_DRILL session with QUICK preset → 5 items created with IN_PROGRESS status (BR-365)

**Pre-conditions:**
- Learner is authenticated
- Learner is enrolled in the course
- Lesson exists
- ExerciseGeneratorAgent mocked to return 5 items

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions with Authorization: Bearer {learnerToken} and body:
   - lessonId: {publishedLesson.getId()}
   - sessionType: "VOCAB_DRILL"
   - preset: "QUICK"
2. AuthFilter validates token and resolves LEARNER user identity.
3. PracticeController validates learner enrollment via ClassRepository.
4. ClassRepository returns enrollment record (learner enrolled in course).
5. PracticeController calls PracticeService.startSession() with itemCount=5 (QUICK preset).
6. PracticeService calls ExerciseGeneratorAgent.generateItems() with VOCAB_DRILL params.
7. ExerciseGeneratorAgent returns 5 GeneratedItem objects with itemType=MULTIPLE_CHOICE.
8. PracticeService creates PracticeSession entity with status=IN_PROGRESS, totalItems=5, completedItems=0.
9. PracticeService creates 5 PracticeItem entities linked to the session with itemIndex 0-4.
10. PracticeSessionRepository.save() and PracticeItemRepository.save() persist the data.
11. System returns session object and items array.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.session.sessionId = non-empty UUID
- data.session.status = "IN_PROGRESS"
- data.items.length() = 5
Database state:
- PracticeSession created with status=IN_PROGRESS, totalItems=5, completedItems=0, explicitMode=false
- 5 PracticeItem rows created with itemIndex 0-4

---

### Test Case ID: TC_PSS_002
**Test Case Description:** Learner starts GRAMMAR_DRILL session with STANDARD preset → 10 items created (BR-365)

**Pre-conditions:**
- Learner is authenticated
- Learner is enrolled in the course
- ExerciseGeneratorAgent mocked to return 10 items

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions with Authorization: Bearer {learnerToken} and body:
   - lessonId: {publishedLesson.getId()}
   - sessionType: "GRAMMAR_DRILL"
   - preset: "STANDARD"
2. AuthFilter validates token and resolves LEARNER user identity.
3. PracticeController validates learner enrollment via ClassRepository.
4. PracticeController calls PracticeService.startSession() with itemCount=10 (STANDARD preset).
5. PracticeService calls ExerciseGeneratorAgent.generateItems() with GRAMMAR_DRILL params.
6. ExerciseGeneratorAgent returns 10 GeneratedItem objects.
7. PracticeService creates PracticeSession and 10 PracticeItem entities.
8. System returns session and items array.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.items.length() = 10

---

### Test Case ID: TC_PSS_003
**Test Case Description:** Learner starts MIXED_LESSON session with FULL preset → 20 items created (BR-365)

**Pre-conditions:**
- Learner is authenticated
- ExerciseGeneratorAgent mocked to return 20 items

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions with Authorization: Bearer {learnerToken} and body:
   - lessonId: {publishedLesson.getId()}
   - sessionType: "MIXED_LESSON"
   - preset: "FULL"
2. AuthFilter validates token and resolves LEARNER user identity.
3. PracticeController validates learner enrollment via ClassRepository.
4. PracticeController calls PracticeService.startSession() with itemCount=20 (FULL preset).
5. PracticeService calls ExerciseGeneratorAgent.generateItems() with MIXED_LESSON params.
6. ExerciseGeneratorAgent returns 20 GeneratedItem objects.
7. PracticeService creates PracticeSession and 20 PracticeItem entities.
8. System returns session and items array.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.items.length() = 20

---

### Test Case ID: TC_PSS_004
**Test Case Description:** Learner starts practice with selectedVocabIds → session marked explicitMode=true (BR-366)

**Pre-conditions:**
- Learner is authenticated
- Learner is enrolled in the course
- Lesson contains 2 vocabulary items
- selectedVocabIds contains 1 vocabulary ID
- ExerciseGeneratorAgent mocked to return 1 item

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions with Authorization: Bearer {learnerToken} and body:
   - lessonId: {publishedLesson.getId()}
   - sessionType: "VOCAB_DRILL"
   - selectedVocabIds: [vocabularyId]
2. AuthFilter validates token and resolves LEARNER user identity.
3. PracticeController validates learner enrollment via ClassRepository.
4. PracticeController detects selectedVocabIds presence (not null/empty).
5. PracticeController calls PracticeService.startSession() with selectedVocabIds.
6. PracticeService sets explicitMode=true on session (due to selectedVocabIds present).
7. PracticeService calls ExerciseGeneratorAgent.generateItems() with selectedVocabIds filter.
8. ExerciseGeneratorAgent returns 1 GeneratedItem filtered for selected vocabulary.
9. PracticeService creates PracticeSession with explicitMode=true.
10. System returns session with explicitMode=true.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
Database state:
- PracticeSession.isExplicitMode = true (is_explicit_mode column via @Getter boolean)

---

### Test Case ID: TC_PSS_005
**Test Case Description:** Learner starts session without lessonId → returns 400 BAD REQUEST (@NotNull)

**Pre-conditions:**
- Learner is authenticated

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions with Authorization: Bearer {learnerToken} and body:
   - sessionType: "VOCAB_DRILL"
   - (no lessonId)
2. AuthFilter validates token and resolves LEARNER user identity.
3. Request validation fails at controller level due to @NotNull constraint on lessonId field.
4. GlobalExceptionHandler catches MethodArgumentNotValidException.
5. System returns validation error response before reaching PracticeService.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false

---

### Test Case ID: TC_PSS_006
**Test Case Description:** Learner starts session without sessionType → returns 400 BAD REQUEST (@NotNull)

**Pre-conditions:**
- Learner is authenticated

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions with Authorization: Bearer {learnerToken} and body:
   - lessonId: {publishedLesson.getId()}
   - (no sessionType)
2. AuthFilter validates token and resolves LEARNER user identity.
3. Request validation fails at controller level due to @NotNull constraint on sessionType field.
4. GlobalExceptionHandler catches MethodArgumentNotValidException.
5. System returns validation error response before reaching PracticeService.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false

---

### Test Case ID: TC_PSS_007
**Test Case Description:** Learner selects > 50 vocabulary items → returns 400 BAD REQUEST (@Size max=50)

**Pre-conditions:**
- Learner is authenticated

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions with Authorization: Bearer {learnerToken} and body:
   - lessonId: {publishedLesson.getId()}
   - sessionType: "VOCAB_DRILL"
   - selectedVocabIds: [51 UUID items]
2. AuthFilter validates token and resolves LEARNER user identity.
3. Request validation fails at controller level due to @Size constraint (max=50) on selectedVocabIds field.
4. GlobalExceptionHandler catches MethodArgumentNotValidException.
5. System returns validation error response before reaching PracticeService.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false

---

### Test Case ID: TC_PSS_008
**Test Case Description:** Learner starts practice for unenrolled course lesson → returns 4xx NOT_ENROLLED

**Pre-conditions:**
- Learner is authenticated
- Learner is enrolled only in Course A
- Lesson exists in Course B (not enrolled)

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions with Authorization: Bearer {learnerToken} and body:
   - lessonId: {lessonInCourseB.getId()}
   - sessionType: "VOCAB_DRILL"
2. AuthFilter validates token and resolves LEARNER user identity.
3. PracticeController validates learner enrollment via ClassRepository.findByClassIdAndUserId().
4. ClassRepository executes database query with learner's enrolled course and returns no enrollment (learner not in Course B).
5. PracticeController/PracticeService throws 4xx error (NOT_ENROLLED or FORBIDDEN).
6. System returns error response.

**Expected Results:** HTTP 4XX CLIENT ERROR
Response JSON:
- success = false

---

## Section 4 — Item Format per Exercise Type Tests

### Test Case ID: TC_PIF_001
**Test Case Description:** MULTIPLE_CHOICE exercise returns 4 options without exposing isCorrect before submit

**Pre-conditions:**
- Learner is authenticated (accessToken available)
- ExerciseGeneratorAgent mocked to return 1 MULTIPLE_CHOICE item with 4 options

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions with Authorization: Bearer {learnerToken} and body:
   - lessonId: {publishedLesson.getId()}
   - sessionType: "VOCAB_DRILL"
   - itemTypes: ["MULTIPLE_CHOICE"]
2. AuthFilter validates token and resolves LEARNER user identity.
3. PracticeController calls PracticeService.startSession() with itemTypes filter.
4. PracticeService validates learner enrollment via ClassRepository.
5. PracticeService calls ExerciseGeneratorAgent.generateItems() with itemTypes=["MULTIPLE_CHOICE"].
6. ExerciseGeneratorAgent returns 1 GeneratedItem with itemType=MULTIPLE_CHOICE and 4 options.
7. PracticeService creates PracticeSession and PracticeItem entities from GeneratedItem.
8. ItemFormatter.buildSelectionItem() maps options, excluding isCorrect (null → @JsonInclude(NON_NULL)).
9. System returns session and items array with formatted options.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.items[0].itemType = "MULTIPLE_CHOICE"
- data.items[0].question = non-blank string
- data.items[0].options.length() = 4
- Each option contains:
  - id = integer >= 0
  - text = non-blank string
  - isCorrect = NOT present (null → stripped by @JsonInclude(NON_NULL))

---

### Test Case ID: TC_PIF_002
**Test Case Description:** TRUE_FALSE exercise returns exactly 2 options ('Đúng'/'Sai') without exposing isCorrect

**Pre-conditions:**
- Learner is authenticated (accessToken available)
- ExerciseGeneratorAgent mocked to return 1 TRUE_FALSE item with 2 options

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions with Authorization: Bearer {learnerToken} and body:
   - lessonId: {publishedLesson.getId()}
   - sessionType: "VOCAB_DRILL"
   - itemTypes: ["TRUE_FALSE"]
2. AuthFilter validates token and resolves LEARNER user identity.
3. PracticeController calls PracticeService.startSession() with itemTypes filter.
4. PracticeService validates learner enrollment via ClassRepository.
5. PracticeService calls ExerciseGeneratorAgent.generateItems() with itemTypes=["TRUE_FALSE"].
6. ExerciseGeneratorAgent returns 1 GeneratedItem with itemType=TRUE_FALSE and options ["Đúng", "Sai"].
7. PracticeService creates PracticeSession and PracticeItem entities.
8. ItemFormatter.buildSelectionItem() maps TRUE_FALSE options, excluding isCorrect.
9. System returns formatted items array.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.items[0].itemType = "TRUE_FALSE"
- data.items[0].options.length() = 2
- Option texts include "Đúng" and "Sai"
- Each option has NO isCorrect field (null excluded)

---

### Test Case ID: TC_PIF_003
**Test Case Description:** FILL_BLANK exercise returns options with question not blank

**Pre-conditions:**
- Learner is authenticated (accessToken available)
- ExerciseGeneratorAgent mocked to return 1 FILL_BLANK item with options

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions with Authorization: Bearer {learnerToken} and body:
   - lessonId: {publishedLesson.getId()}
   - sessionType: "VOCAB_DRILL"
   - itemTypes: ["FILL_BLANK"]
2. AuthFilter validates token and resolves LEARNER user identity.
3. PracticeController calls PracticeService.startSession() with itemTypes filter.
4. PracticeService validates learner enrollment via ClassRepository.
5. PracticeService calls ExerciseGeneratorAgent.generateItems() with itemTypes=["FILL_BLANK"].
6. ExerciseGeneratorAgent returns 1 GeneratedItem with itemType=FILL_BLANK and options array.
7. PracticeService creates PracticeSession and PracticeItem entities from GeneratedItem.
8. ItemFormatter.buildSelectionItem() maps FILL_BLANK options (same format as MULTIPLE_CHOICE).
9. System returns formatted items array with options.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.items[0].itemType = "FILL_BLANK"
- data.items[0].question = non-blank string
- data.items[0].options.length() >= 2
- Each option has NO isCorrect field

---

### Test Case ID: TC_PIF_004
**Test Case Description:** TRANSLATION exercise returns 4 options without exposing isCorrect before submit

**Pre-conditions:**
- Learner is authenticated (accessToken available)
- ExerciseGeneratorAgent mocked to return 1 TRANSLATION item with 4 options

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions with Authorization: Bearer {learnerToken} and body:
   - lessonId: {publishedLesson.getId()}
   - sessionType: "VOCAB_DRILL"
   - itemTypes: ["TRANSLATION"]
2. AuthFilter validates token and resolves LEARNER user identity.
3. PracticeController calls PracticeService.startSession() with itemTypes filter.
4. PracticeService validates learner enrollment via ClassRepository.
5. PracticeService calls ExerciseGeneratorAgent.generateItems() with itemTypes=["TRANSLATION"].
6. ExerciseGeneratorAgent returns 1 GeneratedItem with itemType=TRANSLATION and 4 options.
7. PracticeService creates PracticeSession and PracticeItem entities.
8. ItemFormatter.buildSelectionItem() maps TRANSLATION options (same as MULTIPLE_CHOICE format).
9. System returns formatted items array with 4 options.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.items[0].itemType = "TRANSLATION"
- data.items[0].options.length() = 4
- Each option contains:
  - id = integer >= 0
  - text = non-blank string
  - isCorrect = NOT present

---

### Test Case ID: TC_PIF_005
**Test Case Description:** SENTENCE_ORDER exercise returns token options without exposing correctAnswer

**Pre-conditions:**
- Learner is authenticated (accessToken available)
- ExerciseGeneratorAgent mocked to return 1 SENTENCE_ORDER item with token options

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions with Authorization: Bearer {learnerToken} and body:
   - lessonId: {publishedLesson.getId()}
   - sessionType: "VOCAB_DRILL"
   - itemTypes: ["SENTENCE_ORDER"]
2. AuthFilter validates token and resolves LEARNER user identity.
3. PracticeController calls PracticeService.startSession() with itemTypes filter.
4. PracticeService validates learner enrollment via ClassRepository.
5. PracticeService calls ExerciseGeneratorAgent.generateItems() with itemTypes=["SENTENCE_ORDER"].
6. ExerciseGeneratorAgent returns 1 GeneratedItem with itemType=SENTENCE_ORDER and token options.
7. PracticeService creates PracticeSession and PracticeItem entities.
8. ItemFormatter.buildSentenceOrderItem() maps options without exposing correctAnswer.
9. System returns formatted items array.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.items[0].itemType = "SENTENCE_ORDER"
- data.items[0].options.length() >= 2
- data.items[0] does NOT contain "correctMapping" field
- data.items[0] does NOT contain "correctAnswer" field

---

### Test Case ID: TC_PIF_006
**Test Case Description:** MATCHING exercise returns LEFT+RIGHT options with side field and correctMapping

**Pre-conditions:**
- Learner is authenticated (accessToken available)
- ExerciseGeneratorAgent mocked to return 1 MATCHING item with LEFT and RIGHT options

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions with Authorization: Bearer {learnerToken} and body:
   - lessonId: {publishedLesson.getId()}
   - sessionType: "VOCAB_DRILL"
   - itemTypes: ["MATCHING"]
2. AuthFilter validates token and resolves LEARNER user identity.
3. PracticeController calls PracticeService.startSession() with itemTypes filter.
4. PracticeService validates learner enrollment via ClassRepository.
5. PracticeService calls ExerciseGeneratorAgent.generateItems() with itemTypes=["MATCHING"].
6. ExerciseGeneratorAgent returns 1 GeneratedItem with itemType=MATCHING and LEFT+RIGHT options.
7. PracticeService creates PracticeSession and PracticeItem entities.
8. ItemFormatter.buildMatchingItem() maps options with side field and includes correctMapping (exposed for client-side validation).
9. System returns formatted items array with correctMapping.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.items[0].itemType = "MATCHING"
- data.items[0].options contains items with "side" field = "LEFT" or "RIGHT"
- data.items[0].correctMapping = present and non-empty

---

## Section 5 — Submit Answer Tests (POST /api/v1/learner/practice/sessions/{id}/items/{itemId}/answer)

### Test Case ID: TC_PSA_001
**Test Case Description:** Learner submits correct answer → isCorrect=true, score=100, correctAnswerText returned (BR-265)

**Pre-conditions:**
- Learner is authenticated
- Practice session exists with 2 items
- First item's correct answer is "0" (option at index 0)

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer with Authorization: Bearer {learnerToken} and body:
   - userAnswer: "0"
2. AuthFilter validates token and resolves LEARNER user identity.
3. PracticeController validates session ownership: session.userId == currentUser.id.
4. PracticeController calls AnswerSubmissionService.submitAnswer().
5. AnswerSubmissionService retrieves PracticeItem via PracticeItemRepository.
6. AnswerSubmissionService validates answer format via @NotBlank constraint.
7. AnswerSubmissionService compares userAnswer ("0") with item.correctAnswer ("0").
8. Match found → isCorrect=true, score=100, correctAnswerText set.
9. AnswerSubmissionService updates PracticeSession: completedItems++, score += 100.
10. PracticeSessionRepository.save() persists updated session.
11. System returns response with feedback.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.correct = true
- data.score = 100
- data.correctAnswerText = non-empty string
- data.sessionCompleted = false (1 of 2 items done)

---

### Test Case ID: TC_PSA_002
**Test Case Description:** Learner submits wrong answer → isCorrect=false, score=0, correctAnswer and explanation revealed (BR-265)

**Pre-conditions:**
- Learner is authenticated
- Practice session exists with 2 items
- First item's correct answer is "0"

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer with Authorization: Bearer {learnerToken} and body:
   - userAnswer: "1" (incorrect)
2. AuthFilter validates token and resolves LEARNER user identity.
3. PracticeController validates session ownership via session.userId == currentUser.id.
4. PracticeController calls AnswerSubmissionService.submitAnswer().
5. AnswerSubmissionService retrieves PracticeItem via PracticeItemRepository.
6. AnswerSubmissionService validates answer format via @NotBlank constraint.
7. AnswerSubmissionService compares userAnswer ("1") with item.correctAnswer ("0").
8. No match → isCorrect=false, score=0, correctAnswer revealed in response.
9. AnswerSubmissionService updates PracticeSession: completedItems++, score += 0.
10. PracticeSessionRepository.save() persists updated session.
11. System returns response with explanation.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.correct = false
- data.score = 0
- data.correctAnswer = non-empty string
- data.explanation = non-empty string

---

### Test Case ID: TC_PSA_003
**Test Case Description:** Learner submits last item → sessionCompleted=true, nextItem null, session status=COMPLETED

**Pre-conditions:**
- Learner is authenticated
- Practice session exists with 1 item only

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer with Authorization: Bearer {learnerToken} and body:
   - userAnswer: "0" (correct)
2. AuthFilter validates token and resolves LEARNER user identity.
3. PracticeController validates session ownership via session.userId == currentUser.id.
4. PracticeController calls AnswerSubmissionService.submitAnswer().
5. AnswerSubmissionService retrieves PracticeItem via PracticeItemRepository.
6. AnswerSubmissionService validates answer format and compares userAnswer with correctAnswer.
7. Match found → isCorrect=true, score=100.
8. AnswerSubmissionService updates PracticeSession: completedItems=1, totalItems=1.
9. AnswerSubmissionService detects completedItems == totalItems.
10. AnswerSubmissionService sets session status=COMPLETED, completedAt=now.
11. PracticeSessionRepository.save() persists updated session.
12. System returns response with sessionCompleted=true, no nextItem.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.sessionCompleted = true
- data.nextItem = NOT present
Database state:
- PracticeSession.status = COMPLETED
- PracticeSession.completedItems = 1
- PracticeSession.completedAt = not null

---

### Test Case ID: TC_PSA_004
**Test Case Description:** Learner submits non-last item → sessionCompleted=false, nextItem present with required fields

**Pre-conditions:**
- Learner is authenticated
- Practice session exists with 2 items
- Submitting first item

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions/{sessionId}/items/{firstItemId}/answer with Authorization: Bearer {learnerToken} and body:
   - userAnswer: "0" (correct)
2. AuthFilter validates token and resolves LEARNER user identity.
3. PracticeController validates session ownership via session.userId == currentUser.id.
4. PracticeController calls AnswerSubmissionService.submitAnswer().
5. AnswerSubmissionService updates PracticeSession: completedItems=1, totalItems=2.
6. AnswerSubmissionService detects completedItems < totalItems (1 < 2).
7. AnswerSubmissionService retrieves next item via PracticeItemRepository.findBySessionIdAndItemIndex(sessionId, 1).
8. PracticeItemRepository returns the 2nd item (itemIndex=1).
9. AnswerSubmissionService maps PracticeItem to response DTO with required fields.
10. System returns response with nextItem present.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.sessionCompleted = false
- data.nextItem = present (not null)
- data.nextItem.id = non-empty UUID
- data.nextItem.itemType = non-empty string

---

### Test Case ID: TC_PSA_005
**Test Case Description:** Learner submits answer for other learner's session → returns 4xx SESSION_ACCESS_DENIED

**Pre-conditions:**
- Learner A is authenticated
- Practice session belongs to Learner B (otherLearner)

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions/{otherLearnerSessionId}/items/{itemId}/answer with Authorization: Bearer {learnerToken} and body:
   - userAnswer: "0"
2. AuthFilter validates token and resolves LEARNER user identity.
3. PracticeController retrieves session via PracticeSessionRepository.findById().
4. PracticeController validates session ownership: session.userId == currentUser.id.
5. Ownership check fails (otherLearner.id ≠ learner.id).
6. PracticeController throws SESSION_ACCESS_DENIED exception.
7. GlobalExceptionHandler catches exception and maps to 4xx response.
8. System returns error response.

**Expected Results:** HTTP 4XX CLIENT ERROR
Response JSON:
- success = false

---

### Test Case ID: TC_PSA_006
**Test Case Description:** Learner submits blank answer → returns 400 BAD REQUEST (@NotBlank)

**Pre-conditions:**
- Learner is authenticated
- Practice session and item exist

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer with Authorization: Bearer {learnerToken} and body:
   - userAnswer: ""
2. AuthFilter validates token and resolves LEARNER user identity.
3. Request validation fails at controller level due to @NotBlank constraint on userAnswer field.
4. GlobalExceptionHandler catches MethodArgumentNotValidException.
5. System returns validation error response before reaching PracticeService.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false

---

### Test Case ID: TC_PSA_007
**Test Case Description:** Learner submits oversized answer → returns 400 BAD REQUEST (@Size max=500)

**Pre-conditions:**
- Learner is authenticated
- Practice session and item exist

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer with Authorization: Bearer {learnerToken} and body:
   - userAnswer: "x" repeated 501 times
2. AuthFilter validates token and resolves LEARNER user identity.
3. Request validation fails at controller level due to @Size constraint (max=500) on userAnswer field.
4. GlobalExceptionHandler catches MethodArgumentNotValidException.
5. System returns validation error response before reaching PracticeService.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false

---

## Section 6 — Session Summary Tests (GET /api/v1/learner/practice/sessions/{id}/summary)

### Test Case ID: TC_PSU_001
**Test Case Description:** Learner retrieves completed session summary → summary with score, correctItems, totalItems returned

**Pre-conditions:**
- Learner is authenticated
- Practice session with 1 item completed (correct answer submitted)

**Test Case Procedure:**
1. Client creates and completes a 1-item practice session via:
   - POST /api/v1/learner/practice/sessions with lessonId, sessionType, preset (returns sessionId)
   - POST /api/v1/learner/practice/sessions/{sessionId}/items/{itemId}/answer with correct answer
2. Client sends GET /api/v1/learner/practice/sessions/{sessionId}/summary with Authorization: Bearer {learnerToken}.
3. AuthFilter validates token and resolves LEARNER user identity.
4. SummaryController validates session ownership via session.userId == currentUser.id.
5. SummaryController calls SummaryService.getSessionSummary().
6. SummaryService retrieves session via PracticeSessionRepository.findById().
7. SummaryService retrieves session answers via AnswerRepository.findBySessionId().
8. SummaryService calculates correctItems count from answers.
9. System returns summary response with scores and counts.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.sessionId = {sessionId UUID as string}
- data.score = non-empty (numeric)
- data.totalItems = 1
- data.correctItems = 1

---

### Test Case ID: TC_PSU_002
**Test Case Description:** Learner retrieves summary for other learner's session → returns 4xx SESSION_ACCESS_DENIED

**Pre-conditions:**
- Learner A is authenticated
- Practice session belongs to Learner B (otherLearner)

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/practice/sessions/{otherLearnerSessionId}/summary with Authorization: Bearer {learnerToken}.
2. AuthFilter validates token and resolves LEARNER user identity.
3. SummaryController retrieves session via PracticeSessionRepository.findById().
4. SummaryController validates session ownership: session.userId == currentUser.id.
5. Ownership check fails (otherLearner.id ≠ learner.id).
6. SummaryController throws SESSION_ACCESS_DENIED exception.
7. GlobalExceptionHandler catches exception and maps to 4xx response.
8. System returns error response.

**Expected Results:** HTTP 4XX CLIENT ERROR
Response JSON:
- success = false

---

## End of Test Script



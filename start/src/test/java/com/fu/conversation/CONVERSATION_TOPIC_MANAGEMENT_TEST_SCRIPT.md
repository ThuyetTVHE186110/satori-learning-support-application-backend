# Conversation Topic Management Integration Test Script

## Overview
This document contains detailed test scripts for all conversation topic management API endpoints in Satori Learning Support Application backend.

## Test Environment Setup

### Pre-conditions
- Test DB running via `application-test.properties`
- PostgreSQL `satori_test` database created and initialized
- Docker environment up and running
- EmailService mocked to prevent actual email sending

---

## Section 1 — View Topic List Tests (GET /api/v1/manager/conversation/topics)

### Test Case ID: TC_CTP_VTL_001
**Test Case Description:** Manager view topic list success with default pagination, soft-deleted topics excluded

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Topics inserted via topicRepository.save():
- "Mua sắm tại siêu thị" (status=DRAFT)
- "Đặt đồ ăn" (status=DRAFT)
- "Hỏi đường" (status=DRAFT)
- "Cũ" (status=DRAFT, soft-deleted with deletedAt and deletedBy set)

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/conversation/topics?page=0&size=10 with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationTopicController calls ConversationTopicService.getTopics() with pagination params.
4. ConversationTopicService retrieves topic list via ConversationTopicRepository.findAll().
5. ConversationTopicRepository executes database query with deletedAt IS NULL filter and returns paginated list.
6. System returns the paginated topic list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.pageNumber = 0
- data.pageSize = 10
- data.totalElements = 3
- data.totalPages = 1
- data.content.length() = 3
- data.content does not contain topic with title "Cũ"
- data.content contains topics with titles: "Mua sắm tại siêu thị", "Đặt đồ ăn", "Hỏi đường" (in any order)
All items contain required fields: id, title, status

---

### Test Case ID: TC_CTP_VTL_002
**Test Case Description:** Manager search topics by keyword (case-insensitive)

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Topics inserted via topicRepository.save():
- "Mua sắm tại siêu thị" (status=DRAFT)
- "Đặt đồ ăn" (status=DRAFT)
- "Hỏi đường" (status=DRAFT)

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/conversation/topics?page=0&size=10&keyword=mua with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationTopicController calls ConversationTopicService.getTopics() with keyword filter.
4. ConversationTopicService searches topics by keyword in title via ConversationTopicRepository with keyword filter.
5. ConversationTopicRepository executes database query with keyword search and deletedAt IS NULL filter.
6. System returns the filtered paginated topic list response.

**Expected Results:** 
HTTP 200 OK
Response JSON:
- success = true
- data.totalElements = 1
- data.content.length() = 1
- data.content[0].title = "Mua sắm tại siêu thị"
- data.pageNumber = 0
- data.pageSize = 10

---

### Test Case ID: TC_CTP_VTL_003
**Test Case Description:** Keyword search with no matching results

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Topics inserted via topicRepository.save():
- "Mua sắm" (status=DRAFT)

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/conversation/topics?page=0&size=10&keyword=bệnh%20viện with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationTopicController calls ConversationTopicService.getTopics() with keyword filter.
4. ConversationTopicService searches topics by keyword via ConversationTopicRepository with keyword filter.
5. ConversationTopicRepository executes database query and returns empty result.
6. System returns empty paginated response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.totalElements = 0
- data.content = [] (empty array)

---

### Test Case ID: TC_CTP_VTL_004
**Test Case Description:** Pagination with 15 items: page 0 shows 10 items, page 1 shows 5 items

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- 15 topics inserted via topicRepository.save() with titles "Topic 1" through "Topic 15"

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/conversation/topics?page=0&size=10 with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationTopicController calls ConversationTopicService.getTopics() with page=0, size=10.
4. ConversationTopicService retrieves paginated list via ConversationTopicRepository.findAll(Pageable).
5. ConversationTopicRepository executes database query with LIMIT 10 OFFSET 0 and returns first 10 items.
6. System returns paginated response for page 0.

**Expected Results:** HTTP 200 OK
Response JSON (page 0):
- success = true
- data.pageNumber = 0
- data.pageSize = 10
- data.totalElements = 15
- data.totalPages = 2
- data.content.length() = 10

Test second page:
1. Client sends GET /api/v1/manager/conversation/topics?page=1&size=10 with Authorization: Bearer {token}.
2. System returns paginated response for page 1.

**Expected Results:** HTTP 200 OK
Response JSON (page 1):
- success = true
- data.pageNumber = 1
- data.pageSize = 10
- data.totalElements = 15
- data.totalPages = 2
- data.content.length() = 5

---

### Test Case ID: TC_CTP_VTL_005
**Test Case Description:** Keyword parameter exceeds max 100 characters

**Pre-conditions:**
- Manager accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/conversation/topics?keyword=[101 characters] with Authorization: Bearer {token}.
2. Request validation fails at controller level due to keyword exceeding max length.
3. System returns the validation error response before reaching ConversationTopicService.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false

---

## Section 2 — View Topic Detail Tests (GET /api/v1/manager/conversation/topics/{topicId})

### Test Case ID: TC_CTP_VTD_001
**Test Case Description:** Manager get active topic — returns full response with all fields

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Topic inserted via topicRepository.save() with all fields:
  - title = "Shopping"
  - titleJapanese = "買い物"
  - description = "Shopping scenarios"
  - descriptionVi = "Các tình huống mua sắm"
  - category = "Daily Life"
  - jlptLevel = N3
  - status = DRAFT
  - orderIndex = 0

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/conversation/topics/{topicId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationTopicController calls ConversationTopicService.getTopicById().
4. ConversationTopicService retrieves topic by ID via ConversationTopicRepository.findByIdAndNotDeleted().
5. ConversationTopicRepository executes database query with deletedAt IS NULL filter and returns the topic object.
6. System returns the topic detail response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.id = [UUID value of the topic]
- data.title = "Shopping"
- data.titleJapanese = "買い物"
- data.description = "Shopping scenarios"
- data.descriptionVi = "Các tình huống mua sắm"
- data.category = "Daily Life"
- data.jlptLevel = "N3"
- data.status = "DRAFT"
- data.orderIndex = 0

---

### Test Case ID: TC_CTP_VTD_002
**Test Case Description:** Manager get soft-deleted topic — returns 404 Not Found

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Topic exists but has been soft-deleted (deletedAt and deletedBy set)

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/conversation/topics/{deletedTopicId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationTopicController calls ConversationTopicService.getTopicById().
4. ConversationTopicService retrieves topic by ID via ConversationTopicRepository.findByIdAndNotDeleted().
5. ConversationTopicRepository executes database query with deletedAt IS NULL filter and returns empty.
6. ConversationTopicService throws ResourceNotFoundException.
7. GlobalExceptionHandler maps exception to 404 response.

**Expected Results:** HTTP 404 NOT FOUND
Response JSON:
- success = false

---

### Test Case ID: TC_CTP_VTD_003
**Test Case Description:** Manager get non-existent topic — returns 404 Not Found

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Topic ID does not exist in database

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/conversation/topics/{nonExistentId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationTopicController calls ConversationTopicService.getTopicById().
4. ConversationTopicService retrieves topic by ID via ConversationTopicRepository.findByIdAndNotDeleted().
5. ConversationTopicRepository executes database query and returns empty.
6. ConversationTopicService throws ResourceNotFoundException.
7. GlobalExceptionHandler maps exception to 404 response.

**Expected Results:** HTTP 404 NOT FOUND
Response JSON:
- success = false

---

## Section 3 — Create Topic Tests (POST /api/v1/manager/conversation/topics)

### Test Case ID: TC_CTP_CTH_001
**Test Case Description:** Manager create topic with title only — defaults applied for optional fields

**Pre-conditions:**
- Manager accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/conversation/topics with Authorization: Bearer {token} and JSON body:
   - {"title":"Mua sắm"}
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationTopicController calls ConversationTopicService.createTopic().
4. ConversationTopicService creates new ConversationTopic entity with provided title and default values via ConversationTopicRepository.save().
5. ConversationTopicRepository saves the new topic with status=DRAFT and orderIndex=0.
6. System returns the created topic response.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.title = "Mua sắm"
- data.id = [non-empty UUID]
- data.status = "DRAFT"
- data.orderIndex = 0
Database state: topic.title = "Mua sắm", topic.status = DRAFT, topic.deletedAt = null, topic.deletedBy = null
Audit fields: topic.createdAt = not null, topic.updatedAt = not null

---

### Test Case ID: TC_CTP_CTH_002
**Test Case Description:** Manager create topic with all fields — all values persisted to database

**Pre-conditions:**
- Manager accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/conversation/topics with Authorization: Bearer {token} and JSON body:
   ```json
   {
     "title": "Shopping",
     "titleJapanese": "買い物",
     "description": "Shopping at supermarket",
     "descriptionVi": "Mua sắm tại siêu thị",
     "category": "Daily Life",
     "jlptLevel": "N3",
     "orderIndex": 5
   }
   ```
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationTopicController calls ConversationTopicService.createTopic().
4. ConversationTopicService creates new ConversationTopic entity with all provided fields via ConversationTopicRepository.save().
5. ConversationTopicRepository saves the new topic with all provided values.
6. System returns the created topic response.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.title = "Shopping"
- data.titleJapanese = "買い物"
- data.description = "Shopping at supermarket"
- data.descriptionVi = "Mua sắm tại siêu thị"
- data.category = "Daily Life"
- data.jlptLevel = "N3"
- data.orderIndex = 5
- data.id = [non-empty UUID]
Database state: all fields match the request values, deletedAt = null

---

### Test Case ID: TC_CTP_CTH_003
**Test Case Description:** Create topic with blank title — validation error

**Pre-conditions:**
- Manager accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/conversation/topics with Authorization: Bearer {token} and JSON body:
   - {"title":""}
2. Request validation fails at controller level due to blank title.
3. MethodArgumentNotValidException is thrown.
4. GlobalExceptionHandler maps exception to 400 response with field validation map.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
Database state: No topic created

---

### Test Case ID: TC_CTP_CTH_004
**Test Case Description:** Create topic with title exceeding 255 characters — validation error

**Pre-conditions:**
- Manager accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/conversation/topics with Authorization: Bearer {token} and JSON body:
   - {"title":"[256 characters]"}
2. Request validation fails at controller level due to title exceeding max length.
3. MethodArgumentNotValidException is thrown.
4. GlobalExceptionHandler maps exception to 400 response with field validation map.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
Database state: No topic created

---

### Test Case ID: TC_CTP_CTH_005
**Test Case Description:** Create topic with description exceeding 1000 characters — validation error

**Pre-conditions:**
- Manager accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/conversation/topics with Authorization: Bearer {token} and JSON body:
   - {"title":"Test","description":"[1001 characters]"}
2. Request validation fails at controller level due to description exceeding max length.
3. MethodArgumentNotValidException is thrown.
4. GlobalExceptionHandler maps exception to 400 response with field validation map.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
Database state: No topic created

---

## Section 4 — Update Topic Tests (PUT /api/v1/manager/conversation/topics/{topicId})

### Test Case ID: TC_CTP_UTH_001
**Test Case Description:** Manager update topic title only — only title changed, other fields preserved

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Topic inserted via topicRepository.save():
  - title = "Old Title"
  - description = "Old Description"
  - status = DRAFT
  - orderIndex = 0

**Test Case Procedure:**
1. Client sends PUT /api/v1/manager/conversation/topics/{topicId} with Authorization: Bearer {token} and JSON body:
   - {"title":"New Title"}
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationTopicController calls ConversationTopicService.updateTopic().
4. ConversationTopicService retrieves topic by ID via ConversationTopicRepository.findByIdAndNotDeleted().
5. ConversationTopicRepository executes database query and returns the topic object.
6. ConversationTopicService applies partial update (only title), preserving other fields.
7. ConversationTopicService saves updated topic via ConversationTopicRepository.save().
8. System returns the updated topic response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.title = "New Title"
- data.description = "Old Description" (preserved)
Database state: topic.title = "New Title", topic.description = "Old Description", topic.updatedAt is newer than before

---

### Test Case ID: TC_CTP_UTH_002
**Test Case Description:** Manager update all topic fields — all updated in database

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Topic inserted via topicRepository.save() with initial values

**Test Case Procedure:**
1. Client sends PUT /api/v1/manager/conversation/topics/{topicId} with Authorization: Bearer {token} and JSON body:
   ```json
   {
     "title": "New Title",
     "description": "New Description",
     "titleJapanese": "新しいタイトル",
     "descriptionVi": "Mô tả mới",
     "category": "New Category"
   }
   ```
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationTopicController calls ConversationTopicService.updateTopic().
4. ConversationTopicService retrieves topic by ID via ConversationTopicRepository.findByIdAndNotDeleted().
5. ConversationTopicRepository executes database query and returns the topic object.
6. ConversationTopicService applies all updates to topic fields.
7. ConversationTopicService saves updated topic via ConversationTopicRepository.save().
8. System returns the updated topic response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.title = "New Title"
- data.description = "New Description"
- data.titleJapanese = "新しいタイトル"
- data.descriptionVi = "Mô tả mới"
- data.category = "New Category"
Database state: all fields match the updated values, updatedAt is newer than before

---

### Test Case ID: TC_CTP_UTH_003
**Test Case Description:** Manager update soft-deleted topic — returns 404 Not Found

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Topic exists but has been soft-deleted (deletedAt and deletedBy set)

**Test Case Procedure:**
1. Client sends PUT /api/v1/manager/conversation/topics/{deletedTopicId} with Authorization: Bearer {token} and JSON body:
   - {"title":"New Title"}
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationTopicController calls ConversationTopicService.updateTopic().
4. ConversationTopicService retrieves topic by ID via ConversationTopicRepository.findByIdAndNotDeleted().
5. ConversationTopicRepository executes database query with deletedAt IS NULL filter and returns empty.
6. ConversationTopicService throws ResourceNotFoundException.
7. GlobalExceptionHandler maps exception to 404 response.

**Expected Results:** HTTP 404 NOT FOUND
Response JSON:
- success = false

---

### Test Case ID: TC_CTP_UTH_005
**Test Case Description:** Manager update topic to blank title — validation error

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Topic inserted via topicRepository.save()

**Test Case Procedure:**
1. Client sends PUT /api/v1/manager/conversation/topics/{topicId} with Authorization: Bearer {token} and JSON body:
   - {"title":""}
2. Request validation fails at controller level due to blank title.
3. MethodArgumentNotValidException is thrown.
4. GlobalExceptionHandler maps exception to 400 response with field validation map.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
Database state: topic.title unchanged (original value preserved)

---

### Test Case ID: TC_CTP_UTH_006
**Test Case Description:** Update topic — createdAt and createdBy remain unchanged, only updatedAt changes

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Topic inserted via topicRepository.save()

**Test Case Procedure:**
1. Record topic's createdAt and createdBy values before update.
2. Wait 100ms to ensure timestamp difference.
3. Client sends PUT /api/v1/manager/conversation/topics/{topicId} with Authorization: Bearer {token} and JSON body:
   - {"title":"Updated"}
4. System processes update and returns 200 OK response.
5. Retrieve updated topic from database and verify audit fields.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
Database state: topic.createdAt = [original value] (unchanged)
Audit verification: topic.createdBy = [original value] (unchanged), topic.updatedAt > [original createdAt]

---

## Section 5 — Delete Topic Tests (DELETE /api/v1/manager/conversation/topics/{topicId})

### Test Case ID: TC_CTP_DTH_001
**Test Case Description:** Manager delete active topic — soft-deleted in database with deletedAt and deletedBy set

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Topic inserted via topicRepository.save()

**Test Case Procedure:**
1. Client sends DELETE /api/v1/manager/conversation/topics/{topicId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationTopicController calls ConversationTopicService.deleteTopic().
4. ConversationTopicService retrieves topic by ID via ConversationTopicRepository.findByIdAndNotDeleted().
5. ConversationTopicRepository executes database query and returns the topic object.
6. ConversationTopicService performs soft delete: sets deletedAt and deletedBy fields.
7. ConversationTopicService saves updated topic via ConversationTopicRepository.save().
8. System returns the delete success response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
Database state: topic.deletedAt = [current timestamp] (not null), topic.deletedBy = [managerId]

---

### Test Case ID: TC_CTP_DTH_002
**Test Case Description:** Manager delete topic cascades soft-delete to all associated conversations

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Topic inserted via topicRepository.save()
- 3 Conversations inserted via conversationRepository.save():
  - "Conv 1" with topicId = [topic.id]
  - "Conv 2" with topicId = [topic.id]
  - "Conv 3" with topicId = [topic.id]

**Test Case Procedure:**
1. Client sends DELETE /api/v1/manager/conversation/topics/{topicId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationTopicController calls ConversationTopicService.deleteTopic().
4. ConversationTopicService retrieves topic by ID and cascades soft delete to all associated conversations via JPA @OnDelete cascade.
5. ConversationTopicService saves updated topic with soft delete fields.
6. System returns the delete success response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
Database state: topic.deletedAt = [current timestamp] (not null), topic.deletedBy = [managerId]
Conversation verification: all 3 conversations have deletedAt = [current timestamp] (not null), deletedBy = [managerId]

---

### Test Case ID: TC_CTP_DTH_003
**Test Case Description:** Manager delete already-soft-deleted topic — returns 404 Not Found

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Topic exists but has been soft-deleted (deletedAt and deletedBy set)

**Test Case Procedure:**
1. Client sends DELETE /api/v1/manager/conversation/topics/{deletedTopicId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationTopicController calls ConversationTopicService.deleteTopic().
4. ConversationTopicService retrieves topic by ID via ConversationTopicRepository.findByIdAndNotDeleted().
5. ConversationTopicRepository executes database query with deletedAt IS NULL filter and returns empty.
6. ConversationTopicService throws ResourceNotFoundException.
7. GlobalExceptionHandler maps exception to 404 response.

**Expected Results:** HTTP 404 NOT FOUND
Response JSON:
- success = false

---

### Test Case ID: TC_CTP_DTH_004
**Test Case Description:** Manager delete non-existent topic — returns 404 Not Found

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Topic ID does not exist in database

**Test Case Procedure:**
1. Client sends DELETE /api/v1/manager/conversation/topics/{nonExistentId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationTopicController calls ConversationTopicService.deleteTopic().
4. ConversationTopicService retrieves topic by ID via ConversationTopicRepository.findByIdAndNotDeleted().
5. ConversationTopicRepository executes database query and returns empty.
6. ConversationTopicService throws ResourceNotFoundException.
7. GlobalExceptionHandler maps exception to 404 response.

**Expected Results:** HTTP 404 NOT FOUND
Response JSON:
- success = false

---

### Test Case ID: TC_CTP_DTH_005
**Test Case Description:** After delete, topic no longer appears in list (soft delete verified)

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Topic inserted via topicRepository.save()

**Test Case Procedure:**
1. Client sends DELETE /api/v1/manager/conversation/topics/{topicId} with Authorization: Bearer {token}.
2. System processes soft delete and returns 200 OK response.
3. Client sends GET /api/v1/manager/conversation/topics?page=0&size=10 with Authorization: Bearer {token}.
4. ConversationTopicService retrieves topics via ConversationTopicRepository with deletedAt IS NULL filter.
5. System returns topic list with deleted topic excluded.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.totalElements = 0 (if only 1 topic existed)
- data.content = [] (empty array, deleted topic not included)

---

## Section 6 — RBAC Tests

### Test Case ID: TC_CTP_RBAC_001
**Test Case Description:** GET request without authentication token — returns 401 Unauthorized

**Pre-conditions:**
- No authentication token provided

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/conversation/topics without Authorization header.
2. AuthFilter detects missing token and returns authentication error.
3. System returns the unauthorized error response.

**Expected Results:** HTTP 401 UNAUTHORIZED
Response JSON:
- success = false

---

### Test Case ID: TC_CTP_RBAC_002
**Test Case Description:** GET request with LEARNER role — returns 403 Forbidden

**Pre-conditions:**
- Learner accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/conversation/topics with Authorization: Bearer {learnerToken}.
2. AuthFilter validates token and resolves LEARNER user identity.
3. SecurityConfig's @PreAuthorize("hasRole('CONTENT_MANAGER')") at class level denies access.
4. System returns the access denied error response.

**Expected Results:** HTTP 403 FORBIDDEN
Response JSON:
- success = false

---

### Test Case ID: TC_CTP_RBAC_003
**Test Case Description:** POST request with TEACHER role — returns 403 Forbidden

**Pre-conditions:**
- Teacher accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/conversation/topics with Authorization: Bearer {teacherToken} and JSON body:
   - {"title":"Test"}
2. AuthFilter validates token and resolves TEACHER user identity.
3. SecurityConfig's @PreAuthorize("hasRole('CONTENT_MANAGER')") at class level denies access.
4. System returns the access denied error response.

**Expected Results:** HTTP 403 FORBIDDEN
Response JSON:
- success = false

---

### Test Case ID: TC_CTP_RBAC_004
**Test Case Description:** DELETE request with ADMIN role — returns 403 Forbidden

**Pre-conditions:**
- Admin accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends DELETE /api/v1/manager/conversation/topics/{topicId} with Authorization: Bearer {adminToken}.
2. AuthFilter validates token and resolves ADMIN user identity.
3. SecurityConfig's @PreAuthorize("hasRole('CONTENT_MANAGER')") at class level denies access.
4. System returns the access denied error response.

**Expected Results:** HTTP 403 FORBIDDEN
Response JSON:
- success = false


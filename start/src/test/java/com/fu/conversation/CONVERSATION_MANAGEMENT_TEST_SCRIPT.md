# Conversation Management Integration Test Script

## Overview
This document contains detailed test scripts for all conversation management API endpoints in Satori Learning Support Application backend.

## Test Environment Setup

### Pre-conditions
- Test DB running via `application-test.properties`
- PostgreSQL `satori_test` database created and initialized
- Docker environment up and running
- EmailService mocked to prevent actual email sending

---

## Section 1 — View Conversation List Tests (GET /api/v1/manager/conversation/topics/{topicId}/conversations)

### Test Case ID: TC_CVL_001
**Test Case Description:** Manager view conversation list with pagination, soft-deleted conversations excluded

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation Topic inserted via topicRepository.save()
- 4 Conversations inserted via conversationRepository.save():
  - "Ordering Food" (status=DRAFT)
  - "Making Reservation" (status=DRAFT)
  - "Paying Bill" (status=DRAFT)
  - "Deleted Conversation" (status=DRAFT, soft-deleted with deletedAt and deletedBy set)

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/conversation/topics/{topicId}/conversations?page=0&size=10 with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationController calls ConversationService.getConversations() with pagination params and topic filter.
4. ConversationService retrieves conversation list via ConversationRepository.findAll() with deletedAt IS NULL filter.
5. ConversationRepository executes database query and returns paginated list excluding soft-deleted conversations.
6. System returns the paginated conversation list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.pageNumber = 0
- data.pageSize = 10
- data.totalElements = 3
- data.totalPages = 1
- data.content.length() = 3
- data.content does not contain conversation with title "Deleted Conversation"
- data.content contains conversations with titles: "Ordering Food", "Making Reservation", "Paying Bill" (in any order)
All items contain required fields: id, title, status

---

### Test Case ID: TC_CVL_002
**Test Case Description:** Conversation list pagination with 15 items: page 0 shows 10, page 1 shows 5

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation Topic inserted via topicRepository.save()
- 15 Conversations inserted via conversationRepository.save() with titles "Conversation 1" through "Conversation 15"

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/conversation/topics/{topicId}/conversations?page=0&size=10 with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationController calls ConversationService.getConversations() with page=0, size=10.
4. ConversationService retrieves paginated list via ConversationRepository with LIMIT 10 OFFSET 0.
5. System returns paginated response for page 0.

**Expected Results:** HTTP 200 OK
Response JSON (page 0):
- success = true
- data.pageNumber = 0
- data.pageSize = 10
- data.totalElements = 15
- data.totalPages = 2
- data.content.length() = 10

Test second page:
1. Client sends GET /api/v1/manager/conversation/topics/{topicId}/conversations?page=1&size=10 with Authorization: Bearer {token}.

**Expected Results:** HTTP 200 OK
Response JSON (page 1):
- success = true
- data.pageNumber = 1
- data.totalElements = 15
- data.totalPages = 2
- data.content.length() = 5

---

### Test Case ID: TC_CVL_003
**Test Case Description:** Keyword search returns matching conversations only

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation Topic inserted via topicRepository.save()
- 4 Conversations inserted via conversationRepository.save():
  - "Restaurant - Ordering" (status=DRAFT)
  - "Restaurant - Reservation" (status=DRAFT)
  - "Hotel - Check-in" (status=DRAFT)
  - "Train Station - Ticket" (status=DRAFT)

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/conversation/topics/{topicId}/conversations?page=0&size=10&keyword=Restaurant with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationController calls ConversationService.getConversations() with keyword filter.
4. ConversationService searches conversations by keyword in title via ConversationRepository with keyword filter and deletedAt IS NULL filter.
5. ConversationRepository executes database query and returns matching results.
6. System returns filtered paginated response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.totalElements = 2
- data.content.length() = 2
- data.content[0].title = "Restaurant - Ordering"
- data.content[1].title = "Restaurant - Reservation"

---

### Test Case ID: TC_CVL_004
**Test Case Description:** Keyword parameter exceeds max 100 characters — validation error

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation Topic exists

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/conversation/topics/{topicId}/conversations?page=0&size=10&keyword=[101 characters] with Authorization: Bearer {token}.
2. Request validation fails at controller level due to keyword exceeding max length.
3. System returns the validation error response before reaching ConversationService.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false

---

### Test Case ID: TC_CVL_005
**Test Case Description:** Filter by jlptLevel returns matching conversations only

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation Topic inserted via topicRepository.save()
- 3 Conversations inserted with different JLPT levels:
  - "N1 Conversation" (jlptLevel=N1)
  - "N2 Conversation 1" (jlptLevel=N2)
  - "N2 Conversation 2" (jlptLevel=N2)

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/conversation/topics/{topicId}/conversations?page=0&size=10&jlptLevel=N2 with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationController calls ConversationService.getConversations() with jlptLevel filter.
4. ConversationService retrieves conversations via ConversationRepository with jlptLevel filter and deletedAt IS NULL filter.
5. System returns filtered paginated response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.totalElements = 2
- data.content.length() = 2

---

### Test Case ID: TC_CVL_006
**Test Case Description:** Filter by status returns matching conversations only

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation Topic inserted via topicRepository.save()
- 3 Conversations inserted with different statuses:
  - "Draft Conv" (status=DRAFT)
  - "Published Conv 1" (status=PUBLISHED)
  - "Published Conv 2" (status=PUBLISHED)

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/conversation/topics/{topicId}/conversations?page=0&size=10&status=PUBLISHED with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationController calls ConversationService.getConversations() with status filter.
4. ConversationService retrieves conversations via ConversationRepository with status filter and deletedAt IS NULL filter.
5. System returns filtered paginated response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.totalElements = 2

---

### Test Case ID: TC_CVL_007
**Test Case Description:** Combined filters use AND logic (jlptLevel AND status)

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation Topic inserted via topicRepository.save()
- 3 Conversations inserted:
  - "N1 Draft" (jlptLevel=N1, status=DRAFT)
  - "N1 Published" (jlptLevel=N1, status=PUBLISHED)
  - "N2 Published" (jlptLevel=N2, status=PUBLISHED)

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/conversation/topics/{topicId}/conversations?page=0&size=10&jlptLevel=N1&status=PUBLISHED with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationController calls ConversationService.getConversations() with combined filters.
4. ConversationService retrieves conversations via ConversationRepository with both jlptLevel AND status filters using AND logic.
5. System returns filtered paginated response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.totalElements = 1
- data.content[0].title = "N1 Published"

---

## Section 2 — View Conversation Details Tests (GET /api/v1/manager/conversation/conversations/{conversationId})

### Test Case ID: TC_CVI_001
**Test Case Description:** Manager get conversation — returns full details with all fields and associated missions

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation Topic inserted via topicRepository.save()
- Conversation inserted via conversationRepository.save() with:
  - title = "Test Conversation"
  - titleJapanese = "テストタイトル"
  - description = "Full description"
  - jlptLevel = N2
  - difficultyScore = 5
  - status = DRAFT
- 2 ConversationMissions inserted: "Mission 1", "Mission 2"

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/conversation/conversations/{conversationId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationController calls ConversationService.getConversationById().
4. ConversationService retrieves conversation by ID via ConversationRepository.findByIdAndNotDeleted() with deletedAt IS NULL filter.
5. ConversationService retrieves associated missions via ConversationMissionRepository.
6. System returns the conversation detail response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.id = [UUID value]
- data.title = "Test Conversation"
- data.titleJapanese = "テストタイトル"
- data.description = "Full description"
- data.jlptLevel = "N2"
- data.difficultyScore = 5
- data.status = "DRAFT"
- data.missions.length() = 2
- data.missions[0].title = "Mission 1"
- data.missions[1].title = "Mission 2"

---

### Test Case ID: TC_CVI_003
**Test Case Description:** Manager get soft-deleted conversation — returns 404 Not Found

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation exists but has been soft-deleted (deletedAt and deletedBy set)

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/conversation/conversations/{deletedConversationId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationController calls ConversationService.getConversationById().
4. ConversationService retrieves conversation by ID via ConversationRepository.findByIdAndNotDeleted() with deletedAt IS NULL filter.
5. ConversationRepository executes database query and returns empty.
6. ConversationService throws ResourceNotFoundException.
7. GlobalExceptionHandler maps exception to 404 response.

**Expected Results:** HTTP 404 NOT FOUND
Response JSON:
- success = false

---

### Test Case ID: TC_CVI_004
**Test Case Description:** Response includes missions ordered by index

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation Topic inserted via topicRepository.save()
- Conversation inserted via conversationRepository.save()
- 3 ConversationMissions inserted: "Mission A", "Mission B", "Mission C" with orderIndex 0, 1, 2

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/conversation/conversations/{conversationId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationController calls ConversationService.getConversationById().
4. ConversationService retrieves conversation and associated missions ordered by orderIndex.
5. System returns the conversation detail response with missions in order.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.missions.length() = 3
- data.missions[0].title = "Mission A"
- data.missions[1].title = "Mission B"
- data.missions[2].title = "Mission C"

---

## Section 3 — Create Conversation Tests (POST /api/v1/manager/conversation/conversations)

### Test Case ID: TC_CCV_001
**Test Case Description:** Create conversation with all required fields and optional fields — conversation saved with DRAFT status by default

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation Topic exists (topicId is valid)
- Course exists (courseId is valid)

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/conversation/conversations with Authorization: Bearer {token} and JSON body with all fields:
   - topicId = [valid topic UUID]
   - courseId = [valid course UUID]
   - title = "Ordering at Restaurant"
   - titleJapanese = "レストランで注文する"
   - description = "Learn how to order food at a restaurant"
   - descriptionVi = "Học cách đặt đồ ăn tại nhà hàng"
   - scenario = "You are at a restaurant and want to order"
   - aiRole = "Restaurant Staff (polite, helpful)"
   - learnerRole = "Customer"
   - difficultyScore = 5
   - vocabularyHints = "[\"予約\", \"テーブル\", \"人数\"]"
   - grammarPoints = "[\"〜ください\", \"〜たいです\"]"
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationController calls ConversationService.createConversation().
4. ConversationService validates required fields (aiRole, learnerRole, scenario must not be empty).
5. ConversationService validates title length (max 255 chars) and description length (max 1000 chars).
6. ConversationService creates new Conversation entity with all provided fields via ConversationRepository.save().
7. ConversationRepository saves the new conversation with default status=DRAFT.
8. System returns the created conversation response.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.id = [non-empty UUID]
- data.title = "Ordering at Restaurant"
- data.titleJapanese = "レストランで注文する"
- data.aiRole = "Restaurant Staff (polite, helpful)"
- data.learnerRole = "Customer"
- data.scenario = "You are at a restaurant and want to order"
- data.status = "DRAFT"
- data.createdBy = [manager UUID]
Database state: conversation.status = DRAFT, conversation.deletedAt = null, conversation.topicId = [topic UUID], conversation.createdBy = manager.getId()

---

### Test Case ID: TC_CCV_002
**Test Case Description:** Create conversation without aiRole — validation error (BR-186)

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation Topic exists

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/conversation/conversations with Authorization: Bearer {token} and JSON body with aiRole = null.
2. Request validation fails at controller level due to aiRole being required.
3. MethodArgumentNotValidException is thrown.
4. System returns validation error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
Database state: No conversation created

---

### Test Case ID: TC_CCV_003
**Test Case Description:** Create conversation without learnerRole — validation error (BR-186)

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation Topic exists

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/conversation/conversations with Authorization: Bearer {token} and JSON body with learnerRole = null.
2. Request validation fails at controller level due to learnerRole being required.
3. MethodArgumentNotValidException is thrown.
4. System returns validation error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
Database state: No conversation created

---

### Test Case ID: TC_CCV_004
**Test Case Description:** Create conversation without scenario — validation error (BR-186)

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation Topic exists

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/conversation/conversations with Authorization: Bearer {token} and JSON body with scenario = null.
2. Request validation fails at controller level due to scenario being required.
3. MethodArgumentNotValidException is thrown.
4. System returns validation error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
Database state: No conversation created

---

### Test Case ID: TC_CCV_005
**Test Case Description:** Create conversation without title — validation error (BR-183)

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation Topic exists

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/conversation/conversations with Authorization: Bearer {token} and JSON body with title = null.
2. Request validation fails at controller level due to title being required.
3. MethodArgumentNotValidException is thrown.
4. System returns validation error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
Database state: No conversation created

---

### Test Case ID: TC_CCV_006
**Test Case Description:** Create conversation with empty title — validation error (BR-183)

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation Topic exists

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/conversation/conversations with Authorization: Bearer {token} and JSON body with title = "".
2. Request validation fails at controller level due to blank title.
3. MethodArgumentNotValidException is thrown.
4. System returns validation error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
Database state: No conversation created

---

### Test Case ID: TC_CCV_007
**Test Case Description:** Create conversation with title exactly 255 characters — succeeds (BR-183)

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation Topic exists

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/conversation/conversations with Authorization: Bearer {token} and JSON body with title = [255 'a' characters].
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationController calls ConversationService.createConversation().
4. ConversationService validates title length (max 255 chars, should succeed).
5. ConversationService creates new Conversation entity via ConversationRepository.save().
6. System returns the created conversation response.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.id = [non-empty UUID]
Database state: conversation.title = [255 'a' characters], conversation.title.length() = 255

---

### Test Case ID: TC_CCV_008
**Test Case Description:** Create conversation with title exceeding 255 characters — validation error (BR-183)

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation Topic exists

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/conversation/conversations with Authorization: Bearer {token} and JSON body with title = [256 'a' characters].
2. Request validation fails at controller level due to title exceeding max length.
3. MethodArgumentNotValidException is thrown.
4. System returns validation error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
Database state: No conversation created

---

### Test Case ID: TC_CCV_009
**Test Case Description:** Create conversation with description exactly 1000 characters — succeeds (BR-184)

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation Topic exists

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/conversation/conversations with Authorization: Bearer {token} and JSON body with description = [1000 'a' characters].
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationController calls ConversationService.createConversation().
4. ConversationService validates description length (max 1000 chars, should succeed).
5. ConversationService creates new Conversation entity via ConversationRepository.save().
6. System returns the created conversation response.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.id = [non-empty UUID]
Database state: conversation.description = [1000 'a' characters], conversation.description.length() = 1000

---

### Test Case ID: TC_CCV_010
**Test Case Description:** Create conversation with description exceeding 1000 characters — validation error (BR-184)

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation Topic exists

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/conversation/conversations with Authorization: Bearer {token} and JSON body with description = [1001 'a' characters].
2. Request validation fails at controller level due to description exceeding max length.
3. MethodArgumentNotValidException is thrown.
4. System returns validation error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
Database state: No conversation created

--- 

### Test Case ID: TC_CCV_012
**Test Case Description:** Create conversation with empty missions array — validation error (BR-281)

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation Topic exists

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/conversation/conversations with Authorization: Bearer {token} and JSON body with missions = [] (empty array).
2. Request validation fails at controller level due to missions being required (at least 1 mission).
3. MethodArgumentNotValidException is thrown.
4. System returns validation error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
Database state: No conversation created, no missions created

---

### Test Case ID: TC_CCV_015
**Test Case Description:** Create conversation with exactly 3 missions — succeeds and missions persisted with correct orderIndex (BR-281 max boundary)

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation Topic exists

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/conversation/conversations with Authorization: Bearer {token} and JSON body with 3 missions:
   - Mission 1: "Greeting", "Say hello to staff"
   - Mission 2: "Order Food", "Make a reservation"
   - Mission 3: "Pay Bill", "Request and pay bill"
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationController calls ConversationService.createConversation().
4. ConversationService validates mission count (min 1, max 3, should succeed).
5. ConversationService creates new Conversation entity and 3 mission entities via repositories.
6. ConversationRepository saves the conversation and ConversationMissionRepository saves missions with orderIndex 0, 1, 2.
7. System returns the created conversation response with missions array.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.id = [non-empty UUID]
- data.missions.length() = 3
- data.missions[0].title = "Greeting", data.missions[0].orderIndex = 0
- data.missions[1].title = "Order Food", data.missions[1].orderIndex = 1
- data.missions[2].title = "Pay Bill", data.missions[2].orderIndex = 2
Database state: conversation exists, 3 missions exist with orderIndex 0, 1, 2, all have correct titles

---

### Test Case ID: TC_CCV_016
**Test Case Description:** Create conversation with 4 missions — validation error (BR-281 exceeds max)

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation Topic exists

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/conversation/conversations with Authorization: Bearer {token} and JSON body with 4 missions.
2. Request validation fails at controller level due to mission count exceeding max of 3.
3. MethodArgumentNotValidException is thrown.
4. System returns validation error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
Database state: No conversation created, no missions created

---

### Test Case ID: TC_CCV_018
**Test Case Description:** Create conversation with non-existent topic — returns 404 Not Found

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Topic ID does not exist in database

**Test Case Procedure:**
1. Client sends POST /api/v1/manager/conversation/conversations with Authorization: Bearer {token} and JSON body with topicId = [non-existent UUID].
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationController calls ConversationService.createConversation().
4. ConversationService validates topic existence via ConversationTopicRepository.findById().
5. ConversationTopicRepository executes database query and returns empty.
6. ConversationService throws ResourceNotFoundException.
7. GlobalExceptionHandler maps exception to 404 response.

**Expected Results:** HTTP 404 NOT FOUND
Response JSON:
- success = false

---

## Section 4 — Update Conversation Tests (PUT /api/v1/manager/conversation/conversations/{conversationId})

### Test Case ID: TC_CUV_001
**Test Case Description:** Update conversation title only — only title changed, other fields preserved, createdAt/createdBy unchanged

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation Topic exists
- Conversation inserted via conversationRepository.save() with:
  - title = "Original Title"
  - description = "Original Description"
  - jlptLevel = N3
  - difficultyScore = 3

**Test Case Procedure:**
1. Record conversation's createdAt and createdBy before update.
2. Wait 100ms to ensure timestamp difference.
3. Client sends PUT /api/v1/manager/conversation/conversations/{conversationId} with Authorization: Bearer {token} and JSON body:
   - {"title":"New Title Only"}
4. AuthFilter validates token and resolves MANAGER user identity.
5. ConversationController calls ConversationService.updateConversation().
6. ConversationService retrieves conversation by ID via ConversationRepository.findByIdAndNotDeleted().
7. ConversationService applies partial update (only title), preserving other fields.
8. ConversationService saves updated conversation via ConversationRepository.save().
9. System returns the updated conversation response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.title = "New Title Only"
- data.description = "Original Description" (preserved)
- data.jlptLevel = "N3" (preserved)
- data.difficultyScore = 3 (preserved)
Database state: conversation.title = "New Title Only", conversation.description = "Original Description", conversation.createdAt = [original], conversation.createdBy = [original], conversation.updatedAt > [original createdAt]

---

### Test Case ID: TC_CUV_002
**Test Case Description:** Update conversation all fields — all updated in database

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation Topic exists
- Conversation inserted via conversationRepository.save()

**Test Case Procedure:**
1. Client sends PUT /api/v1/manager/conversation/conversations/{conversationId} with Authorization: Bearer {token} and JSON body:
   - title = "Updated Title"
   - titleJapanese = "新しいタイトル"
   - description = "New description"
   - difficultyScore = 8
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationController calls ConversationService.updateConversation().
4. ConversationService retrieves conversation by ID via ConversationRepository.findByIdAndNotDeleted().
5. ConversationService applies all updates to conversation fields.
6. ConversationService saves updated conversation via ConversationRepository.save().
7. System returns the updated conversation response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.title = "Updated Title"
- data.titleJapanese = "新しいタイトル"
- data.description = "New description"
- data.difficultyScore = 8
Database state: all fields match updated values, updatedAt is newer than before

---

### Test Case ID: TC_CUV_003
**Test Case Description:** Update conversation with title exceeding 255 characters — validation error

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation Topic exists
- Conversation inserted via conversationRepository.save()

**Test Case Procedure:**
1. Client sends PUT /api/v1/manager/conversation/conversations/{conversationId} with Authorization: Bearer {token} and JSON body with title = [256 'a' characters].
2. Request validation fails at controller level due to title exceeding max length.
3. MethodArgumentNotValidException is thrown.
4. System returns validation error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
Database state: conversation.title unchanged (original value preserved)

---

## Section 5 — Delete Conversation Tests (DELETE /api/v1/manager/conversation/conversations/{conversationId})

### Test Case ID: TC_CDL_001
**Test Case Description:** Delete conversation — soft-deleted with deletedAt and deletedBy set, no longer in active list

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation Topic exists
- Conversation inserted via conversationRepository.save()

**Test Case Procedure:**
1. Client sends DELETE /api/v1/manager/conversation/conversations/{conversationId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationController calls ConversationService.deleteConversation().
4. ConversationService retrieves conversation by ID via ConversationRepository.findByIdAndNotDeleted().
5. ConversationRepository executes database query and returns the conversation object.
6. ConversationService performs soft delete: sets deletedAt and deletedBy fields.
7. ConversationService saves updated conversation via ConversationRepository.save().
8. System returns the delete success response with message "Conversation deleted successfully".

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- message = "Conversation deleted successfully"
Database state: conversation.deletedAt = [current timestamp] (not null), conversation.deletedBy = [managerId], conversation.title = "Test Conversation" (data preserved)
Verification: GET /api/v1/manager/conversation/topics/{topicId}/conversations returns totalElements = 0

---

### Test Case ID: TC_CDL_002
**Test Case Description:** Delete conversation cascades soft-delete to all associated missions

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation Topic exists
- Conversation inserted via conversationRepository.save()
- 3 ConversationMissions inserted via missionRepository.save():
  - "Mission 1"
  - "Mission 2"
  - "Mission 3"

**Test Case Procedure:**
1. Client sends DELETE /api/v1/manager/conversation/conversations/{conversationId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationController calls ConversationService.deleteConversation().
4. ConversationService retrieves conversation by ID and cascades soft delete to all associated missions via JPA @OnDelete cascade.
5. ConversationService saves updated conversation with soft delete fields.
6. System returns the delete success response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
Database state: conversation.deletedAt = [current timestamp] (not null), conversation.deletedBy = [managerId]
Mission verification: All 3 missions have deletedAt = [current timestamp] (not null), deletedBy = [managerId]

---

### Test Case ID: TC_CDL_005
**Test Case Description:** Delete conversation — learner data preserved (soft delete only, no cascade to learner progress)

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation Topic exists
- Conversation inserted via conversationRepository.save()

**Test Case Procedure:**
1. Client sends DELETE /api/v1/manager/conversation/conversations/{conversationId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationController calls ConversationService.deleteConversation().
4. ConversationService performs soft delete: sets deletedAt and deletedBy.
5. ConversationService saves updated conversation via ConversationRepository.save().
6. System returns the delete success response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
Database state: conversation.deletedAt = [current timestamp] (not null)
Note: In real scenario, user_conversation_progress (learner data) would still exist with the conversation's data preserved

---

## Section 6 — Status Transition Tests (PATCH /api/v1/manager/conversation/conversations/{conversationId}/publish and /archive)

### Test Case ID: TC_STR_001
**Test Case Description:** Publish conversation changes status from DRAFT to PUBLISHED

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation Topic exists
- Conversation inserted via conversationRepository.save() with status=DRAFT

**Test Case Procedure:**
1. Client sends PATCH /api/v1/manager/conversation/conversations/{conversationId}/publish with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationController calls ConversationService.publishConversation().
4. ConversationService retrieves conversation by ID via ConversationRepository.findByIdAndNotDeleted().
5. ConversationService updates conversation status to PUBLISHED.
6. ConversationService saves updated conversation via ConversationRepository.save().
7. System returns the updated conversation response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.status = "PUBLISHED"
Database state: conversation.status = PUBLISHED

---

### Test Case ID: TC_STR_002
**Test Case Description:** Archive conversation changes status to ARCHIVED

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation Topic exists
- Conversation inserted via conversationRepository.save()

**Test Case Procedure:**
1. Client sends PATCH /api/v1/manager/conversation/conversations/{conversationId}/archive with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationController calls ConversationService.archiveConversation().
4. ConversationService retrieves conversation by ID via ConversationRepository.findByIdAndNotDeleted().
5. ConversationService updates conversation status to ARCHIVED.
6. ConversationService saves updated conversation via ConversationRepository.save().
7. System returns the updated conversation response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.status = "ARCHIVED"
Database state: conversation.status = ARCHIVED

---

### Test Case ID: TC_STR_003
**Test Case Description:** Publish conversation already in PUBLISHED status is idempotent

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation Topic exists
- Conversation inserted via conversationRepository.save() with status=PUBLISHED

**Test Case Procedure:**
1. Client sends PATCH /api/v1/manager/conversation/conversations/{conversationId}/publish with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves MANAGER user identity.
3. ConversationController calls ConversationService.publishConversation().
4. ConversationService retrieves conversation by ID and sets status to PUBLISHED (already PUBLISHED).
5. ConversationService saves updated conversation via ConversationRepository.save().
6. System returns the updated conversation response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.status = "PUBLISHED"
Database state: conversation.status = PUBLISHED (unchanged)

---

## Section 7 — Cross-Cutting Tests

### Test Case ID: TC_CROSS_001
**Test Case Description:** Full lifecycle: create → update → publish → get → delete

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation Topic exists

**Test Case Procedure:**
1. Step 1 — CREATE: Client sends POST /api/v1/manager/conversation/conversations with valid body (title, aiRole, learnerRole, scenario). Response status = 201, conversation created with status=DRAFT.
2. Step 2 — UPDATE: Client sends PUT /api/v1/manager/conversation/conversations/{conversationId} with new title and description. Response status = 200, conversation updated.
3. Step 3 — PUBLISH: Client sends PATCH /api/v1/manager/conversation/conversations/{conversationId}/publish. Response status = 200, conversation status = PUBLISHED.
4. Step 4 — GET: Client sends GET /api/v1/manager/conversation/conversations/{conversationId}. Response status = 200, data.status = PUBLISHED, data.title = [updated value].
5. Step 5 — DELETE: Client sends DELETE /api/v1/manager/conversation/conversations/{conversationId}. Response status = 200, conversation soft-deleted.
6. Step 6 — GET (after delete): Client sends GET /api/v1/manager/conversation/conversations/{conversationId}. Response status = 404.

**Expected Results:**
- All status codes as specified above
- Conversation progresses through DRAFT → PUBLISHED → DELETED (soft delete)
- Updated fields persisted correctly
- After deletion, conversation no longer accessible

---

### Test Case ID: TC_CROSS_002
**Test Case Description:** Multiple conversations isolation by topic

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- 2 Conversation Topics exist
- 3 Conversations under Topic 1: "Conv 1.1", "Conv 1.2", "Conv 1.3"
- 2 Conversations under Topic 2: "Conv 2.1", "Conv 2.2"

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/conversation/topics/{topic1Id}/conversations?page=0&size=10 with Authorization: Bearer {token}.
2. System retrieves conversations filtered by topic 1 and returns paginated response.
3. Client sends GET /api/v1/manager/conversation/topics/{topic2Id}/conversations?page=0&size=10 with Authorization: Bearer {token}.
4. System retrieves conversations filtered by topic 2 and returns paginated response.

**Expected Results:**
- Topic 1 GET: HTTP 200 OK, data.totalElements = 3, data.content.length() = 3
- Topic 2 GET: HTTP 200 OK, data.totalElements = 2, data.content.length() = 2

---

### Test Case ID: TC_CROSS_003
**Test Case Description:** Pagination consistent with soft-deleted items excluded

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation Topic exists
- 15 Conversations inserted
- 2 conversations soft-deleted

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/conversation/topics/{topicId}/conversations?page=0&size=10 with Authorization: Bearer {token}.
2. ConversationService retrieves conversations with deletedAt IS NULL filter.
3. System returns paginated response with 13 active conversations (15 - 2 deleted).

**Expected Results:** HTTP 200 OK
Response JSON (page 0):
- success = true
- data.totalElements = 13
- data.totalPages = 2
- data.content.length() = 10

Test page 1:
- data.totalElements = 13
- data.totalPages = 2
- data.content.length() = 3

---

## Section 8 — RBAC Tests

### Test Case ID: TC_RBAC_001
**Test Case Description:** CONTENT_MANAGER has full access to all conversation management endpoints

**Pre-conditions:**
- Manager accessToken is available (from prior login)
- Conversation Topic exists
- Conversation exists

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/conversation/conversations/{conversationId} with Authorization: Bearer {managerToken}. Expected: HTTP 200 OK.
2. Client sends POST /api/v1/manager/conversation/conversations with Authorization: Bearer {managerToken} and valid body. Expected: HTTP 201 CREATED.

**Expected Results:**
- GET endpoint: HTTP 200 OK
- POST endpoint: HTTP 201 CREATED

---

### Test Case ID: TC_RBAC_002
**Test Case Description:** LEARNER gets 403 Forbidden for all manager conversation endpoints

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Conversation Topic exists
- Conversation exists

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/conversation/conversations/{conversationId} with Authorization: Bearer {learnerToken}. 
2. Client sends POST /api/v1/manager/conversation/conversations with Authorization: Bearer {learnerToken} and valid body.

**Expected Results:**
- GET endpoint: HTTP 403 FORBIDDEN
- POST endpoint: HTTP 403 FORBIDDEN

---

### Test Case ID: TC_RBAC_003
**Test Case Description:** TEACHER gets 403 Forbidden for all manager conversation endpoints

**Pre-conditions:**
- Teacher accessToken is available (from prior login)
- Conversation exists

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/conversation/conversations/{conversationId} with Authorization: Bearer {teacherToken}.

**Expected Results:** HTTP 403 FORBIDDEN

---

### Test Case ID: TC_RBAC_004
**Test Case Description:** ADMIN gets 403 Forbidden for manager conversation endpoints (CONTENT_MANAGER only)

**Pre-conditions:**
- Admin accessToken is available (from prior login)
- Conversation exists

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/conversation/conversations/{conversationId} with Authorization: Bearer {adminToken}.

**Expected Results:** HTTP 403 FORBIDDEN

---

### Test Case ID: TC_RBAC_005
**Test Case Description:** Unauthenticated request without token gets 401 Unauthorized

**Pre-conditions:**
- No authentication token provided
- Conversation exists

**Test Case Procedure:**
1. Client sends GET /api/v1/manager/conversation/conversations/{conversationId} without Authorization header.
2. AuthFilter detects missing token and returns authentication error.
3. Client sends POST /api/v1/manager/conversation/conversations without Authorization header and with valid body.

**Expected Results:**
- GET endpoint: HTTP 401 UNAUTHORIZED
- POST endpoint: HTTP 401 UNAUTHORIZED


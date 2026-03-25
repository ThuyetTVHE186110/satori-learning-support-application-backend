# Create Custom Conversation Topic Test Script

## Overview
This document contains detailed test scripts for the Create Custom Conversation Topic API endpoints in Satori Learning Support Application backend.

## Test Environment Setup

### Pre-conditions
- Test DB running via `application-test.properties` 
- PostgreSQL `satori_test` database created and initialized
- Docker environment up and running
- AI services mocked to prevent actual HTTP calls
- ConversationGenerationService mocked for context generation

---

## Section 4 — Create Custom Conversation Topic Tests

### Test Case ID: TC_SP_CT_001
**Test Case Description:** Create personal topic

**Pre-conditions:**
- Learner accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/conversation/my-topics with title and scenario in body, Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. CustomTopicController forwards the request to CustomTopicService.
4. CustomTopicService creates new UserConversationTopic with DRAFT status.
5. CustomTopicService persists topic to UserConversationTopicRepository.
6. System returns created topic response.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.title = "My Topic"

Database state:
- UserConversationTopicRepository contains 1 topic for the learner
- Topic has title "My Topic" and scenario "Practice shopping"

---

### Test Case ID: TC_SP_CT_002
**Test Case Description:** Generate context with scenario

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- User topic created with scenario
- ConversationGenerationService mocked to return context

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/conversation/my-topics/{id}/generate-context, Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. CustomTopicController validates topic exists and belongs to user.
4. CustomTopicService calls ConversationGenerationService.generateUserTopicContext().
5. ConversationGenerationService returns AI-generated context.
6. CustomTopicService updates topic with aiGeneratedContext.
7. System returns generated context response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.aiGeneratedContext exists and is not empty

Database state:
- UserConversationTopic.aiGeneratedContext contains generated context

---

### Test Case ID: TC_SP_CT_003
**Test Case Description:** Generate context without scenario returns 400 BAD REQUEST

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- User topic created with null scenario

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/conversation/my-topics/{id}/generate-context, Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. CustomTopicController validates topic scenario is not null/blank.
4. CustomTopicController rejects request due to missing scenario.
5. System returns bad request error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_SP_CT_004
**Test Case Description:** Start free-talk session

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- RoleplayAIService mocked for setup phase

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/free-talk/sessions with jlptLevel and language, Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController creates free-talk session with setup phase.
4. RoleplayService calls RoleplayAIService for setup opening.
5. System returns created session response.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.messages.length() = 1 (setup opening message)

---

### Test Case ID: TC_SP_CT_005
**Test Case Description:** Create personal topic with blank title returns 400 BAD REQUEST

**Pre-conditions:**
- Learner accessToken is available (from prior login)

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/conversation/my-topics with blank title, Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. CustomTopicController validates title is not blank after trimming.
4. CustomTopicController rejects request due to blank title.
5. System returns bad request error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_SP_CT_006
**Test Case Description:** No token returns 401 UNAUTHORIZED

**Pre-conditions:**
- No authorization token provided

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/conversation/my-topics with title and scenario.
2. No Authorization header provided.
3. AuthFilter rejects request.
4. System returns unauthorized error response.

**Expected Results:** HTTP 401 UNAUTHORIZED

---

### Test Case ID: TC_SP_CT_007
**Test Case Description:** Free-talk invalid JLPT level returns 400 BAD REQUEST

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Invalid JLPT level (N9) provided

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/free-talk/sessions with invalid jlptLevel, Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController validates JLPT level (N1-N5 only).
4. RoleplayController rejects request due to invalid level.
5. System returns bad request error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_SP_CT_008
**Test Case Description:** List my topics pagination

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- 2 user topics created for the learner

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/conversation/my-topics with pagination parameters (page=0, size=1), Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. CustomTopicController forwards request to CustomTopicService.
4. CustomTopicService retrieves learner's topics with pagination.
5. System returns paginated topic list.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.totalElements = 2 (total topics)
- data.content.length() = 1 (page size)

---

### Test Case ID: TC_SP_CT_009
**Test Case Description:** Get my topic by id

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- User topic created for the learner

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/conversation/my-topics/{id}, Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. CustomTopicController validates topic exists and belongs to user.
4. CustomTopicService retrieves topic details.
5. System returns topic details.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.title = "Topic 1"

---

### Test Case ID: TC_SP_CT_010
**Test Case Description:** Update my topic

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- User topic created for the learner

**Test Case Procedure:**
1. Client sends PUT /api/v1/learner/conversation/my-topics/{id} with updated title and scenario, Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. CustomTopicController validates topic exists and belongs to user.
4. CustomTopicService updates topic with new values.
5. CustomTopicService persists updated topic.
6. System returns updated topic response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.title = "Updated"

Database state:
- UserConversationTopic has updated title and scenario

---

### Test Case ID: TC_SP_CT_011
**Test Case Description:** Delete my topic

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- User topic created for the learner

**Test Case Procedure:**
1. Client sends DELETE /api/v1/learner/conversation/my-topics/{id}, Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. CustomTopicController validates topic exists and belongs to user.
4. CustomTopicService deletes topic from repository.
5. System returns success response.
6. Client attempts to GET deleted topic.
7. System returns not found.

**Expected Results:** HTTP 200 OK for delete, then HTTP 404 NOT FOUND for get

Database state:
- UserConversationTopic no longer exists for deleted topic

---

### Test Case ID: TC_SP_CT_012
**Test Case Description:** Access another user's topic returns 404 NOT FOUND

**Pre-conditions:**
- Owner learner has created a topic
- Other learner has valid token

**Test Case Procedure:**
1. Other learner sends GET /api/v1/learner/conversation/my-topics/{id} for owner's topic, Authorization: Bearer {token}.
2. AuthFilter validates other learner token.
3. CustomTopicService attempts to find topic for other learner.
4. Topic not found (belongs to owner).
5. System returns not found error response.

**Expected Results:** HTTP 404 NOT FOUND

---

### Test Case ID: TC_SP_CT_013
**Test Case Description:** Generate context with blank scenario returns 400 BAD REQUEST

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- User topic created with blank scenario (whitespace only)

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/conversation/my-topics/{id}/generate-context, Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. CustomTopicController validates scenario is not blank after trimming.
4. CustomTopicController rejects request due to blank scenario.
5. System returns bad request error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_SP_CT_014
**Test Case Description:** Generate context persists aiGeneratedContext

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- User topic created with scenario
- ConversationGenerationService mocked to return context

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/conversation/my-topics/{id}/generate-context, Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. CustomTopicService calls ConversationGenerationService.generateUserTopicContext().
4. ConversationGenerationService returns AI-generated context.
5. CustomTopicService persists aiGeneratedContext to topic.
6. System returns success response.

**Expected Results:** HTTP 200 OK
Database state:
- UserConversationTopic.aiGeneratedContext contains generated context

---

### Test Case ID: TC_SP_CT_015
**Test Case Description:** Update another user's topic returns 404 NOT FOUND

**Pre-conditions:**
- Owner learner has created a topic
- Attacker learner has valid token

**Test Case Procedure:**
1. Attacker sends PUT /api/v1/learner/conversation/my-topics/{id} for owner's topic, Authorization: Bearer {token}.
2. AuthFilter validates attacker token.
3. CustomTopicService attempts to find topic for attacker.
4. Topic not found (belongs to owner).
5. System returns not found error response.

**Expected Results:** HTTP 404 NOT FOUND

---

### Test Case ID: TC_SP_CT_016
**Test Case Description:** Delete another user's topic returns 404 NOT FOUND

**Pre-conditions:**
- Owner learner has created a topic
- Attacker learner has valid token

**Test Case Procedure:**
1. Attacker sends DELETE /api/v1/learner/conversation/my-topics/{id} for owner's topic, Authorization: Bearer {token}.
2. AuthFilter validates attacker token.
3. CustomTopicService attempts to find topic for attacker.
4. Topic not found (belongs to owner).
5. System returns not found error response.

**Expected Results:** HTTP 404 NOT FOUND

Verification:
- Owner's topic still exists in database (not deleted)

---

### Test Case ID: TC_SP_CT_017
**Test Case Description:** Generate context for another user's topic returns 404 NOT FOUND

**Pre-conditions:**
- Owner learner has created a topic
- Attacker learner has valid token

**Test Case Procedure:**
1. Attacker sends POST /api/v1/learner/conversation/my-topics/{id}/generate-context for owner's topic, Authorization: Bearer {token}.
2. AuthFilter validates attacker token.
3. CustomTopicService attempts to find topic for attacker.
4. Topic not found (belongs to owner).
5. System returns not found error response.

**Expected Results:** HTTP 404 NOT FOUND

---

### Test Case ID: TC_SP_CT_018
**Test Case Description:** Manager create personal topic returns 403 FORBIDDEN

**Pre-conditions:**
- Manager user with valid token

**Test Case Procedure:**
1. Manager sends POST /api/v1/learner/conversation/my-topics with title and scenario, Authorization: Bearer {token}.
2. AuthFilter validates manager token.
3. SecurityConfig blocks access - MANAGER cannot access LEARNER endpoints.
4. System returns forbidden error response.

**Expected Results:** HTTP 403 FORBIDDEN

---

### Test Case ID: TC_SP_CT_019
**Test Case Description:** Free-talk no token returns 401 UNAUTHORIZED

**Pre-conditions:**
- No authorization token provided

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/free-talk/sessions with jlptLevel and language.
2. No Authorization header provided.
3. AuthFilter rejects request.
4. System returns unauthorized error response.

**Expected Results:** HTTP 401 UNAUTHORIZED

---

## Test Data Cleanup

### After Each Test Case:
- Clear all user conversation topics
- Clear user sessions and user data
- Reset AI service mocks
- Verify no orphaned records remain

### Test Execution Order:
Tests can be executed in any order as they use isolated test data and clean up after each execution.

### Mock Service Behavior:
- ConversationGenerationService: Mocked to return predictable context
- RoleplayAIService: Mocked to return setup prompts and messages
- MissionEvaluatorAI: Mocked to return empty progress lists
- PronunciationAssessmentProvider: Mocked to return unavailable for most tests

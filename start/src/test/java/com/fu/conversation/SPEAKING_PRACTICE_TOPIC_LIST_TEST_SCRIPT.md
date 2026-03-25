# Speaking Practice - View Conversation Topic List Test Script

## Overview
This document contains detailed test scripts for the View Conversation Topic List API endpoints in Satori Learning Support Application backend.

## Test Environment Setup

### Pre-conditions
- Test DB running via `application-test.properties` 
- PostgreSQL `satori_test` database created and initialized
- Docker environment up and running
- AI services mocked to prevent actual HTTP calls

---

## Section 2 — View Conversation Topic List Tests

### Test Case ID: TC_SP_VTP_001
**Test Case Description:** Learner views published topics only, sorted by orderIndex

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Published theme created via saveTheme()
- Multiple conversation topics created via saveTopic():
  - Topic B (status=PUBLISHED, orderIndex=2, isRoleplay=true)
  - Topic A (status=PUBLISHED, orderIndex=1, isRoleplay=true)
  - Topic Draft (status=DRAFT, orderIndex=0, isRoleplay=true)

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/conversation/themes/{themeId}/topics with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. ConversationController forwards the request to ConversationService.
4. ConversationService retrieves topics from ConversationTopicRepository (excluding DRAFT records).
5. ConversationTopicRepository queries the database and returns data sorted by orderIndex.
6. System returns topic list response with practice statistics.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.length() = 2
- data[0].title = "Topic A" (orderIndex=1, first in sort)
- data[1].title = "Topic B" (orderIndex=2, second in sort)
- data[*].status = "PUBLISHED"
- data[*].orderIndex exists
- data[*].practiced = false (no sessions)
- data[*].practiceCount = 0

---

### Test Case ID: TC_SP_VTP_002
**Test Case Description:** Practice statistics populated when user has sessions

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Published theme created via saveTheme()
- Published topic created via saveTopic()
- Conversation session created via saveSession() linking learner to topic

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/conversation/themes/{themeId}/topics with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. ConversationController forwards the request to ConversationService.
4. ConversationService retrieves topics and queries ConversationSessionRepository for user's practice statistics.
5. ConversationSessionRepository returns session count for the user-topic pair.
6. System returns topic list response with populated practice statistics.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data[0].practiced = true
- data[0].practiceCount = 1
- data[0].lastPracticedAt exists (timestamp)

---

### Test Case ID: TC_SP_VTP_003
**Test Case Description:** Content Manager role forbidden → 403 FORBIDDEN

**Pre-conditions:**
- Content Manager accessToken is available (from prior login)
- Published theme and topics exist in database

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/conversation/themes/{themeId}/topics with Authorization: Bearer {managerToken}.
2. AuthFilter validates token and resolves CONTENT_MANAGER identity.
3. SecurityConfig blocks access - endpoint requires LEARNER role.
4. System returns access denied error response.

**Expected Results:** HTTP 403 FORBIDDEN

---

### Test Case ID: TC_SP_VTP_004
**Test Case Description:** View published topic detail with missions

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Published theme created via saveTheme()
- Published topic created via saveTopic()
- Two missions created via saveMission():
  - Mission 1 (orderIndex=1)
  - Mission 2 (orderIndex=2)

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/conversation/topics/{topicId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. ConversationController forwards the request to ConversationService.
4. ConversationService retrieves topic from ConversationTopicRepository (must be PUBLISHED).
5. ConversationService retrieves associated missions from ConversationMissionRepository.
6. ConversationTopicRepository returns the topic details with mission list.
7. System returns topic detail response with missions.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.id = topicId
- data.status = "PUBLISHED"
- data.title = "Topic A"
- data.orderIndex = 1
- data.roleplayContext exists
- data.missions exists (array)
- data.missions.length() = 2
- data.missions[0].id = mission1Id
- data.missions[0].title = "Mission 1"
- data.missions[0].orderIndex = 1
- data.missions[1].id = mission2Id
- data.missions[1].title = "Mission 2"
- data.missions[1].orderIndex = 2

---

### Test Case ID: TC_SP_VTP_005
**Test Case Description:** Non-published topic detail returns 404 NOT FOUND

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Published theme created via saveTheme()
- Draft topic created via saveTopic() with status=DRAFT

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/conversation/topics/{topicId} with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. ConversationController forwards the request to ConversationService.
4. ConversationService retrieves topic from ConversationTopicRepository but finds DRAFT status.
5. ConversationService throws BusinessException with NOT_FOUND.
6. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND

---

### Test Case ID: TC_SP_VTP_006
**Test Case Description:** No authorization token → 401 UNAUTHORIZED

**Pre-conditions:**
- Published theme and topics exist in database
- No Authorization header provided

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/conversation/themes/{themeId}/topics without Authorization header.
2. AuthFilter detects missing token and returns unauthorized error.
3. System returns the unauthorized error response.

**Expected Results:** HTTP 401 UNAUTHORIZED

---

### Test Case ID: TC_SP_VTP_007
**Test Case Description:** No practice statistics when user has no sessions

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Published theme created via saveTheme()
- Published topic created via saveTopic()
- No conversation sessions exist for the learner

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/conversation/themes/{themeId}/topics with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. ConversationController forwards the request to ConversationService.
4. ConversationService retrieves topics and queries ConversationSessionRepository for user's practice statistics.
5. ConversationSessionRepository returns empty result (no sessions found).
6. System returns topic list response with default practice statistics.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data[0].practiced = false
- data[0].practiceCount = 0

---

### Test Case ID: TC_SP_VTP_008
**Test Case Description:** Soft-deleted topic excluded from results

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Published theme created via saveTheme()
- Published topic created via saveTopic()
- Soft-deleted topic created via saveSoftDeletedTopic()

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/conversation/themes/{themeId}/topics with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. ConversationController forwards the request to ConversationService.
4. ConversationService retrieves topics from ConversationTopicRepository (excluding soft-deleted records).
5. ConversationTopicRepository queries the database filtering deletedAt IS NULL.
6. System returns topic list response excluding soft-deleted topics.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.length() = 1
- data[0].title = "Topic A" (only published, non-deleted topic)

---

### Test Case ID: TC_SP_VTP_009
**Test Case Description:** Theme with no topics returns empty list

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Published theme created via saveTheme()
- No topics exist for the theme

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/conversation/themes/{themeId}/topics with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. ConversationController forwards the request to ConversationService.
4. ConversationService retrieves topics from ConversationTopicRepository.
5. ConversationTopicRepository queries the database and returns empty list.
6. System returns empty topic list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.length() = 0

---

### Test Case ID: TC_SP_VTP_011
**Test Case Description:** Practice statistics count multiple sessions

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Published theme created via saveTheme()
- Published topic created via saveTopic()
- Multiple conversation sessions created via saveSession() for same learner-topic pair

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/conversation/themes/{themeId}/topics with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. ConversationController forwards the request to ConversationService.
4. ConversationService retrieves topics and queries ConversationSessionRepository for user's practice statistics.
5. ConversationSessionRepository returns session count (multiple sessions) for the user-topic pair.
6. System returns topic list response with correct practice count.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data[0].practiced = true
- data[0].practiceCount = 2
- data[0].lastPracticedAt exists (timestamp of latest session)

---

### Test Case ID: TC_SP_VTP_012
**Test Case Description:** lastPracticedAt populated when session exists

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Published theme created via saveTheme()
- Published topic created via saveTopic()
- Conversation session created via saveSession() linking learner to topic

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/conversation/themes/{themeId}/topics with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. ConversationController forwards the request to ConversationService.
4. ConversationService retrieves topics and queries ConversationSessionRepository for user's practice statistics.
5. ConversationSessionRepository returns session data with createdAt timestamp.
6. System returns topic list response with lastPracticedAt populated.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data[0].practiced = true
- data[0].practiceCount = 1
- data[0].lastPracticedAt is not empty

---

### Test Case ID: TC_SP_VTP_013
**Test Case Description:** Mixed statistics scenario: 20 topics, user practiced 3

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Published theme created via saveTheme()
- 20 published topics created via loop
- 3 conversation sessions created for first 3 topics

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/conversation/themes/{themeId}/topics with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. ConversationController forwards the request to ConversationService.
4. ConversationService retrieves all 20 topics and queries ConversationSessionRepository for user's practice statistics.
5. ConversationSessionRepository returns session counts for practiced topics (3) and empty for others (17).
6. System returns topic list response with mixed practice statistics.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.length() = 20
- data[0].practiced = true, data[0].practiceCount = 1
- data[1].practiced = true, data[1].practiceCount = 1
- data[2].practiced = true, data[2].practiceCount = 1
- data[3].practiced = false, data[3].practiceCount = 0
- data[19].practiced = false, data[19].practiceCount = 0

---

## Test Data Cleanup

### After Each Test Case:
- Clear all conversation themes, topics, sessions, and user data
- Reset database sequences if applicable
- Verify no orphaned records remain

### Test Execution Order:
Tests can be executed in any order as they use isolated test data and clean up after each execution.

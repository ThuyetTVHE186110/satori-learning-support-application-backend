# Speaking Practice - Practice Conversation with AI (Roleplay) Test Script

## Overview
This document contains detailed test scripts for the Practice Conversation with AI (Roleplay) API endpoints in Satori Learning Support Application backend.

## Test Environment Setup

### Pre-conditions
- Test DB running via `application-test.properties` 
- PostgreSQL `satori_test` database created and initialized
- Docker environment up and running
- AI services mocked to prevent actual HTTP calls
- RoleplayAIService, MissionEvaluatorAI, SessionEvaluatorAI, and PronunciationAssessmentProvider mocked

---

## Section 3 — Practice Conversation with AI (Roleplay) Tests

### Test Case ID: TC_SP_AI_001
**Test Case Description:** Start roleplay session success

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Published theme created via saveTheme()
- Published topic created via saveTopic() with isRoleplay=true
- Mission created via saveMission()
- RoleplayAIService mocked to return system prompt and opening messages

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions with topicId in body and Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService validates topic exists and is published.
5. RoleplayService checks user's active session limit (max 3).
6. RoleplayService creates new ConversationSession with status=ACTIVE.
7. RoleplayService calls RoleplayAIService to build system prompt and generate opening messages.
8. RoleplayService persists opening messages to ConversationMessageRepository.
9. RoleplayService caches system prompt for the session.
10. System returns created session response with messages and missions.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.id exists (sessionId)
- data.status = "ACTIVE"
- data.messages exists (array)
- data.messages.length() = 3 (MISSION_INTRO, SCENE_INTRO, DIALOGUE)
- data.missions exists (array)
- data.missions.length() = 1

---

### Test Case ID: TC_SP_AI_003
**Test Case Description:** Send message success

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Active roleplay session created via prior POST /sessions
- AI services mocked to generate response and evaluate progress

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions/{sessionId}/messages with message content as multipart, Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController handles multipart message request.
4. RoleplayService validates session exists and is ACTIVE.
5. RoleplayService persists user message to ConversationMessageRepository.
6. RoleplayService calls RoleplayAIService to generate AI response.
7. RoleplayService calls MissionEvaluatorAI to evaluate mission progress.
8. RoleplayService calls PronunciationAssessmentProvider if audio provided.
9. RoleplayService persists AI response message.
10. System returns updated session response with new messages.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.messages exists (array)
- data.messages.length() = 1 (new AI response)
- data.messages[0].type = "DIALOGUE"
- data.messages[0].japanese_content exists
- data.messages[0].translation exists
- data.messages[0].corrections = []

---

### Test Case ID: TC_SP_AI_004
**Test Case Description:** Complete session returns evaluation and status update to COMPLETE

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Active roleplay session with messages and session missions created
- SessionEvaluatorAI mocked to return evaluation details

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions/{sessionId}/complete with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService validates session exists and is ACTIVE.
5. RoleplayService calls SessionEvaluatorAI to evaluate the entire conversation.
6. RoleplayService updates session status to COMPLETED.
7. RoleplayService persists evaluation results.
8. System returns session evaluation response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.sessionId = sessionId
- data.languageEvaluation exists
- data.languageEvaluation.summary exists
- data.languageEvaluation.fluencyScore exists
- data.languageEvaluation.accuracyScore exists
- data.languageEvaluation.vocabularyScore exists
- data.languageEvaluation.grammarScore exists
- data.languageEvaluation.strengths exists (array)
- data.languageEvaluation.improvements exists (array)

Database state:
- ConversationSession.status = "COMPLETED"

---

### Test Case ID: TC_SP_AI_005
**Test Case Description:** Start session with draft topic returns 404 NOT FOUND

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Draft topic created via saveTopic() with status=DRAFT

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions with draft topicId, Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService attempts to find topic via ConversationTopicRepository but finds DRAFT status.
5. RoleplayService throws BusinessException with NOT_FOUND.
6. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND

---

### Test Case ID: TC_SP_AI_006
**Test Case Description:** Active session limit reached returns 400 BAD REQUEST

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- 3 active sessions already created for the learner (maximum limit)
- Published topic available for new session

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions with topicId, Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService counts user's active sessions via ConversationSessionRepository.
5. RoleplayService finds 3 active sessions (limit reached).
6. RoleplayService throws BusinessException with ACTIVE_SESSION_LIMIT.
7. System returns the bad request error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_SP_AI_007
**Test Case Description:** Send message with blank content returns 400 BAD REQUEST

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Active roleplay session created

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions/{sessionId}/messages with blank/whitespace content, Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController validates message content (not blank after trimming).
4. RoleplayController rejects blank content and returns bad request.
5. System returns the bad request error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_SP_AI_008
**Test Case Description:** Send message to inactive session returns 400 BAD REQUEST

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Inactive session created with status=COMPLETED

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions/{sessionId}/messages with message content, Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService validates session status and finds it's not ACTIVE.
5. RoleplayService throws BusinessException with SESSION_NOT_ACTIVE.
6. System returns the bad request error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_SP_AI_009
**Test Case Description:** Complete inactive session returns 400 BAD REQUEST

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Inactive session created with status=COMPLETED

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions/{sessionId}/complete, Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService validates session status and finds it's not ACTIVE.
5. RoleplayService throws BusinessException with SESSION_NOT_ACTIVE.
6. System returns the bad request error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_SP_AI_010
**Test Case Description:** Start session with missing topic returns 404 NOT FOUND

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- No topic exists with the provided UUID

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions with non-existent topicId, Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService attempts to find topic via ConversationTopicRepository.
5. ConversationTopicRepository returns empty (topic not found).
6. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND

---

### Test Case ID: TC_SP_AI_011
**Test Case Description:** Start session caches system prompt

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Published theme and topic created
- RoleplayAIService mocked to return system prompt

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions with topicId, Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService creates session and generates system prompt via RoleplayAIService.
5. RoleplayService calls RoleplayAIService.cacheSystemPrompt() to cache the prompt.
6. System returns created session response.
7. Test verifies that cacheSystemPrompt was called with correct parameters.

**Expected Results:** HTTP 201 CREATED
Verification:
- RoleplayAIService.cacheSystemPrompt() called with sessionId and prompt

---

### Test Case ID: TC_SP_AI_012
**Test Case Description:** Start session persists opening messages

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Published theme and topic created
- RoleplayAIService mocked to return opening messages

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions with topicId, Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService creates session and generates opening messages via RoleplayAIService.
5. RoleplayService persists opening messages to ConversationMessageRepository.
6. System returns created session response.
7. Test verifies that messages were persisted correctly in database.

**Expected Results:** HTTP 201 CREATED
Database state:
- ConversationMessageRepository contains opening messages for the session
- Messages have correct sequence numbers and types



---

### Test Case ID: TC_SP_AI_013
**Test Case Description:** Send message to non-existent session returns 404 NOT FOUND

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- No roleplay session exists with the provided UUID

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions/{sessionId}/messages with random UUID, Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService attempts to find session via ConversationSessionRepository.
5. ConversationSessionRepository returns empty (session not found).
6. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND

---

### Test Case ID: TC_SP_AI_014
**Test Case Description:** Message limit exceeded returns 400 BAD REQUEST

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Active roleplay session created
- 50 messages already exist in the session (maximum limit)

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions/{sessionId}/messages with message content, Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService validates session exists and is ACTIVE.
5. RoleplayService counts existing messages in the session via ConversationMessageRepository.
6. RoleplayService finds 50 messages (limit reached).
7. RoleplayService throws BusinessException with MESSAGE_LIMIT_EXCEEDED.
8. System returns the bad request error response.

**Expected Results:** HTTP 400 BAD REQUEST
- code: MESSAGE_LIMIT


---

### Test Case ID: TC_SP_AI_015
**Test Case Description:** Audio too large returns 400 BAD REQUEST

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Active roleplay session created
- Audio file larger than 10MB

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions/{sessionId}/messages with message content and large audio file (10MB + 1 byte), Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController validates multipart request and checks audio file size.
4. RoleplayController rejects audio file exceeding 10MB limit.
5. System returns the bad request error response.

**Expected Results:** HTTP 400 BAD REQUEST
- code: AUDIO_TOO_LARGE
---


---

### Test Case ID: TC_SP_AI_016
**Test Case Description:** Send message with unsupported audio format returns 400 BAD REQUEST

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Active roleplay session created
- Audio file with unsupported format (e.g., .txt, .pdf)

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions/{sessionId}/messages with message content and unsupported audio file, Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController validates multipart request and checks audio file format.
4. RoleplayController rejects unsupported audio format.
5. System returns the bad request error response.

**Expected Results:** HTTP 400 BAD REQUEST
- code: INVALID_AUDIO

---

### Test Case ID: TC_SP_AI_017
**Test Case Description:** Pronunciation provider available with audio

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Active roleplay session created
- PronunciationAssessmentProvider mocked to return available
- AssessmentResult mocked with scores

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions/{sessionId}/messages with message content and audio file, Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController handles multipart message request with audio.
4. RoleplayService validates session exists and is ACTIVE.
5. RoleplayService persists user message to ConversationMessageRepository.
6. RoleplayService calls RoleplayAIService to generate AI response.
7. RoleplayService calls PronunciationAssessmentProvider to assess audio.
8. PronunciationAssessmentProvider returns AssessmentResult with scores.
9. RoleplayService persists AI response message.
10. System returns updated session response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.messages exists (array)
- data.messages.length() = 1 (new AI response)
- data.messages[0].pronunciation does not exist (assessment handled separately)

Verification:
- PronunciationAssessmentProvider.assess() called once with audio data

---

### Test Case ID: TC_SP_AI_018
**Test Case Description:** Free-talk phase transition creates dynamic mission

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- RoleplayAIService mocked for free-talk setup and response
- MissionEvaluatorAI mocked to return empty progress

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/free-talk/sessions with jlptLevel and language, Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController creates free-talk session with setup phase.
4. RoleplayService calls RoleplayAIService for setup opening.
5. System returns created session response.
6. Client sends POST /api/v1/learner/roleplay/sessions/{sessionId}/messages with content about shopping.
7. RoleplayAIService generates PHASE_TRANSITION response with topic and mission summaries.
8. RoleplayService creates dynamic SessionMission from transition data.
9. System returns updated session with new mission.

**Expected Results:** HTTP 200 OK for message send
Response JSON:
- success = true
- data.missions.length() = 1 (newly created dynamic mission)

Database state:
- SessionMission created with title "Shopping" and titleVi "Mua hang"

---

### Test Case ID: TC_SP_AI_019
**Test Case Description:** Mission progress updates after send message

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Published theme, topic, and mission created
- Roleplay session started with opening messages
- MissionEvaluatorAI mocked to return completed progress

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions with topicId, Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayService creates session with opening messages and missions.
4. Client sends POST /api/v1/learner/roleplay/sessions/{sessionId}/messages with content.
5. RoleplayService calls MissionEvaluatorAI to evaluate mission progress.
6. MissionEvaluatorAI returns MissionProgress with status=COMPLETED and progressPct=100.
7. RoleplayService updates SessionMission with new progress.
8. System returns updated session response.

**Expected Results:** HTTP 200 OK
Database state:
- SessionMission.progressPct = 100
- SessionMission.status = COMPLETED

---


---

### Test Case ID: TC_SP_AI_020
**Test Case Description:** Session history pagination

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- 2 completed roleplay sessions created with different topics

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/roleplay/sessions with pagination parameters (page=0, size=1), Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService retrieves learner's session history with pagination.
5. System returns paginated session list.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.totalElements = 2 (total sessions)
- data.content.length() = 1 (page size)
- data.content contains session details

---

### Test Case ID: TC_SP_AI_022
**Test Case Description:** Get session returns messages and missions

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Active roleplay session created
- One message saved to the session
- One mission saved to the session

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/roleplay/sessions/{sessionId}, Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService retrieves session with associated messages and missions.
5. System returns session details with messages and missions.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.messages.length() = 1
- data.missions.length() = 1


---

### Test Case ID: TC_SP_AI_023
**Test Case Description:** Get session not found returns 404 NOT FOUND

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- No session exists with the provided UUID

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/roleplay/sessions/{sessionId} with random UUID, Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService attempts to find session via ConversationSessionRepository.
5. ConversationSessionRepository returns empty (session not found).
6. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND

---

### Test Case ID: TC_SP_AI_024
**Test Case Description:** Abandon non-existent session returns 404 NOT FOUND

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- No session exists with the provided UUID

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions/{sessionId}/abandon with random UUID, Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService attempts to find session via ConversationSessionRepository.
5. ConversationSessionRepository returns empty (session not found).
6. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND

---

### Test Case ID: TC_SP_AI_025
**Test Case Description:** Abandon inactive session returns 400 BAD REQUEST

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Session with status=COMPLETED exists

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions/{sessionId}/abandon with completed session ID, Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService validates session status and finds it's not ACTIVE.
5. RoleplayService throws BusinessException with SESSION_NOT_ACTIVE.
6. System returns the bad request error response.

**Expected Results:** HTTP 400 BAD REQUEST

---


---

### Test Case ID: TC_SP_AI_026
**Test Case Description:** Send message to another user's session returns 404 NOT FOUND

**Pre-conditions:**
- Owner learner has active session
- Attacker learner has valid token

**Test Case Procedure:**
1. Attacker sends POST /api/v1/learner/roleplay/sessions/{sessionId}/messages to owner's session
2. AuthFilter validates attacker token
3. RoleplayService attempts to find session for attacker
4. Session not found (belongs to owner)
5. System returns 404

**Expected Results:** HTTP 404 NOT FOUND

---

### Test Case ID: TC_SP_AI_027
**Test Case Description:** Complete another user's session returns 404 NOT FOUND

**Pre-conditions:**
- Owner learner has active session
- Attacker learner has valid token

**Test Case Procedure:**
1. Attacker sends POST /api/v1/learner/roleplay/sessions/{sessionId}/complete to owner's session
2. AuthFilter validates attacker token
3. RoleplayService attempts to find session for attacker
4. Session not found (belongs to owner)
5. System returns 404

**Expected Results:** HTTP 404 NOT FOUND

---

### Test Case ID: TC_SP_AI_028
**Test Case Description:** Abandon another user's session returns 404 NOT FOUND

**Pre-conditions:**
- Owner learner has active session
- Attacker learner has valid token

**Test Case Procedure:**
1. Attacker sends POST /api/v1/learner/roleplay/sessions/{sessionId}/abandon to owner's session
2. AuthFilter validates attacker token
3. RoleplayService attempts to find session for attacker
4. Session not found (belongs to owner)
5. System returns 404

**Expected Results:** HTTP 404 NOT FOUND

---

### Test Case ID: TC_SP_AI_029
**Test Case Description:** Get another user's session returns 404 NOT FOUND

**Pre-conditions:**
- Owner learner has active session
- Attacker learner has valid token

**Test Case Procedure:**
1. Attacker sends GET /api/v1/learner/roleplay/sessions/{sessionId} for owner's session
2. AuthFilter validates attacker token
3. RoleplayService attempts to find session for attacker
4. Session not found (belongs to owner)
5. System returns 404

**Expected Results:** HTTP 404 NOT FOUND

---

### Test Case ID: TC_SP_AI_030
**Test Case Description:** Manager start roleplay session returns 403 FORBIDDEN

**Pre-conditions:**
- Manager user with valid token
- Published topic available

**Test Case Procedure:**
1. Manager sends POST /api/v1/learner/roleplay/sessions with topicId
2. AuthFilter validates manager token
3. SecurityConfig blocks access - MANAGER cannot access LEARNER endpoints
4. System returns 403

**Expected Results:** HTTP 403 FORBIDDEN

---

### Test Case ID: TC_SP_AI_031
**Test Case Description:** Complete non-existent session returns 404 NOT FOUND

**Pre-conditions:**
- Learner with valid token
- No session exists with provided UUID

**Test Case Procedure:**
1. Learner sends POST /api/v1/learner/roleplay/sessions/{sessionId}/complete with random UUID
2. AuthFilter validates learner token
3. RoleplayService attempts to find session
4. Session not found
5. System returns 404

**Expected Results:** HTTP 404 NOT FOUND

---

### Test Case ID: TC_SP_AI_032
**Test Case Description:** Start session when topic has no missions

**Pre-conditions:**
- Learner with valid token
- Published topic with no missions

**Test Case Procedure:**
1. Learner sends POST /api/v1/learner/roleplay/sessions with topicId
2. AuthFilter validates learner token
3. RoleplayService creates session with no missions
4. RoleplayAIService generates opening messages
5. System returns created session

**Expected Results:** HTTP 201 CREATED
Response JSON:
- data.missions.length() = 0
- data.messages.length() = 1

---

### Test Case ID: TC_SP_AI_033
**Test Case Description:** Send message with hintRequest=true

**Pre-conditions:**
- Learner with active session
- RoleplayAIService mocked for hint response

**Test Case Procedure:**
1. Learner sends POST /api/v1/learner/roleplay/sessions/{sessionId}/messages with hintRequest=true
2. AuthFilter validates learner token
   3. RoleplayService calls RoleplayAIService.generateHintResponse()
4. System returns hint message

**Expected Results:** HTTP 200 OK
Response JSON:
- data.messages.length() = 1
- data.messages[0].messageType = "HINT"

---


---

### Test Case ID: TC_SP_CT_034
**Test Case Description:** Free-talk session limit reached returns 400 BAD REQUEST

**Pre-conditions:**
- Learner with valid token
- 3 active sessions already exist (maximum limit)

**Test Case Procedure:**
1. Learner sends POST /api/v1/learner/roleplay/free-talk/sessions with jlptLevel and language
2. AuthFilter validates learner token
3. RoleplayService counts learner's active sessions
4. RoleplayService finds 3 active sessions (limit reached)
5. RoleplayService throws BusinessException with ACTIVE_SESSION_LIMIT
6. System returns 400

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_SP_AI_039
**Test Case Description:** Message ordering preserved

**Pre-conditions:**
- Learner with active session
- Messages saved with out-of-order sequence numbers in database

**Test Case Procedure:**
1. Create messages with sequence numbers 3, 1, 2 (out of order)
2. Learner sends GET /api/v1/learner/roleplay/sessions/{sessionId}
3. AuthFilter validates learner token
4. RoleplayService retrieves session and messages
5. Messages are ordered by sequenceNumber ascending
6. System returns ordered message list

**Expected Results:** HTTP 200 OK
Response JSON:
- data.messages[0].content = "msg1"
- data.messages[1].content = "msg2"
- data.messages[2].content = "msg3"

---

### Test Case ID: TC_SP_AI_043
**Test Case Description:** Start roleplay session without token returns 401 UNAUTHORIZED

**Pre-conditions:**
- Published topic available
- No authorization token provided

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions with topicId
2. No Authorization header provided
3. AuthFilter rejects request
4. System returns 401

**Expected Results:** HTTP 401 UNAUTHORIZED

---

### Test Case ID: TC_SP_AI_044
**Test Case Description:** Session list returns 200 with empty array

**Pre-conditions:**
- Learner with valid token
- No sessions exist for learner

**Test Case Procedure:**
1. Learner sends GET /api/v1/learner/roleplay/sessions
2. AuthFilter validates learner token
3. RoleplayService retrieves learner's session history
4. No sessions found
5. System returns empty session list

**Expected Results:** HTTP 200 OK
Response JSON:
- data.content.length() = 0

---

### Test Case ID: TC_SP_AI_045
**Test Case Description:** Send message with empty audio file returns 400 BAD REQUEST

**Pre-conditions:**
- Learner with active session
- Audio file with 0 bytes

**Test Case Procedure:**
1. Learner starts roleplay session
2. Learner sends POST /api/v1/learner/roleplay/sessions/{sessionId}/messages with message and empty audio file (0 bytes)
3. AuthFilter validates learner token
4. RoleplayController validates audio file size
5. RoleplayController rejects empty audio file
6. System returns 400

**Expected Results:** HTTP 400 BAD REQUEST

---


## Test Data Cleanup

### After Each Test Case:
- Clear all conversation themes, topics, missions, sessions, and messages
- Clear user sessions and user data
- Reset AI service mocks
- Verify no orphaned records remain

### Test Execution Order:
Tests can be executed in any order as they use isolated test data and clean up after each execution.

### Mock Service Behavior:
- RoleplayAIService: Mocked to return predictable prompts and messages
- MissionEvaluatorAI: Mocked to return empty progress lists
- SessionEvaluatorAI: Mocked to return detailed evaluation scores
- PronunciationAssessmentProvider: Mocked to return unavailable for most tests

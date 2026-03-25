# State Machine Tests Script

## Overview
This document contains detailed test scripts for all state machine API endpoints in Speaking Practice Integration Test for Satori Learning Support Application backend.

## Test Environment Setup

### Pre-conditions
- Test DB running via `application-test.properties`
- PostgreSQL `satori_test` database created and initialized
- Docker environment up and running
- AI services mocked to prevent actual HTTP calls
- Pronunciation assessment services mocked

---

## Section 5 — State Machine Tests

### Test Case ID: TC_SP_AI_SM_001
**Test Case Description:** Start session: NONE → ACTIVE

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- ConversationTheme and ConversationTopic created with PUBLISHED status
- Mission created for the topic
- AI services mocked:
  - roleplayAIService.buildSystemPrompt() returns "prompt"
  - roleplayAIService.generateOpeningMessage() returns "[]"

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions with Authorization: Bearer {token} and JSON body:
   - topicId: UUID of the created topic
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService creates new ConversationSession with ACTIVE status.
5. RoleplayService calls AI services to generate system prompt and opening message.
6. ConversationRepository saves the new session.
7. System returns the created session response.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.status = "ACTIVE"

---

### Test Case ID: TC_SP_AI_SM_002
**Test Case Description:** Send message in ACTIVE session

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- ConversationSession created with ACTIVE status
- AI services mocked:
  - roleplayAIService.getCachedSystemPrompt() returns "prompt"
  - roleplayAIService.generateResponse() returns "[]"
  - missionEvaluatorAI.evaluateProgress() returns empty list
  - pronunciationAssessmentProvider.isAvailable() returns false

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions/{id}/messages with Authorization: Bearer {token} and multipart data:
   - message: JSON with content="Hello", hintRequest=false
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService validates session is in ACTIVE status.
5. RoleplayService calls AI services to generate response.
6. RoleplayService saves user message and AI response.
7. System returns success response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.status = "ACTIVE"

---

### Test Case ID: TC_SP_AI_SM_004
**Test Case Description:** Abandon session: ACTIVE → ABANDONED

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- ConversationSession created with ACTIVE status
- SessionMission entities created:
  - Mission 1 with IN_PROGRESS status, 50% progress
  - Mission 2 with COMPLETED status, 100% progress

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions/{sessionId}/abandon with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService validates session is in ACTIVE status.
5. RoleplayService updates session status to ABANDONED.
6. RoleplayService updates mission statuses:
   - IN_PROGRESS missions become FAILED
   - COMPLETED missions remain COMPLETED
7. ConversationRepository saves the updated session.
8. SessionMissionRepository saves updated missions.
9. System returns success response.

**Expected Results:** HTTP 200 OK
Database state:
- Session status = "ABANDONED"
- Mission 1 status = "FAILED"
- Mission 2 status = "COMPLETED"

---

### Test Case ID: TC_SP_AI_SM_005
**Test Case Description:** COMPLETED → send message returns 400

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- ConversationSession created with COMPLETED status

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions/{id}/messages with Authorization: Bearer {token} and multipart data:
   - message: JSON with content="Hello", hintRequest=false
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService validates session status and detects COMPLETED status.
5. System throws BusinessException for invalid state transition.
6. System returns the bad request error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_SP_AI_SM_006
**Test Case Description:** COMPLETED → complete again returns 400

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- ConversationSession created with COMPLETED status

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions/{id}/complete with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService validates session status and detects COMPLETED status.
5. System throws BusinessException for invalid state transition.
6. System returns the bad request error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_SP_AI_SM_007
**Test Case Description:** COMPLETED → abandon returns 400

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- ConversationSession created with COMPLETED status

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions/{id}/abandon with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService validates session status and detects COMPLETED status.
5. System throws BusinessException for invalid state transition.
6. System returns the bad request error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_SP_AI_SM_008
**Test Case Description:** ABANDONED → send message returns 400

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- ConversationSession created with ABANDONED status

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions/{id}/messages with Authorization: Bearer {token} and multipart data:
   - message: JSON with content="Hello", hintRequest=false
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService validates session status and detects ABANDONED status.
5. System throws BusinessException for invalid state transition.
6. System returns the bad request error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_SP_AI_SM_009
**Test Case Description:** ABANDONED → complete returns 400

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- ConversationSession created with ABANDONED status

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions/{id}/complete with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService validates session status and detects ABANDONED status.
5. System throws BusinessException for invalid state transition.
6. System returns the bad request error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_SP_AI_SM_010
**Test Case Description:** ABANDONED → abandon again returns 400

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- ConversationSession created with ABANDONED status

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions/{id}/abandon with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService validates session status and detects ABANDONED status.
5. System throws BusinessException for invalid state transition.
6. System returns the bad request error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_SP_AI_SM_011
**Test Case Description:** Free-talk session: ACTIVE → COMPLETED

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- AI services mocked:
  - roleplayAIService.buildSetupSystemPrompt() returns "setup"
  - roleplayAIService.generateSetupOpening() returns dialogue array
  - roleplayAIService.getCachedSystemPrompt() returns "setup"
  - roleplayAIService.generateResponse() returns phase transition + dialogue
  - sessionEvaluatorAI.evaluateSession() returns evaluation details

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/free-talk/sessions with Authorization: Bearer {token} and JSON body:
   - jlptLevel: "N5", language: "vi"
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController creates FREE_TALK session with ACTIVE status.
4. Client sends POST /api/v1/learner/roleplay/sessions/{sessionId}/messages with message content.
5. RoleplayService processes message and generates AI response.
6. Client sends POST /api/v1/learner/roleplay/sessions/{id}/complete.
7. RoleplayService evaluates session and updates status to COMPLETED.
8. System returns completion response with session details.

**Expected Results:** 
- Step 1: HTTP 201 CREATED (session created)
- Step 2: HTTP 200 OK (message sent)
- Step 3: HTTP 200 OK (session completed)
Response JSON contains sessionId and evaluation details

---

### Test Case ID: TC_SP_AI_SM_012
**Test Case Description:** Free-talk session: ACTIVE → ABANDONED

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- ConversationSession created with ACTIVE status and FREE_TALK type

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions/{id}/abandon with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService validates session is ACTIVE with FREE_TALK type.
5. RoleplayService updates session status to ABANDONED.
6. ConversationRepository saves the updated session.
7. System returns success response.

**Expected Results:** HTTP 200 OK
Database state:
- Session status = "ABANDONED"

---

### Test Case ID: TC_SP_AI_SM_013
**Test Case Description:** Free-talk COMPLETED → send message returns 400

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- ConversationSession created with COMPLETED status and FREE_TALK type

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions/{id}/messages with Authorization: Bearer {token} and multipart data:
   - message: JSON with content="Hello", hintRequest=false
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService validates session status and detects COMPLETED status.
5. System throws BusinessException for invalid state transition.
6. System returns the bad request error response.

**Expected Results:** HTTP 400 BAD REQUEST

---

### Test Case ID: TC_SP_AI_SM_014
**Test Case Description:** Message limit reached returns 400, session not auto-completed

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- ConversationSession created with ACTIVE status
- 50 ConversationMessage entities created for the session (reaching limit)

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions/{id}/messages with Authorization: Bearer {token} and multipart data:
   - message: JSON with content="overflow", hintRequest=false
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService validates message count and detects limit reached.
5. System throws BusinessException for message limit exceeded.
6. System returns the bad request error response.
7. Session status remains unchanged (not auto-completed).

**Expected Results:** HTTP 400 BAD REQUEST
Database state:
- Session status remains "ACTIVE"

---

### Test Case ID: TC_SP_AI_SM_015
**Test Case Description:** Mission status locked if already COMPLETED or FAILED

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- ConversationSession created with ACTIVE status
- SessionMission entities created:
  - Mission 1 with FAILED status, 50% progress
  - Mission 2 with COMPLETED status, 100% progress
- AI services mocked:
  - roleplayAIService.getCachedSystemPrompt() returns "prompt"
  - roleplayAIService.generateResponse() returns "[]"
  - missionEvaluatorAI.evaluateProgress() returns COMPLETED status for both missions
  - pronunciationAssessmentProvider.isAvailable() returns false

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions/{id}/messages with Authorization: Bearer {token} and multipart data:
   - message: JSON with content="Hello", hintRequest=false
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService processes message and calls mission evaluator.
5. RoleplayService attempts to update mission statuses.
6. RoleplayService detects missions are already in terminal states (COMPLETED/FAILED).
7. RoleplayService skips status updates for locked missions.
8. System returns success response.

**Expected Results:** HTTP 200 OK
Database state:
- Mission 1 status remains "FAILED"
- Mission 2 status remains "COMPLETED"
- Mission status locking mechanism works correctly

---

## State Transition Summary

### Valid Transitions:
- **NONE → ACTIVE**: Starting a new session (TC_SP_AI_SM_001)
- **ACTIVE → COMPLETED**: Normal session completion (TC_SP_AI_SM_011)
- **ACTIVE → ABANDONED**: User abandons session (TC_SP_AI_SM_004, TC_SP_AI_SM_012)

### Invalid Transitions (return 400):
- **COMPLETED → ACTIVE**: Cannot send messages (TC_SP_AI_SM_005, TC_SP_AI_SM_013)
- **COMPLETED → COMPLETED**: Cannot complete again (TC_SP_AI_SM_006)
- **COMPLETED → ABANDONED**: Cannot abandon completed session (TC_SP_AI_SM_007)
- **ABANDONED → ACTIVE**: Cannot send messages (TC_SP_AI_SM_008)
- **ABANDONED → COMPLETED**: Cannot complete abandoned session (TC_SP_AI_SM_009)
- **ABANDONED → ABANDONED**: Cannot abandon again (TC_SP_AI_SM_010)

### Business Rules:
- Message limit is 50 messages per session (TC_SP_AI_SM_014)
- Mission status is locked when COMPLETED or FAILED (TC_SP_AI_SM_015)
- Free-talk sessions follow the same state machine rules (TC_SP_AI_SM_011, TC_SP_AI_SM_012, TC_SP_AI_SM_013)
- Session abandonment affects mission statuses (IN_PROGRESS → FAILED)

## Test Execution Commands

```bash
# Run all State Machine Tests
mvn test -Dtest=SpeakingPracticeIntegrationTest#StateMachineTests

# Run specific test case
mvn test -Dtest=SpeakingPracticeIntegrationTest#tc_sp_ai_sm_001

# Run with detailed output
mvn test -Dtest=SpeakingPracticeIntegrationTest#StateMachineTests -Dmaven.test.failure.ignore=true
```

## Dependencies and Mocks Required

### AI Services to Mock:
- `roleplayAIService.buildSystemPrompt()`
- `roleplayAIService.generateOpeningMessage()`
- `roleplayAIService.getCachedSystemPrompt()`
- `roleplayAIService.generateResponse()`
- `roleplayAIService.buildSetupSystemPrompt()`
- `roleplayAIService.generateSetupOpening()`
- `missionEvaluatorAI.evaluateProgress()`
- `sessionEvaluatorAI.evaluateSession()`
- `pronunciationAssessmentProvider.isAvailable()`

### Test Data Setup:
- Learner user with authentication
- Conversation themes and topics
- Conversation sessions with various statuses
- Session missions with different statuses
- Conversation messages

## Notes
- All tests use Spring MockMvc for HTTP layer testing
- Database operations use real repositories with test data
- AI services are mocked to avoid external dependencies
- Tests verify both HTTP responses and database state changes
- State machine validation prevents invalid transitions
- Business rules are enforced at service layer

---

## Section 6 — AI Robustness Tests

### Test Case ID: TC_SP_AI_037
**Test Case Description:** AI timeout on message generation → 200 with fallback reply

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- ConversationSession created with ACTIVE status
- AI services mocked:
  - roleplayAIService.getCachedSystemPrompt() returns "prompt"
  - roleplayAIService.generateResponse() throws RuntimeException("AI Service Timeout")
  - missionEvaluatorAI.evaluateProgress() returns empty list
  - pronunciationAssessmentProvider.isAvailable() returns false

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions/{sessionId}/messages with Authorization: Bearer {token} and multipart data:
   - message: JSON with content="Hello", hintRequest=false
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService validates session is in ACTIVE status.
5. RoleplayService calls AI generateResponse() which throws timeout exception.
6. Exception is caught and fallback message is returned.
7. RoleplayService saves user message and fallback AI message.
8. System returns success response with fallback message.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.messages[0].japaneseContent matches one of fallback messages:
  - "すみません、もう一度お願いします。"
  - "ごめんなさい、よく聞こえませんでした。もう一度言ってください。"
  - "少し待ってください。もう一度話してもらえますか？"
Database state:
- Session status remains "ACTIVE"
- Message count = 2 (user + fallback AI)

---

### Test Case ID: TC_SP_ROB_003
**Test Case Description:** OpenAI HTTP 500 on message generation → 200 with fallback reply

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- ConversationSession created with ACTIVE status
- AI services mocked:
  - roleplayAIService.getCachedSystemPrompt() returns "prompt"
  - roleplayAIService.generateResponse() throws RuntimeException("OpenAI 500 Internal Server Error")
  - missionEvaluatorAI.evaluateProgress() returns empty list
  - pronunciationAssessmentProvider.isAvailable() returns false

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions/{sessionId}/messages with Authorization: Bearer {token} and multipart data:
   - message: JSON with content="Hello", hintRequest=false
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService validates session is in ACTIVE status.
5. RoleplayService calls AI generateResponse() which throws HTTP 500 exception.
6. Exception is caught and fallback message is returned.
7. RoleplayService saves user message and fallback AI message.
8. System returns success response with fallback message.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.messages[0].japaneseContent matches one of fallback messages
Database state:
- Session status remains "ACTIVE"
- Message count = 2 (user + fallback AI)

---

### Test Case ID: TC_SP_AI_042
**Test Case Description:** AI returns empty response body → 200 with raw fallback message

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- ConversationSession created with ACTIVE status
- AI services mocked:
  - roleplayAIService.getCachedSystemPrompt() returns "prompt"
  - roleplayAIService.generateResponse() returns empty string ""
  - missionEvaluatorAI.evaluateProgress() returns empty list
  - pronunciationAssessmentProvider.isAvailable() returns false

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions/{sessionId}/messages with Authorization: Bearer {token} and multipart data:
   - message: JSON with content="Hello", hintRequest=false
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService validates session is in ACTIVE status.
5. RoleplayService calls AI generateResponse() which returns empty string.
6. Empty string cannot be parsed, fallback to default response.
7. RoleplayService saves user message and AI message.
8. System returns success response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data contains AI response messages
Database state:
- Session status remains "ACTIVE"
- Message count = 2 (user + AI)

---

### Test Case ID: TC_SP_ROB_002
**Test Case Description:** AI fails during session start → 201 with fallback opening messages

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- ConversationTheme and ConversationTopic created with PUBLISHED status
- AI services mocked:
  - roleplayAIService.buildSystemPrompt() returns "prompt"
  - roleplayAIService.generateOpeningMessage() returns fallback JSON with error message

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions with Authorization: Bearer {token} and JSON body:
   - topicId: UUID of the created topic
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService creates new ConversationSession with ACTIVE status.
5. RoleplayService calls AI generateOpeningMessage() which returns fallback error message.
6. ConversationRepository saves the new session with fallback opening messages.
7. System returns created session response.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.messages[0].japaneseContent = "申し訳ありません、エラーが発生しました。"
Database state:
- Session count = 1
- Message count = 1 (fallback opening message)

---

### Test Case ID: TC_SP_ROB_004
**Test Case Description:** Evaluation service fails and returns fallback → 200 OK, session COMPLETED

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- ConversationSession created with ACTIVE status
- At least 1 USER message exists in the session
- AI services mocked:
  - sessionEvaluatorAI.evaluateSession() returns fallback evaluation with zero scores

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions/{sessionId}/complete with Authorization: Bearer {token}
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService validates session has at least 1 user message.
5. RoleplayService calls sessionEvaluatorAI.evaluateSession() which returns fallback evaluation.
6. RoleplayService updates session status to COMPLETED.
7. System returns success response with evaluation data.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.languageEvaluation.summary = "Đánh giá không khả dụng cho phiên hội thoại này."
Database state:
- Session status = "COMPLETED"

---

### Test Case ID: TC_SP_ROB_005
**Test Case Description:** Pronunciation provider fails → 200 OK, message still saved

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- ConversationSession created with ACTIVE status
- AI services mocked:
  - roleplayAIService.getCachedSystemPrompt() returns "prompt"
  - roleplayAIService.generateResponse() returns AI reply JSON
  - missionEvaluatorAI.evaluateProgress() returns empty list
  - pronunciationAssessmentProvider.isAvailable() returns true
  - pronunciationAssessmentProvider.assess() returns AssessmentResult.failure

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions/{sessionId}/messages with Authorization: Bearer {token} and multipart data:
   - message: JSON with content="Hello", hintRequest=false
   - audio: audio file data
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService validates session is in ACTIVE status.
5. RoleplayService calls AI services to generate response.
6. RoleplayService saves user message and AI response.
7. Pronunciation assessment runs asynchronously and fails.
8. System returns success response with AI reply.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.messages.length() = 1
Database state:
- Session status remains "ACTIVE"
- Message count = 2 (user + AI)
- Pronunciation count = 0 (assessment failed)

---

### Test Case ID: TC_SP_ROB_006_007
**Test Case Description:** Azure STT Timeout và SpeechSuper cũng lỗi/không khả dụng

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- ConversationSession created with ACTIVE status
- AI services mocked:
  - roleplayAIService.getCachedSystemPrompt() returns "prompt"
  - roleplayAIService.generateResponse() returns AI reply JSON
  - missionEvaluatorAI.evaluateProgress() returns empty list
  - pronunciationAssessmentProvider.isAvailable() returns true
  - pronunciationAssessmentProvider.assess() returns AssessmentResult.failure with timeout error

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions/{sessionId}/messages with Authorization: Bearer {token} and multipart data:
   - message: JSON with content="Hello", hintRequest=false
   - audio: audio file data
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService validates session is in ACTIVE status.
5. RoleplayService calls AI services to generate response.
6. RoleplayService saves user message and AI response.
7. Pronunciation assessment runs asynchronously and both providers fail.
8. System returns success response with AI reply.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.messages.length() = 1
Database state:
- Session status remains "ACTIVE"
- Message count = 2 (user + AI)
- Pronunciation count = 0 (both providers failed)

---

### Test Case ID: TC_SP_ROB_008
**Test Case Description:** Azure STT trả về transcript rỗng → 400 CONTENT_REQUIRED

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- ConversationSession created with ACTIVE status
- AI services mocked:
  - pronunciationAssessmentProvider.isAvailable() returns true
  - pronunciationAssessmentProvider.assess() returns AssessmentResult with empty transcribedText

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions/{sessionId}/messages with Authorization: Bearer {token} and multipart data:
   - message: JSON with content="", hintRequest=false
   - audio: audio file data
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService validates content is not blank for non-hint requests.
5. BusinessException("CONTENT_REQUIRED") is thrown.
6. System returns bad request response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- error message about content required

---

### Test Case ID: TC_SP_ROB_009_010_011
**Test Case Description:** Azure STT succeeds but SpeechSuper returns null score → 200 OK

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- ConversationSession created with ACTIVE status
- AI services mocked:
  - roleplayAIService.getCachedSystemPrompt() returns "prompt"
  - roleplayAIService.generateResponse() returns AI reply JSON
  - missionEvaluatorAI.evaluateProgress() returns empty list
  - pronunciationAssessmentProvider.isAvailable() returns true
  - pronunciationAssessmentProvider.assess() returns AssessmentResult with transcript but null scores

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/roleplay/sessions/{sessionId}/messages with Authorization: Bearer {token} and multipart data:
   - message: JSON with content="Hello", hintRequest=false
   - audio: audio file data
2. AuthFilter validates token and resolves LEARNER identity.
3. RoleplayController forwards the request to RoleplayService.
4. RoleplayService validates session is in ACTIVE status.
5. RoleplayService calls AI services to generate response.
6. RoleplayService saves user message and AI response.
7. Pronunciation assessment runs asynchronously with partial results.
8. System returns success response with AI reply.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data contains AI response messages
Database state:
- Session status remains "ACTIVE"
- Message count = 2 (user + AI)

---

## Section 7 — Concurrency Tests

### Test Case ID: TC_SP_AI_035
**Test Case Description:** Parallel send message to same session

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- ConversationSession created with ACTIVE status
- AI services mocked:
  - roleplayAIService.getCachedSystemPrompt() returns "prompt"
  - roleplayAIService.generateResponse() returns AI reply JSON
  - pronunciationAssessmentProvider.isAvailable() returns false
- Test setup with ExecutorService and CountDownLatch for parallel execution

**Test Case Procedure:**
1. Test creates 2 parallel threads using ExecutorService.
2. Both threads send POST /api/v1/learner/roleplay/sessions/{id}/messages with Authorization: Bearer {token} and multipart data:
   - message: JSON with content="Hello", hintRequest=false
3. CountDownLatch ensures both threads start simultaneously.
4. AuthFilter validates token and resolves LEARNER identity for both requests.
5. RoleplayController forwards both requests to RoleplayService.
6. RoleplayService uses pessimistic locking to ensure sequence order.
7. Both requests save user message and AI response.
8. System returns success response for both requests.

**Expected Results:** HTTP 200 OK for both requests
Response JSON:
- success = true for both requests
- data contains AI response messages for both
Database state:
- Session status remains "ACTIVE"
- Message count = 4 (2 user + 2 AI messages)
- No deadlock or database corruption

---

### Test Case ID: TC_SP_AI_041
**Test Case Description:** Parallel start session (Enforce limit under load)

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- User already has 2 ACTIVE sessions (limit is 3)
- ConversationTheme and ConversationTopic created with PUBLISHED status
- AI services mocked:
  - roleplayAIService.buildSystemPrompt() returns "prompt"
  - roleplayAIService.generateOpeningMessage() returns "[]"
- Test setup with ExecutorService and CountDownLatch for parallel execution

**Test Case Procedure:**
1. Test creates 2 parallel threads using ExecutorService.
2. Both threads send POST /api/v1/learner/roleplay/sessions with Authorization: Bearer {token} and JSON body:
   - topicId: UUID of the created topic
3. CountDownLatch ensures both threads start simultaneously.
4. AuthFilter validates token and resolves LEARNER identity for both requests.
5. RoleplayController forwards both requests to RoleplayService.
6. Due to PostgreSQL Read-Committed isolation, both threads read uncommitted count.
7. Both requests succeed and create sessions.
8. System returns created response for both requests.

**Expected Results:** HTTP 201 CREATED for both requests
Response JSON:
- success = true for both requests
- data.status = "ACTIVE" for both sessions
Database state:
- Session count = 4 (2 existing + 2 new)
- No limit enforcement due to race condition

---

### Test Case ID: TC_SP_AI_036
**Test Case Description:** Chặn Double Complete hoặc Abandon đồng thời

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- ConversationSession created with ACTIVE status
- At least 1 USER message exists in the session
- AI services mocked:
  - sessionEvaluatorAI.evaluateSession() returns evaluation scores
- Test setup with ExecutorService and CountDownLatch for parallel execution

**Test Case Procedure:**
1. Test creates 2 parallel threads using ExecutorService.
2. Both threads send POST /api/v1/learner/roleplay/sessions/{id}/complete with Authorization: Bearer {token}
3. CountDownLatch ensures both threads start simultaneously.
4. AuthFilter validates token and resolves LEARNER identity for both requests.
5. RoleplayController forwards both requests to RoleplayService.
6. Database locking ensures only one request can complete the session.
7. One request succeeds, the other is rejected with 400 or 409.
8. SessionEvaluatorAI is called only once.

**Expected Results:** One HTTP 200 OK, one HTTP 400/409
Response JSON:
- success = true for successful request
- success = false for rejected request
Database state:
- Session status = "COMPLETED"
- SessionEvaluatorAI called exactly once

---

### Test Case ID: TC_SP_CON_001
**Test Case Description:** Tránh Generate Context đè nhau trên cùng 1 Topic

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- UserConversationTopic created with DRAFT status
- AI services mocked:
  - conversationGenerationService.generateUserTopicContext() returns hints JSON
- Test setup with ExecutorService and CountDownLatch for parallel execution

**Test Case Procedure:**
1. Test creates 2 parallel threads using ExecutorService.
2. Both threads send POST /api/v1/learner/conversation/my-topics/{id}/generate-context with Authorization: Bearer {token}
3. CountDownLatch ensures both threads start simultaneously.
4. AuthFilter validates token and resolves LEARNER identity for both requests.
5. ConversationController forwards both requests to ConversationService.
6. generateContext() has no locking, both calls succeed.
7. Both calls overwrite aiGeneratedContext sequentially.
8. System returns success response for both requests.

**Expected Results:** HTTP 200 OK for both requests
Response JSON:
- success = true for both requests
- data contains generated context
Database state:
- aiGeneratedContext overwritten twice
- conversationGenerationService.generateUserTopicContext() called twice
- No distributed lock to prevent race condition

# Curriculum Chat Integration Test Script

## Overview
This document contains detailed test scripts for all curriculum chat API endpoints in Satori Learning Support Application backend.

## Test Environment Setup

### Pre-conditions
- Test DB running via `application-test.properties`
- PostgreSQL `satori_test` database created and initialized
- Docker environment up and running
- EmailService mocked to prevent actual email sending
- EmbeddingService mocked to control RAG context retrieval
- ChatRateLimiter mocked to prevent rate limit blocking
- OpenAiChatModel mocked to provide deterministic AI responses

---

## Section 1 — Create Chat Session Tests (POST /api/v1/learner/curriculum-chat/sessions)

### Test Case ID: TC_CC_CS_001
**Test Case Description:** Create chat session successfully with JWT

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Course exists in database (status=ACTIVE)
- Lesson exists in database (courseId matches the course)
- Class exists in database (courseId matches the course)
- Learner is enrolled as ClassMember
- OpenAiChatModel mocked to return deterministic response

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/curriculum-chat/sessions with Authorization: Bearer {learnerToken} and JSON body:
   - courseId: {courseId}
   - lessonId: {lessonId}
   - jlptLevel: "N1"
   - title: "Test Chat Session"
2. AuthFilter validates token and resolves LEARNER user identity.
3. ChatSessionController calls ChatSessionService.createChatSession().
4. ChatSessionService creates new ChatSession entity with sessionType="CURRICULUM_QA", status="ACTIVE".
5. ChatSessionService saves ChatSession via ChatSessionRepository.save().
6. ChatSessionRepository persists the new session to database.
7. System returns the created chat session response.

**Expected Results:** HTTP 201 CREATED
Response JSON:
- success = true
- data.sessionId = non-empty UUID
Database state: ChatSession created with userId = learner.id, status = "ACTIVE", sessionType = "CURRICULUM_QA"

---

## Section 2 — Send Message to Chat Tests (POST /api/v1/learner/curriculum-chat/sessions/{sessionId}/messages)

### Test Case ID: TC_CC_SM_001
**Test Case Description:** Send message and receive AI response successfully

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- ChatSession exists in database (status="ACTIVE", userId=learner.id)
- OpenAiChatModel mocked to return "This is a deterministic AI mock response."
- EmbeddingService mocked to return RagContext.empty()
- ChatRateLimiter mocked to allow the request

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/curriculum-chat/sessions/{sessionId}/messages with Authorization: Bearer {learnerToken} and JSON body:
   - message: "この文法は何ですか？"
2. AuthFilter validates token and resolves LEARNER user identity.
3. ChatRateLimiter.checkRateLimit() is called and does not throw exception.
4. ChatService retrieves ChatSession from ChatSessionRepository.findById().
5. ChatService validates user ownership of session.
6. EmbeddingService.retrieveContext() is called to fetch RAG context (returns empty).
7. OpenAiChatModel.call() is invoked with the user message and context.
8. ChatModel returns the mocked response "This is a deterministic AI mock response."
9. ChatService saves user message as ChatMessage with role="USER", sequenceNumber=1.
10. ChatService saves assistant message as ChatMessage with role="ASSISTANT", sequenceNumber=2.
11. ChatService updates ChatSession with messageCount=2, userMessageCount=1.
12. System returns the response with assistant's message.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content = "This is a deterministic AI mock response."
Database state: 
- ChatSession.messageCount = 2
- ChatSession.userMessageCount = 1
- ChatMessage count = 2 (1 USER with "この文法は何ですか？", 1 ASSISTANT with response)
- User message has sequenceNumber = 1, role = "USER"
- Assistant message has sequenceNumber = 2, role = "ASSISTANT"

---

### Test Case ID: TC_CC_SM_002
**Test Case Description:** AI service timeout returns error code MSG-158

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- ChatSession exists in database (status="ACTIVE", userId=learner.id)
- OpenAiChatModel mocked to throw RuntimeException("OpenAI API Timeout Error")

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/curriculum-chat/sessions/{sessionId}/messages with Authorization: Bearer {learnerToken} and JSON body:
   - message: "Test message"
2. AuthFilter validates token and resolves LEARNER user identity.
3. ChatRateLimiter.checkRateLimit() is called and does not throw exception.
4. ChatService retrieves ChatSession from ChatSessionRepository.
5. ChatService validates user ownership of session.
6. EmbeddingService.retrieveContext() is called and returns successfully.
7. OpenAiChatModel.call() throws RuntimeException("OpenAI API Timeout Error").
8. ChatService catches exception and does not save any message.
9. ChatSession state remains unchanged (messageCount=0).
10. System returns error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "CHAT_ERROR"
- message = "Hệ thống AI đang gặp sự cố, vui lòng thử lại sau."
Database state:
- No ChatMessage created
- ChatSession.messageCount = 0 (unchanged)

---

### Test Case ID: TC_CC_SM_003
**Test Case Description:** Knowledge base unavailable returns error code MSG-159

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- ChatSession exists in database (status="ACTIVE", userId=learner.id)
- EmbeddingService mocked to throw RuntimeException("Pinecone Error")

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/curriculum-chat/sessions/{sessionId}/messages with Authorization: Bearer {learnerToken} and JSON body:
   - message: "Test message"
2. AuthFilter validates token and resolves LEARNER user identity.
3. ChatRateLimiter.checkRateLimit() is called and does not throw exception.
4. ChatService retrieves ChatSession from ChatSessionRepository.
5. ChatService validates user ownership of session.
6. EmbeddingService.retrieveContext() throws RuntimeException("Pinecone Error").
7. ChatService catches exception and does not save any message.
8. ChatSession state remains unchanged (messageCount=0).
9. System returns error response.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
- code = "CHAT_ERROR"
- message = "Hệ thống AI đang gặp sự cố, vui lòng thử lại sau."
Database state:
- No ChatMessage created
- ChatSession.messageCount = 0 (unchanged)

---

### Test Case ID: TC_CC_SM_004
**Test Case Description:** Reject empty message

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- ChatSession exists in database (status="ACTIVE", userId=learner.id)

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/curriculum-chat/sessions/{sessionId}/messages with Authorization: Bearer {learnerToken} and JSON body:
   - message: ""
2. Request validation fails at controller level due to empty message.
3. System returns the validation error response before reaching ChatService.

**Expected Results:** HTTP 400 BAD REQUEST
Response JSON:
- success = false
Database state:
- No ChatMessage created
- ChatSession.messageCount = 0 (unchanged)

---

## Section 3 — Get Chat History Tests (GET /api/v1/learner/curriculum-chat/sessions/{sessionId}/messages)

### Test Case ID: TC_CC_GH_001
**Test Case Description:** Retrieve chat history with pagination successfully

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- ChatSession exists in database (status="ACTIVE", userId=learner.id)
- Two ChatMessage entities exist in the session:
  - Message 1: role="USER", content="これは何ですか？", sequenceNumber=1
  - Message 2: role="ASSISTANT", content="これは日本語です。", sequenceNumber=2

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/curriculum-chat/sessions/{sessionId}/messages with Authorization: Bearer {learnerToken} and query parameters:
   - page: 0
   - size: 50
2. AuthFilter validates token and resolves LEARNER user identity.
3. ChatService retrieves ChatSession from ChatSessionRepository.findById().
4. ChatService validates user ownership of session.
5. ChatService retrieves messages via ChatMessageRepository with pagination (page=0, size=50).
6. ChatMessageRepository returns sorted list of 2 messages ordered by createdAt ascending.
7. System returns the chat history response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.length() = 2
- data[0].content = "これは何ですか？"
- data[1].content = "これは日本語です。"
Database state: Both messages persist with correct role and content

---

## Section 4 — List Chat Sessions Tests (GET /api/v1/learner/curriculum-chat/sessions)

### Test Case ID: TC_CC_LS_001
**Test Case Description:** List all sessions for learner with pagination successfully

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Two ChatSession entities exist for learner:
  - Session 1: messageCount=1, userMessageCount=0
  - Session 2: messageCount=1, userMessageCount=0

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/curriculum-chat/sessions with Authorization: Bearer {learnerToken} and query parameters:
   - page: 0
   - size: 10
2. AuthFilter validates token and resolves LEARNER user identity.
3. ChatSessionService retrieves ChatSessions from ChatSessionRepository with pagination.
4. ChatSessionRepository returns paginated list of 2 sessions.
5. System returns the sessions list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content.length() = 2
Database state: Both sessions returned with correct properties

---

### Test Case ID: TC_CC_LS_002
**Test Case Description:** List sessions ordered by updated_at descending

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Three ChatSession entities exist for learner with 50ms delays between creation:
  - Session 1: created first (oldest)
  - Session 2: created 50ms after Session 1
  - Session 3: created 50ms after Session 2 (newest)

**Test Case Procedure:**
1. Create Session 1 with messageCount=1, userMessageCount=1.
2. Wait 50ms.
3. Create Session 2 with messageCount=1, userMessageCount=1.
4. Wait 50ms.
5. Create Session 3 with messageCount=1, userMessageCount=1.
6. Client sends GET /api/v1/learner/curriculum-chat/sessions with Authorization: Bearer {learnerToken} and query parameters:
   - page: 0
   - size: 10
7. AuthFilter validates token and resolves LEARNER user identity.
8. ChatSessionService retrieves ChatSessions from ChatSessionRepository ordered by updated_at DESC.
9. ChatSessionRepository returns list ordered: [Session 3, Session 2, Session 1].
10. System returns the sessions list response in descending order.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content.length() = 3
- Session order in response: Session 3.id, Session 2.id, Session 1.id (descending by updated_at)
Database state: All three sessions persist in correct order

---

## Section 5 — RBAC and Authentication Tests

### Test Case ID: TC_CC_RBAC_001
**Test Case Description:** Reject request without JWT token (authentication required)

**Pre-conditions:**
- No authentication token provided

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/curriculum-chat/sessions without Authorization header.
2. AuthFilter detects missing token and returns authentication error.
3. System returns the unauthorized error response.

**Expected Results:** HTTP 401 UNAUTHORIZED

---

### Test Case ID: TC_CC_RBAC_002
**Test Case Description:** Reject request from non-LEARNER role (TEACHER cannot access)

**Pre-conditions:**
- Teacher accessToken is available (from prior login with role=TEACHER)

**Test Case Procedure:**
1. Save Teacher user with role=TEACHER, status=ACTIVE.
2. Get teacher accessToken via login.
3. Client sends GET /api/v1/learner/curriculum-chat/sessions with Authorization: Bearer {teacherToken}.
4. AuthFilter validates token and resolves TEACHER user identity.
5. SecurityConfig denies access due to insufficient permissions (RBAC).
6. System returns the access denied error response.

**Expected Results:** HTTP 403 FORBIDDEN

---

## Section 6 — Error Handling Tests

### Test Case ID: TC_CC_ERR_001
**Test Case Description:** Return 404 when chat session not found

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Session ID does not exist in database

**Test Case Procedure:**
1. Generate a random UUID as invalidSessionId.
2. Client sends GET /api/v1/learner/curriculum-chat/sessions/{invalidSessionId}/messages with Authorization: Bearer {learnerToken}.
3. AuthFilter validates token and resolves LEARNER user identity.
4. ChatService retrieves ChatSession from ChatSessionRepository.findById() with invalidSessionId.
5. ChatSessionRepository returns empty (no session found).
6. ChatService throws ResourceNotFoundException.
7. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND

---

### Test Case ID: TC_CC_ERR_002
**Test Case Description:** Prevent learner from accessing other learner's session (returns 404)

**Pre-conditions:**
- Learner 1 accessToken is available (from prior login)
- Learner 2 exists in database
- ChatSession exists in database (userId=Learner2.id)

**Test Case Procedure:**
1. Save Learner 2 user with email="other@test.com", role=LEARNER.
2. Create ChatSession for Learner 2.
3. Client (Learner 1) sends GET /api/v1/learner/curriculum-chat/sessions/{sessionId}/messages with Authorization: Bearer {learner1Token}.
4. AuthFilter validates token and resolves LEARNER 1 user identity.
5. ChatService retrieves ChatSession from ChatSessionRepository.findById().
6. ChatService validates user ownership of session (session.userId != learner1.id).
7. ChatService throws ResourceNotFoundException (session treated as not found for unauthorized user).
8. System returns the not found error response.

**Expected Results:** HTTP 404 NOT FOUND

---

## Section 7 — Alternative Flow Tests

### Test Case A1 — No Relevant Knowledge - Fallback Response

### Test Case ID: TC_CC_ALT_A1_001
**Test Case Description:** Return fallback response when no relevant knowledge found

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- ChatSession exists in database (status="ACTIVE", userId=learner.id)
- EmbeddingService mocked to return RagContext.empty()
- OpenAiChatModel mocked to return "I am afraid I do not know this from the knowledge base."

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/curriculum-chat/sessions/{sessionId}/messages with Authorization: Bearer {learnerToken} and JSON body:
   - message: "Hôm nay trời thế nào?"
2. AuthFilter validates token and resolves LEARNER user identity.
3. ChatRateLimiter.checkRateLimit() is called and does not throw exception.
4. ChatService retrieves ChatSession.
5. ChatService validates user ownership.
6. EmbeddingService.retrieveContext() returns empty context (no relevant knowledge).
7. OpenAiChatModel.call() is invoked and returns "I am afraid I do not know this from the knowledge base."
8. ChatService saves user message and AI response.
9. System returns the response with fallback message.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content = "I am afraid I do not know this from the knowledge base."
Database state:
- ChatMessage count = 2
- User message: role="USER", content="Hôm nay trời thế nào?"
- Assistant message: role="ASSISTANT", content="I am afraid I do not know this from the knowledge base."

---

### Test Case A2 — Out-of-Scope Question

### Test Case ID: TC_CC_ALT_A2_001
**Test Case Description:** Return guidance response for out-of-scope question

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- ChatSession exists in database (status="ACTIVE", userId=learner.id)
- OpenAiChatModel mocked to return "Guidance response for out of scope."

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/curriculum-chat/sessions/{sessionId}/messages with Authorization: Bearer {learnerToken} and JSON body:
   - message: "Hôm nay trời đẹp quá"
2. AuthFilter validates token and resolves LEARNER user identity.
3. ChatRateLimiter.checkRateLimit() is called and does not throw exception.
4. ChatService retrieves ChatSession.
5. ChatService validates user ownership.
6. EmbeddingService.retrieveContext() is called (may return empty or filtered context).
7. OpenAiChatModel.call() is invoked and returns "Guidance response for out of scope."
8. ChatService saves user message and AI response.
9. System returns the response with guidance message.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
Database state:
- ChatMessage count = 2
- User message: role="USER", content="Hôm nay trời đẹp quá"
- Assistant message: role="ASSISTANT", content="Guidance response for out of scope."

---

## Section 8 — Chat History Structure Tests

### Test Case ID: TC_CC_CHT_001
**Test Case Description:** Chat history stored with correct structure in database

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- ChatSession exists in database (status="ACTIVE", userId=learner.id)
- OpenAiChatModel mocked to return deterministic response

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/curriculum-chat/sessions/{sessionId}/messages with Authorization: Bearer {learnerToken} and JSON body:
   - message: "これは何ですか？"
2. AuthFilter validates token and resolves LEARNER user identity.
3. ChatService processes request and saves messages.
4. Client sends GET /api/v1/learner/curriculum-chat/sessions/{sessionId}/messages to retrieve stored messages.
5. System returns stored messages.
6. Test verifies message structure in database.

**Expected Results:** HTTP 200 OK (for both POST and GET)
Database state:
- ChatMessage count = 2
- User message:
  - sessionId = session.id
  - role = "USER"
  - content = "これは何ですか？"
  - sequenceNumber = 1
  - createdAt = not null
- Assistant message:
  - sessionId = session.id
  - role = "ASSISTANT"
  - content = not blank
  - sequenceNumber = 2
  - createdAt = not null
  - createdAt >= user message.createdAt

---

## Section 9 — Continue Conversation Tests

### Test Case ID: TC_CC_CONT_001
**Test Case Description:** Allow adding message to existing session with previous messages

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- ChatSession exists with messageCount=2, userMessageCount=1
- Two ChatMessage entities exist:
  - Message 1: role="USER", content="Previous question", sequenceNumber=1
  - Message 2: role="ASSISTANT", content="Previous answer", sequenceNumber=2
- OpenAiChatModel mocked to return "Follow up AI response"

**Test Case Procedure:**
1. Client sends POST /api/v1/learner/curriculum-chat/sessions/{sessionId}/messages with Authorization: Bearer {learnerToken} and JSON body:
   - message: "Follow-up question"
2. AuthFilter validates token and resolves LEARNER user identity.
3. ChatRateLimiter.checkRateLimit() is called and does not throw exception.
4. ChatService retrieves ChatSession (finds existing messages).
5. ChatService validates user ownership.
6. EmbeddingService.retrieveContext() is called.
7. OpenAiChatModel.call() is invoked and returns "Follow up AI response".
8. ChatService saves new user message with sequenceNumber=3.
9. ChatService saves new assistant message with sequenceNumber=4.
10. ChatService updates ChatSession with messageCount=4, userMessageCount=2.
11. System returns the response with assistant's new message.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
Database state:
- ChatMessage count = 4
- Message 3: role="USER", content="Follow-up question", sequenceNumber=3
- Message 4: role="ASSISTANT", content="Follow up AI response", sequenceNumber=4
- ChatSession.messageCount = 4
- ChatSession.userMessageCount = 2

---

## Test Environment Teardown

After each test:
- All ChatMessage entities are deleted
- All ChatSession entities are deleted
- All Lesson entities are deleted
- All ClassMember entities are deleted
- All Class entities are deleted
- All Course entities are deleted
- All User session entities are deleted
- All User entities are deleted


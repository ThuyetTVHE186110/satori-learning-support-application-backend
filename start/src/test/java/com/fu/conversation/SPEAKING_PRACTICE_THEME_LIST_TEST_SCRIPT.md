# Speaking Practice - View Conversation Theme List Test Script

## Overview
This document contains detailed test scripts for the View Conversation Theme List API endpoints in Satori Learning Support Application backend.

## Test Environment Setup

### Pre-conditions
- Test DB running via `application-test.properties` 
- PostgreSQL `satori_test` database created and initialized
- Docker environment up and running
- AI services mocked to prevent actual HTTP calls

---

## Section 1 — View Conversation Theme List Tests

### Test Case ID: TC_SP_VTL_001
**Test Case Description:** Learner views published themes only, sorted by orderIndex

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Multiple conversation themes created via saveTheme():
  - Theme B (status=PUBLISHED, orderIndex=2)
  - Theme A (status=PUBLISHED, orderIndex=1)
  - Theme Draft (status=DRAFT, orderIndex=0)
  - Theme Deleted (soft-deleted via saveSoftDeletedTheme())

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/conversation/themes with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. ConversationController forwards the request to ConversationService.
4. ConversationService retrieves themes from ConversationThemeRepository (excluding DRAFT and soft-deleted records).
5. ConversationThemeRepository queries the database and returns paginated data sorted by orderIndex.
6. System returns paginated theme list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.content.length() = 2
- data.content[0].title = "Theme A" (orderIndex=1, first in sort)
- data.content[1].title = "Theme B" (orderIndex=2, second in sort)
- data.content[*].status = "PUBLISHED"

---

### Test Case ID: TC_SP_VTL_002
**Test Case Description:** Keyword filter returns matching themes

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Multiple published themes created:
  - "Shopping" (status=PUBLISHED, orderIndex=1)
  - "Travel" (status=PUBLISHED, orderIndex=2)

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/conversation/themes?keyword=shop with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. ConversationController forwards the request to ConversationService with keyword filter.
4. ConversationService retrieves themes by keyword search via ConversationThemeRepository.
5. ConversationThemeRepository queries the database with LIKE filter on title and returns matching results.
6. System returns the filtered paginated theme list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.totalElements = 1
- data.content.length() = 1
- data.content[0].title = "Shopping"

---

### Test Case ID: TC_SP_VTL_003
**Test Case Description:** Content Manager role forbidden → 403 FORBIDDEN

**Pre-conditions:**
- Content Manager accessToken is available (from prior login)
- Published themes exist in database

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/conversation/themes with Authorization: Bearer {managerToken}.
2. AuthFilter validates token and resolves CONTENT_MANAGER identity.
3. SecurityConfig blocks access - endpoint requires LEARNER role.
4. System returns access denied error response.

**Expected Results:** HTTP 403 FORBIDDEN

---

### Test Case ID: TC_SP_VTL_004
**Test Case Description:** No authorization token → 401 UNAUTHORIZED

**Pre-conditions:**
- Published themes exist in database
- No Authorization header provided

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/conversation/themes without Authorization header.
2. AuthFilter detects missing token and returns unauthorized error.
3. System returns the unauthorized error response.

**Expected Results:** HTTP 401 UNAUTHORIZED

---

### Test Case ID: TC_SP_VTL_005
**Test Case Description:** Filter by JLPT level

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Multiple published themes with different JLPT levels:
  - "N5 Theme" (status=PUBLISHED, jlptLevel=N5)
  - "N4 Theme" (status=PUBLISHED, jlptLevel=N4)

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/conversation/themes?level=N5 with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. ConversationController forwards the request to ConversationService with level filter.
4. ConversationService retrieves themes by JLPT level via ConversationThemeRepository.
5. ConversationThemeRepository queries the database with level filter and returns matching results.
6. System returns the filtered paginated theme list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.totalElements = 1
- data.content.length() = 1
- data.content[0].title = "N5 Theme"
- data.content[0].jlptLevel = "N5"

---

### Test Case ID: TC_SP_VTL_006
**Test Case Description:** Multiple filters applied together (keyword + level)

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Multiple published themes:
  - "N5 Theme hahaa" (status=PUBLISHED, jlptLevel=N5)
  - "N4 Theme" (status=PUBLISHED, jlptLevel=N4)
  - "N5 Shopping" (status=PUBLISHED, jlptLevel=N5)

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/conversation/themes?keyword=ha&level=N5 with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. ConversationController forwards the request to ConversationService with both filters.
4. ConversationService applies both keyword and level filters via ConversationThemeRepository.
5. ConversationThemeRepository queries the database with combined filters and returns matching results.
6. System returns the filtered paginated theme list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.totalElements = 1
- data.content.length() = 1
- data.content[0].title = "N5 Theme hahaa"
- data.content[0].jlptLevel = "N5"

---

### Test Case ID: TC_SP_VTL_007
**Test Case Description:** Pagination returns correct page

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- Multiple published themes created:
  - "Theme A" (orderIndex=0)
  - "Theme B" (orderIndex=1)
  - "Theme C" (orderIndex=2)

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/conversation/themes?page=1&size=2 with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. ConversationController forwards the request to ConversationService with pagination params.
4. ConversationService retrieves paginated themes via ConversationThemeRepository.
5. ConversationThemeRepository queries the database with pagination and returns page 1 (0-indexed) with size 2.
6. System returns the paginated theme list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.pageNumber = 1
- data.pageSize = 2
- data.totalElements = 3
- data.totalPages = 2
- data.content.length() = 1
- data.content[0].title = "Theme C"

---

### Test Case ID: TC_SP_VTL_008
**Test Case Description:** Page size clamp to maximum (50)

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- 60+ published themes created via loop

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/conversation/themes?size=100 with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. ConversationController forwards the request to ConversationService with size param.
4. ConversationService clamps size to maximum 50 via validation logic.
5. ConversationService retrieves paginated themes via ConversationThemeRepository with size=50.
6. System returns the paginated theme list response with clamped size.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.pageSize = 50
- data.content.length() = 50
- data.totalElements = 60+

---

### Test Case ID: TC_SP_VTL_009
**Test Case Description:** Empty list returns 200 with no content

**Pre-conditions:**
- Learner accessToken is available (from prior login)
- No published themes exist in database

**Test Case Procedure:**
1. Client sends GET /api/v1/learner/conversation/themes with Authorization: Bearer {token}.
2. AuthFilter validates token and resolves LEARNER identity.
3. ConversationController forwards the request to ConversationService.
4. ConversationService retrieves themes from ConversationThemeRepository.
5. ConversationThemeRepository queries the database and returns empty list.
6. System returns the empty paginated theme list response.

**Expected Results:** HTTP 200 OK
Response JSON:
- success = true
- data.totalElements = 0
- data.content.length() = 0

---

### Test Case ID: TC_SP_VTL_010
**Test Case Description:** Expired JWT token returns 401 UNAUTHORIZED

**Pre-conditions:**
- Learner account exists
- JWT token manually created with past expiration date

**Test Case Procedure:**
1. Client creates expired JWT token with learner identity.
2. Client sends GET /api/v1/learner/conversation/themes with Authorization: Bearer {expiredToken}.
3. AuthFilter validates token and detects expiration.
4. AuthFilter returns unauthorized error with specific error code.
5. System returns the unauthorized error response.

**Expected Results:** HTTP 401 UNAUTHORIZED
Response JSON:
- success = false
- code = "UNAUTHORIZED"


## Test Data Cleanup

### After Each Test Case:
- Clear all conversation themes, topics, sessions, and user data
- Reset database sequences if applicable
- Verify no orphaned records remain

### Test Execution Order:
Tests can be executed in any order as they use isolated test data and clean up after each execution.

# Speaking Practice (Learner) — Test Procedures
> Format: System Interaction Steps
> Last updated: 2026-03-13

---

## Section 1 — View Conversation Theme List
Endpoint: `GET /api/v1/learner/conversation/themes`

### TC_SP_VTL_001 — published themes only, sorted by orderIndex
1. Client → GET `/api/v1/learner/conversation/themes` with valid LEARNER Bearer token.
2. AuthFilter → parse token and resolve LEARNER.
3. LearnerConversationController → call `ConversationThemeService.getPublishedThemes()`.
4. Service → return only `PUBLISHED` themes ordered by `order_index` (deleted_at IS NULL).
5. System → response contains only published themes sorted ascending.

### TC_SP_VTL_002 — keyword filter
1. Client → GET `/api/v1/learner/conversation/themes?keyword=mua` with valid LEARNER token.
2. Controller → call `searchPublishedThemes()`.
3. Service → return only themes matching keyword (case-insensitive).
4. System → response contains matching themes only.

### TC_SP_VTL_003 — forbidden for non-LEARNER role
1. Client → GET `/api/v1/learner/conversation/themes` with MANAGER token.
2. AuthFilter → role mismatch.
3. System → HTTP 403.

### TC_SP_VTL_004 — no token returns 401
1. Client → GET `/api/v1/learner/conversation/themes` with no Authorization header.
2. AuthFilter → unauthenticated.
3. System → HTTP 401.

### TC_SP_VTL_005 — filter by JLPT level
1. Client → GET `/api/v1/learner/conversation/themes?level=N5` with LEARNER token.
2. Service → return only themes matching JLPT level.
3. System → HTTP 200 with filtered list.

### TC_SP_VTL_006 — keyword blank falls back to list
1. Client → GET `/api/v1/learner/conversation/themes?keyword=   ` with LEARNER token.
2. Controller → treat blank keyword as absent.
3. System → HTTP 200 with full list.

### TC_SP_VTL_007 — pagination returns correct page
1. Client → GET `/api/v1/learner/conversation/themes?page=1&size=2`.
2. Service → return paginated results.
3. System → `pageNumber=1`, `pageSize=2`, content length reflects remaining items.

### TC_SP_VTL_008 — size clamp to 50
1. Client → GET `/api/v1/learner/conversation/themes?size=100`.
2. Controller → clamp size to 50.
3. System → `pageSize=50`.

### TC_SP_VTL_009 — empty list returns 200 with no content
1. Client → GET `/api/v1/learner/conversation/themes` when no data exists.
2. System → `totalElements=0`, `content=[]`.

---

## Section 2 — View Conversation Topic List
Endpoints:
- `GET /api/v1/learner/conversation/themes/{themeId}/topics`
- `GET /api/v1/learner/conversation/topics/{topicId}`

### TC_SP_VTP_001 — published topics only, ordered by orderIndex
1. Client → GET `/api/v1/learner/conversation/themes/{themeId}/topics` with LEARNER token.
2. Service → return only `PUBLISHED` topics ordered by `order_index`.
3. System → response excludes DRAFT/ARCHIVED/deleted topics.

### TC_SP_VTP_002 — practice stats populated
1. Precondition: user has at least one `conversation_session` for a topic.
2. Client → GET `/api/v1/learner/conversation/themes/{themeId}/topics`.
3. Service → enrich topic response with `practiced`, `practiceCount`, `lastPracticedAt`.
4. System → response shows practiced=true for that topic.

### TC_SP_VTP_003 — forbidden for non-LEARNER role
1. Client → GET `/api/v1/learner/conversation/themes/{themeId}/topics` with MANAGER token.
2. AuthFilter → role mismatch.
3. System → HTTP 403.

### TC_SP_VTP_004 — view published topic detail
1. Client → GET `/api/v1/learner/conversation/topics/{topicId}` with LEARNER token.
2. Service → return topic detail if status=PUBLISHED.
3. System → HTTP 200 with full topic detail.

### TC_SP_VTP_005 — non-published topic detail returns 404
1. Client → GET `/api/v1/learner/conversation/topics/{topicId}` where topic status=DRAFT.
2. Service → not found for learner.
3. System → HTTP 404.

### TC_SP_VTP_006 — no token returns 401
1. Client → GET `/api/v1/learner/conversation/themes/{themeId}/topics` with no Authorization header.
2. System → HTTP 401.

### TC_SP_VTP_007 — no practice stats when user has no sessions
1. Precondition: user has no sessions for any topics.
2. Client → GET `/api/v1/learner/conversation/themes/{themeId}/topics`.
3. System → `practiced=false`, `practiceCount=0` for each topic.

### TC_SP_VTP_008 — soft-deleted topic excluded
1. Precondition: one topic soft-deleted.
2. Client → GET `/api/v1/learner/conversation/themes/{themeId}/topics`.
3. System → soft-deleted topic not present.

### TC_SP_VTP_009 — theme with no topics returns empty list
1. Client → GET `/api/v1/learner/conversation/themes/{themeId}/topics` where no topics exist.
2. System → `content=[]`.

### TC_SP_VTP_010 — non-existent theme returns empty list
1. Client → GET `/api/v1/learner/conversation/themes/{themeId}/topics` with random themeId.
2. System → `content=[]`.

### TC_SP_VTP_011 — practice stats map counts multiple sessions
1. Precondition: user has 2 sessions for same topic.
2. Client → GET `/api/v1/learner/conversation/themes/{themeId}/topics`.
3. System → `practiceCount=2` for that topic.

### TC_SP_VTP_012 — topic summary payload includes required fields
1. Client → GET `/api/v1/learner/conversation/themes/{themeId}/topics`.
2. System → each item has `id`, `title`, `status`.

### TC_SP_VTP_013 — lastPracticedAt populated when session exists
1. Precondition: user has at least one `conversation_session` for a topic.
2. Client → GET `/api/v1/learner/conversation/themes/{themeId}/topics`.
3. System → `lastPracticedAt` is non-null.

---

## Section 3 — Practice Conversation with AI (Roleplay)
Endpoints:
- `POST /api/v1/learner/roleplay/sessions`
- `POST /api/v1/learner/roleplay/sessions/{sessionId}/messages`
- `POST /api/v1/learner/roleplay/sessions/{sessionId}/complete`
- `POST /api/v1/learner/roleplay/sessions/{sessionId}/abandon`
- `GET  /api/v1/learner/roleplay/sessions`

### TC_SP_AI_001 — start roleplay session success
1. Client → POST `/api/v1/learner/roleplay/sessions` with valid topicId.
2. Service → validate topic (PUBLISHED, isRoleplay=true), create session + missions.
3. Service → call AI opening message (mocked), parse segments.
4. System → HTTP 201 with messages and missions.

### TC_SP_AI_002 — start session with non-roleplay topic → 404
1. Client → POST with topic isRoleplay=false.
2. Service → fail topic validation.
3. System → HTTP 404.

### TC_SP_AI_003 — send message success
1. Client → POST `/sessions/{id}/messages` multipart with JSON message.
2. Service → save user message, call AI response (mocked), save AI messages.
3. System → HTTP 200 with AI messages and missions.

### TC_SP_AI_004 — complete session returns evaluation
1. Client → POST `/sessions/{id}/complete`.
2. Service → compute scores, call evaluator AI (mocked).
3. System → HTTP 200 with evaluation report.

### TC_SP_AI_005 — start session with draft topic → 404
1. Client → POST with topic status=DRAFT.
2. Service → fail topic validation.
3. System → HTTP 404.

### TC_SP_AI_006 — active session limit reached → 400
1. Precondition: user already has max active sessions.
2. Client → POST `/api/v1/learner/roleplay/sessions`.
3. System → HTTP 400 with SESSION_LIMIT.

### TC_SP_AI_007 — send message with blank content → 400
1. Client → POST `/sessions/{id}/messages` with blank content.
2. System → HTTP 400.

### TC_SP_AI_008 — send message to inactive session → 400
1. Precondition: session status=COMPLETED.
2. Client → POST `/sessions/{id}/messages`.
3. System → HTTP 400.

### TC_SP_AI_009 — complete inactive session → 400
1. Precondition: session status=COMPLETED.
2. Client → POST `/sessions/{id}/complete`.
3. System → HTTP 400.

### TC_SP_AI_010 — start session with missing topic → 404
1. Client → POST with random topicId.
2. System → HTTP 404.

### TC_SP_AI_011 — start session caches system prompt
1. Client → POST `/api/v1/learner/roleplay/sessions`.
2. System → cacheSystemPrompt invoked (mock verify).

### TC_SP_AI_012 — start session persists opening messages
1. Client → POST `/api/v1/learner/roleplay/sessions`.
2. System → AI opening segments saved as messages in DB.

### TC_SP_AI_013 — send message to non-existent session → 404
1. Client → POST `/sessions/{id}/messages` with random sessionId.
2. System → HTTP 404.

### TC_SP_AI_014 — message limit exceeded → 400
1. Precondition: user has max messages in session.
2. Client → POST `/sessions/{id}/messages`.
3. System → HTTP 400.

### TC_SP_AI_015 — audio too large → 400
1. Client → POST `/sessions/{id}/messages` with audio > 10MB.
2. System → HTTP 400.

### TC_SP_AI_016 — invalid audio content type → 400
1. Client → POST `/sessions/{id}/messages` with non-audio file.
2. System → HTTP 400.

### TC_SP_AI_017 — pronunciation provider available with audio
1. Client → POST `/sessions/{id}/messages` with audio and provider available.
2. System → HTTP 200 and messages returned.

### TC_SP_AI_018 — free-talk phase transition creates dynamic mission
1. Client → start free-talk session.
2. Client → POST `/sessions/{id}/messages` (AI returns PHASE_TRANSITION).
3. System → dynamic session mission created.

### TC_SP_AI_019 — mission progress updates after send message
1. Client → start roleplay session.
2. Client → POST `/sessions/{id}/messages`.
3. MissionEvaluatorAI returns progress for missionId.
4. System → session mission updated (progressPct/status).

### TC_SP_AI_020 — session history pagination
1. Precondition: 2 completed sessions for learner.
2. Client → GET `/api/v1/learner/roleplay/sessions?page=0&size=1`.
3. System → `totalElements=2`, `content.length=1`.

### TC_SP_AI_021 — abandon session sets status
1. Precondition: active session exists.
2. Client → POST `/api/v1/learner/roleplay/sessions/{sessionId}/abandon`.
3. System → session status updated to ABANDONED.

---

## Section 4 — Create Custom Conversation Topic
Endpoints:
- `POST /api/v1/learner/conversation/my-topics`
- `GET  /api/v1/learner/conversation/my-topics`
- `GET  /api/v1/learner/conversation/my-topics/{id}`
- `PUT  /api/v1/learner/conversation/my-topics/{id}`
- `DELETE /api/v1/learner/conversation/my-topics/{id}`
- `POST /api/v1/learner/conversation/my-topics/{id}/generate-context`
- `POST /api/v1/learner/roleplay/free-talk/sessions`

### TC_SP_CT_001 — create personal topic
1. Client → POST `/api/v1/learner/conversation/my-topics` with title/scenario.
2. Service → create user topic.
3. System → HTTP 201 with created topic.

### TC_SP_CT_002 — generate context with scenario
1. Client → POST `/my-topics/{id}/generate-context`.
2. Service → generate AI context (mocked) and persist.
3. System → HTTP 200 with updated context.

### TC_SP_CT_003 — generate context without scenario → 400
1. Client → POST `/my-topics/{id}/generate-context` (scenario null).
2. Service → throw `MISSING_SCENARIO`.
3. System → HTTP 400.

### TC_SP_CT_004 — start free-talk session
1. Client → POST `/api/v1/learner/roleplay/free-talk/sessions` with jlptLevel.
2. Service → create FREE_TALK session and setup messages.
3. System → HTTP 201.

### TC_SP_CT_005 — create personal topic with blank title → 400
1. Client → POST `/api/v1/learner/conversation/my-topics` with blank title.
2. System → HTTP 400.

### TC_SP_CT_006 — no token returns 401
1. Client → POST `/api/v1/learner/conversation/my-topics` with no Authorization header.
2. System → HTTP 401.

### TC_SP_CT_007 — free-talk invalid jlpt level → 400
1. Client → POST `/api/v1/learner/roleplay/free-talk/sessions` with invalid jlptLevel.
2. System → HTTP 400.

### TC_SP_CT_008 — list my topics pagination
1. Client → GET `/api/v1/learner/conversation/my-topics?page=0&size=1`.
2. System → paginated list with total count.

### TC_SP_CT_009 — get my topic by id
1. Client → GET `/api/v1/learner/conversation/my-topics/{id}`.
2. System → HTTP 200 with topic detail.

### TC_SP_CT_010 — update my topic
1. Client → PUT `/api/v1/learner/conversation/my-topics/{id}`.
2. System → HTTP 200 with updated topic.

### TC_SP_CT_011 — delete my topic
1. Client → DELETE `/api/v1/learner/conversation/my-topics/{id}`.
2. System → HTTP 200, topic no longer accessible.

### TC_SP_CT_012 — access another user's topic → 404
1. Client → GET `/api/v1/learner/conversation/my-topics/{id}` as different user.
2. System → HTTP 404.

### TC_SP_CT_013 — generate context with blank scenario → 400
1. Client → POST `/api/v1/learner/conversation/my-topics/{id}/generate-context` with blank scenario.
2. System → HTTP 400.

### TC_SP_CT_014 — generate context persists aiGeneratedContext
1. Client → POST `/api/v1/learner/conversation/my-topics/{id}/generate-context`.
2. Service → persist `aiGeneratedContext` into DB.
3. System → subsequent load shows non-empty `aiGeneratedContext`.

---

## Notes / Gaps
- Auto-position by learner progress and lesson filtering are not implemented; tests do not cover these behaviors.
- Free-talk start does not enforce user-provided context; behavior matches current implementation.
- External API quality checks (OpenAI/Azure) are manual via Postman.

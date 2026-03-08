# Conversation Topic Management — Test Procedures
> Format: System Interaction Steps  
> Last updated: 2026-03-08

---

## Section 1 — View Topic List By Theme `GET /api/v1/manager/conversation/themes/{themeId}/topics` [P3 Sanity]

### TC_CTP_VTL_001 — 3 active topics returned, soft-deleted excluded
1. Client → GET `/api/v1/manager/conversation/themes/{themeId}/topics` with a valid MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationTopicController → call `ConversationTopicService.getTopicsByThemeId()`.
4. ConversationTopicService → retrieve active topics by Theme ID (deleted_at IS NULL).
5. ConversationTopicService → build the paginated response.
6. System → return response.

---

### TC_CTP_VTL_002 — default pagination: page=0, size=10, totalElements=15, totalPages=2
1. Client → GET `/api/v1/manager/conversation/themes/{themeId}/topics` with no 'page' or 'size' params and a valid MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationTopicController → call `ConversationTopicService.getTopicsByThemeId()` with default pagination params.
4. ConversationTopicService → retrieve active topics for the theme with page 0 and size 10.
5. System → return response.

---

### TC_CTP_VTL_003 — theme with no topics → empty content, totalElements=0
1. Client → GET `/api/v1/manager/conversation/themes/{themeId}/topics` with a valid MANAGER Bearer token (theme has no topics).
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationTopicController → call `ConversationTopicService.getTopicsByThemeId()`.
4. ConversationTopicService → retrieve topics for the theme (none found).
5. System → return response.

---

### TC_CTP_VTL_004 — no token → HTTP 401 Unauthorized
1. Client → GET `/api/v1/manager/conversation/themes/{themeId}/topics` with no Authorization header.
2. AuthFilter → detect that no token is present.
3. AuthFilter → reject the request as unauthenticated.
4. System → return response.

---

### TC_CTP_VTL_005 — LEARNER token → HTTP 403 Forbidden
1. Client → GET `/api/v1/manager/conversation/themes/{themeId}/topics` with a valid LEARNER Bearer token.
2. AuthFilter → parse the token and resolve the user identity (role=LEARNER).
3. AuthFilter → detect that the LEARNER role is not authorized for this endpoint.
4. System → return response.

---

## Section 2 — View Topic Detail `GET /api/v1/manager/conversation/topics/{topicId}` [P3 Sanity]

### TC_CTP_VTI_001 — HTTP 200, all required fields present and correct
1. Client → GET `/api/v1/manager/conversation/topics/{topicId}` with a valid MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationTopicController → call `ConversationTopicService.getTopicById()`.
4. ConversationTopicService → retrieve the topic by ID (deleted_at IS NULL).
5. ConversationTopicService → build the ConversationTopicResponse with all required fields.
6. System → return response.

---

### TC_CTP_VTI_002 — non-existent topicId → HTTP 404, code=NOT_FOUND
1. Client → GET `/api/v1/manager/conversation/topics/00000000-0000-0000-0000-000000000000` with a valid MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationTopicController → call `ConversationTopicService.getTopicById()`.
4. ConversationTopicService → attempt to retrieve the topic by ID.
5. ConversationTopicService → detect that no topic with the given ID exists.
6. System → return response.

---

### TC_CTP_VTI_003 — soft-deleted topic → HTTP 404, code=NOT_FOUND
1. Client → GET `/api/v1/manager/conversation/topics/{topicId}` with a valid MANAGER Bearer token (topic is soft-deleted).
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationTopicController → call `ConversationTopicService.getTopicById()`.
4. ConversationTopicService → attempt to retrieve the topic by ID (deleted_at IS NULL filter).
5. ConversationTopicService → detect that the topic has been soft-deleted (not found).
6. System → return response.

---

### TC_CTP_VTI_004 — no token → HTTP 401 Unauthorized
1. Client → GET `/api/v1/manager/conversation/topics/{topicId}` with no Authorization header.
2. AuthFilter → detect that no token is present.
3. AuthFilter → reject the request as unauthenticated.
4. System → return response.

---

## Section 3 — Create Topic `POST /api/v1/manager/conversation/topics` [P2 Standard]

### TC_CTP_CT_001 — title only → HTTP 201, defaults: difficultyScore=1, isRoleplay=true, orderIndex=0
1. Client → POST `/api/v1/manager/conversation/topics` with a valid MANAGER Bearer token and `{"title":"Mua sắm tại siêu thị"}`.
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationTopicController → validate the request body and call `ConversationTopicService.createTopic()`.
4. ConversationTopicService → create a new topic with the provided title and default values.
5. ConversationTopicService → persist the topic to the database (themeId null, defaults applied).
6. System → return response.

---

### TC_CTP_CT_002 — all optional fields → HTTP 201, all fields persisted to DB
1. Client → POST `/api/v1/manager/conversation/topics` with a valid MANAGER Bearer token and all fields provided (title, titleJapanese, description, scenario, jlptLevel, difficultyScore, isRoleplay, category, orderIndex).
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationTopicController → validate the request body and call `ConversationTopicService.createTopic()`.
4. ConversationTopicService → create a new topic with all provided fields.
5. ConversationTopicService → persist the topic to the database.
6. System → return response.

---

### TC_CTP_CT_003 — valid themeId → HTTP 201, topic appears in theme's topic list
1. Client → POST `/api/v1/manager/conversation/topics` with a valid MANAGER Bearer token and `{"title":"Hỏi đường", "themeId":"{validThemeId}"}`.
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationTopicController → validate the request body and call `ConversationTopicService.createTopic()`.
4. ConversationTopicService → verify that the provided Theme exists and is active.
5. ConversationTopicService → create and persist the topic linked to the theme.
6. System → return response.

---

### TC_CTP_CT_004 — no themeId → HTTP 201, topic.themeId=null in DB
1. Client → POST `/api/v1/manager/conversation/topics` with a valid MANAGER Bearer token and `{"title":"Orphan Topic"}` (no themeId).
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationTopicController → validate the request body and call `ConversationTopicService.createTopic()`.
4. ConversationTopicService → create and persist the orphan topic (themeId is null).
5. System → return response.

---

### TC_CTP_CT_005 — blank title → HTTP 400 VALIDATION_ERROR, no new record in DB
1. Client → POST `/api/v1/manager/conversation/topics` with a valid MANAGER Bearer token and `{"title":"   "}`.
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationTopicController → validate the request body.
4. ConversationTopicController → detect that the title field violates @NotBlank constraint.
5. System → return response.

---

### TC_CTP_CT_006 — non-existent themeId → HTTP 404, no new record in DB
1. Client → POST `/api/v1/manager/conversation/topics` with a valid MANAGER Bearer token and a non-existent `themeId`.
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationTopicController → validate the request body and call `ConversationTopicService.createTopic()`.
4. ConversationTopicService → verify the existence of the theme.
5. ConversationTopicService → detect that the theme does not exist.
6. System → return response.

---

### TC_CTP_CT_007 — soft-deleted themeId → HTTP 404, no new record in DB
1. Client → POST `/api/v1/manager/conversation/topics` with a valid MANAGER Bearer token and a soft-deleted `themeId`.
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationTopicController → validate the request body and call `ConversationTopicService.createTopic()`.
4. ConversationTopicService → attempt to retrieve the theme by ID (filtering active only).
5. ConversationTopicService → detect that the theme is deleted (not found).
6. System → return response.

---

### TC_CTP_CT_008 — no token → HTTP 401 Unauthorized
1. Client → POST `/api/v1/manager/conversation/topics` with no Authorization header.
2. AuthFilter → detect that no token is present.
3. AuthFilter → reject the request as unauthenticated.
4. System → return response.

---

### TC_CTP_CT_009 — LEARNER token → HTTP 403 Forbidden
1. Client → POST `/api/v1/manager/conversation/topics` with a valid LEARNER Bearer token.
2. AuthFilter → parse the token and resolve the user identity (role=LEARNER).
3. AuthFilter → detect that the LEARNER role is not authorized for this endpoint.
4. System → return response.

---

### TC_CTP_CT_010 — ADMIN token → HTTP 403 Forbidden
1. Client → POST `/api/v1/manager/conversation/topics` with a valid ADMIN Bearer token.
2. AuthFilter → parse the token and resolve the user identity (role=ADMIN).
3. AuthFilter → detect that the ADMIN role is not authorized for this endpoint.
4. System → return response.

---

## Section 4 — Update Topic `PUT /api/v1/manager/conversation/topics/{topicId}` [P2 Standard]

### TC_CTP_UT_001 — update title+description → HTTP 200, DB updated, updatedAt refreshed
1. Client → PUT `/api/v1/manager/conversation/topics/{topicId}` with a valid MANAGER Bearer token and `{"title":"New Title", "description":"New description"}`.
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationTopicController → validate the request body and call `ConversationTopicService.updateTopic()`.
4. ConversationTopicService → retrieve the topic by ID (deleted_at IS NULL).
5. ConversationTopicService → apply the title and description changes.
6. ConversationTopicService → persist the updated topic (updatedAt refreshed).
7. System → return response.

---

### TC_CTP_UT_002 — optional fields only → HTTP 200, title unchanged in DB
1. Client → PUT `/api/v1/manager/conversation/topics/{topicId}` with a valid MANAGER Bearer token and `{"jlptLevel":"N3", "difficultyScore":5, "isRoleplay":true}`.
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationTopicController → validate the request body and call `ConversationTopicService.updateTopic()`.
4. ConversationTopicService → retrieve the topic by ID (deleted_at IS NULL).
5. ConversationTopicService → apply the new field values without altering the original title.
6. ConversationTopicService → persist the updated topic.
7. System → return response.

---

### TC_CTP_UT_003 — blank title → HTTP 400 VALIDATION_ERROR (NOTE-C: may be a known bug)
1. Client → PUT `/api/v1/manager/conversation/topics/{topicId}` with a valid MANAGER Bearer token and `{"title":"   "}`.
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationTopicController → validate the request body.
4. Controller/Service → detect that the title field is blank and invalid.
5. System → return response.

---

### TC_CTP_UT_004 — non-existent topicId → HTTP 404, code=NOT_FOUND
1. Client → PUT `/api/v1/manager/conversation/topics/00000000-0000-0000-0000-000000000000` with a valid MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationTopicController → validate the request body and call `ConversationTopicService.updateTopic()`.
4. ConversationTopicService → attempt to retrieve the topic by ID.
5. ConversationTopicService → detect that no topic with the given ID exists.
6. System → return response.

---

### TC_CTP_UT_005 — soft-deleted topic → HTTP 404, code=NOT_FOUND
1. Client → PUT `/api/v1/manager/conversation/topics/{topicId}` with a valid MANAGER Bearer token (topic is soft-deleted).
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationTopicController → call `ConversationTopicService.updateTopic()`.
4. ConversationTopicService → attempt to retrieve the topic by ID (deleted_at IS NULL filter).
5. ConversationTopicService → detect that the topic is already soft-deleted (not found).
6. System → return response.

---

### TC_CTP_UT_006 — no token → HTTP 401 Unauthorized
1. Client → PUT `/api/v1/manager/conversation/topics/{topicId}` with no Authorization header.
2. AuthFilter → detect that no token is present.
3. AuthFilter → reject the request as unauthenticated.
4. System → return response.

---

### TC_CTP_UT_007 — LEARNER token → HTTP 403 Forbidden
1. Client → PUT `/api/v1/manager/conversation/topics/{topicId}` with a valid LEARNER Bearer token.
2. AuthFilter → parse the token and resolve the user identity (role=LEARNER).
3. AuthFilter → detect that the LEARNER role is not authorized for this endpoint.
4. System → return response.

---

### TC_CTP_UT_008 — ADMIN token → HTTP 403 Forbidden
1. Client → PUT `/api/v1/manager/conversation/topics/{topicId}` with a valid ADMIN Bearer token.
2. AuthFilter → parse the token and resolve the user identity (role=ADMIN).
3. AuthFilter → detect that the ADMIN role is not authorized for this endpoint.
4. System → return response.

---

## Section 5 — Delete Topic `DELETE /api/v1/manager/conversation/topics/{topicId}` [P2 Standard]

### TC_CTP_DT_001 — soft-delete active topic → HTTP 200, deleted_at set, topic absent from list
1. Client → DELETE `/api/v1/manager/conversation/topics/{topicId}` with a valid MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationTopicController → call `ConversationTopicService.deleteTopic()`.
4. ConversationTopicService → retrieve the topic by ID (deleted_at IS NULL).
5. ConversationTopicService → set deleted_at and deleted_by (manager UUID) on the topic.
6. ConversationTopicService → persist the soft-deleted topic.
7. System → return response.

---

### TC_CTP_DT_002 — non-existent topicId → HTTP 404, code=NOT_FOUND
1. Client → DELETE `/api/v1/manager/conversation/topics/00000000-0000-0000-0000-000000000000` with a valid MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationTopicController → call `ConversationTopicService.deleteTopic()`.
4. ConversationTopicService → attempt to retrieve the topic by ID.
5. ConversationTopicService → detect that no topic with the given ID exists.
6. System → return response.

---

### TC_CTP_DT_003 — already soft-deleted topic → HTTP 404, code=NOT_FOUND
1. Client → DELETE `/api/v1/manager/conversation/topics/{topicId}` with a valid MANAGER Bearer token (topic is already soft-deleted).
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationTopicController → call `ConversationTopicService.deleteTopic()`.
4. ConversationTopicService → attempt to retrieve the topic by ID (deleted_at IS NULL filter).
5. ConversationTopicService → detect that the topic is already soft-deleted (not found).
6. System → return response.

---

### TC_CTP_DT_004 — no token → HTTP 401 Unauthorized
1. Client → DELETE `/api/v1/manager/conversation/topics/{topicId}` with no Authorization header.
2. AuthFilter → detect that no token is present.
3. AuthFilter → reject the request as unauthenticated.
4. System → return response.

---

### TC_CTP_DT_005 — LEARNER token → HTTP 403 Forbidden
1. Client → DELETE `/api/v1/manager/conversation/topics/{topicId}` with a valid LEARNER Bearer token.
2. AuthFilter → parse the token and resolve the user identity (role=LEARNER).
3. AuthFilter → detect that the LEARNER role is not authorized for this endpoint.
4. System → return response.

---

### TC_CTP_DT_006 — ADMIN token → HTTP 403 Forbidden
1. Client → DELETE `/api/v1/manager/conversation/topics/{topicId}` with a valid ADMIN Bearer token.
2. AuthFilter → parse the token and resolve the user identity (role=ADMIN).
3. AuthFilter → detect that the ADMIN role is not authorized for this endpoint.
4. System → return response.

---

## Section 6 — Generate By AI (validation & permission only) `POST /api/v1/manager/conversation/themes/generate` [P2 Standard]

### TC_CTP_GAI_005 — count=0 → HTTP 400 VALIDATION_ERROR (@Min=1 violated)
1. Client → POST `/api/v1/manager/conversation/themes/generate` with a valid MANAGER Bearer token and `{"courseId":"{uuid}", "count":0}`.
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationThemeController → validate the request body.
4. ConversationThemeController → detect that the 'count' field violates @Min(1) constraint.
5. System → return response.

---

### TC_CTP_GAI_010 — no token → HTTP 401 Unauthorized
1. Client → POST `/api/v1/manager/conversation/themes/generate` with no Authorization header.
2. AuthFilter → detect that no token is present.
3. AuthFilter → reject the request as unauthenticated.
4. System → return response.

---

### TC_CTP_GAI_011 — LEARNER token → HTTP 403 Forbidden
1. Client → POST `/api/v1/manager/conversation/themes/generate` with a valid LEARNER Bearer token.
2. AuthFilter → parse the token and resolve the user identity (role=LEARNER).
3. AuthFilter → detect that the LEARNER role is not authorized for this endpoint.
4. System → return response.

---

### TC_CTP_GAI_012 — ADMIN token → HTTP 403 Forbidden
1. Client → POST `/api/v1/manager/conversation/themes/generate` with a valid ADMIN Bearer token.
2. AuthFilter → parse the token and resolve the user identity (role=ADMIN).
3. AuthFilter → detect that the ADMIN role is not authorized for this endpoint.
4. System → return response.

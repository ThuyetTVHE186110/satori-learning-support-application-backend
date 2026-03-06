# Conversation Theme Management — Test Procedures
> Format: System Interaction Steps  
> Last updated: 2026-03-06

---

## Section 1 — View Theme List `GET /api/v1/manager/conversation/themes` [P3 Sanity]

### TC_CT_VTL_001 — 3 active themes (DRAFT/PUBLISHED/ARCHIVED) returned, soft-deleted excluded
1. Client → GET `/api/v1/manager/conversation/themes` with a valid MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationThemeController → call `ConversationThemeService.getThemes()`.
4. ConversationThemeService → retrieve all non-deleted themes (deleted_at IS NULL).
5. ConversationThemeService → return paginated list containing DRAFT, PUBLISHED, and ARCHIVED themes.
6. System → return the paginated theme list response (totalElements=3, soft-deleted excluded).

---

### TC_CT_VTL_002 — keyword='mua' → only 'Mua sắm' returned, 'Du lịch' absent
1. Client → GET `/api/v1/manager/conversation/themes?keyword=mua` with a valid MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationThemeController → call `ConversationThemeService.getThemes()` with keyword filter.
4. ConversationThemeService → retrieve themes whose title matches the keyword (case-insensitive).
5. ConversationThemeService → return the matching theme only.
6. System → return the paginated theme list response (totalElements=1, only 'Mua sắm' present).

---

### TC_CT_VTL_003 — keyword='Bệnh viện' → empty content list
1. Client → GET `/api/v1/manager/conversation/themes?keyword=Bệnh viện` with a valid MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationThemeController → call `ConversationThemeService.getThemes()` with keyword filter.
4. ConversationThemeService → retrieve themes matching the keyword — none found.
5. System → return the paginated theme list response (totalElements=0, content=[]).

---

## Section 2 — Create Theme `POST /api/v1/manager/conversation/themes` [P2 Standard]

### TC_CT_CTH_001 — title only → HTTP 201, DB: DRAFT, orderIndex=0, optional fields null
1. Client → POST `/api/v1/manager/conversation/themes` with a valid MANAGER Bearer token and `{"title":"Mua sắm"}`.
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationThemeController → validate the request body and call `ConversationThemeService.createTheme()`.
4. ConversationThemeService → create a new theme with default status=DRAFT and orderIndex=0.
5. ConversationThemeService → persist the theme to the database (optional fields null, deleted_at null).
6. System → return HTTP 201 with the created theme response.

---

### TC_CT_CTH_002 — all optional fields → HTTP 201, all fields persisted to DB
1. Client → POST `/api/v1/manager/conversation/themes` with a valid MANAGER Bearer token and all fields (title, description, titleJapanese, category, jlptLevel, orderIndex).
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationThemeController → validate the request body and call `ConversationThemeService.createTheme()`.
4. ConversationThemeService → create a new theme with all provided fields.
5. ConversationThemeService → persist the theme to the database.
6. System → return HTTP 201 with the created theme response (all fields reflected).

---

### TC_CT_CTH_003 — duplicate title → both HTTP 201, 2 distinct records in DB
1. Client → POST `/api/v1/manager/conversation/themes` with title "Gia đình" (first request).
2. ConversationThemeController → call `ConversationThemeService.createTheme()`.
3. ConversationThemeService → create and persist the first theme (no unique constraint on title).
4. System → return HTTP 201 with the first created theme response.
5. Client → POST `/api/v1/manager/conversation/themes` with the same title "Gia đình" (second request).
6. ConversationThemeController → call `ConversationThemeService.createTheme()`.
7. ConversationThemeService → create and persist the second theme with a distinct UUID.
8. System → return HTTP 201 with the second created theme response (two distinct records in DB).

---

### TC_CT_CTH_004 — blank title → HTTP 400 VALIDATION_ERROR, data.title error present
1. Client → POST `/api/v1/manager/conversation/themes` with a valid MANAGER Bearer token and `{"title":""}`.
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationThemeController → validate the request body.
4. ConversationThemeController → detect that the title field violates @NotBlank constraint.
5. System → return HTTP 400 VALIDATION_ERROR response before reaching ConversationThemeService (data.title error present).

---

### TC_CT_CTH_005 — invalid enum 'N9' → HTTP 400 VALIDATION_ERROR, message contains 'N9'
1. Client → POST `/api/v1/manager/conversation/themes` with a valid MANAGER Bearer token and `{"title":"Test Theme","jlptLevel":"N9"}`.
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationThemeController → attempt to deserialize the request body.
4. Jackson → fail to deserialize "N9" into Course.JlptLevel (HttpMessageNotReadableException, fires before Bean Validation).
5. System → return HTTP 400 VALIDATION_ERROR response (message contains "N9", data=null).

---

### TC_CT_CTH_006 — no token → HTTP 401 Unauthorized
1. Client → POST `/api/v1/manager/conversation/themes` with no Authorization header.
2. AuthFilter → detect that no token is present.
3. AuthFilter → reject the request as unauthenticated.
4. System → return HTTP 401 Unauthorized response.

---

### TC_CT_CTH_007 — LEARNER creates theme → HTTP 403 Forbidden
1. Client → POST `/api/v1/manager/conversation/themes` with a valid LEARNER Bearer token.
2. AuthFilter → parse the token and resolve the user identity (role=LEARNER).
3. AuthFilter → detect that the LEARNER role is not authorized for this endpoint.
4. System → return HTTP 403 Forbidden response.

---

## Section 3 — Update Theme `PUT /api/v1/manager/conversation/themes/{id}` [P2 Standard]

### TC_CT_UTH_001 — update title+description → HTTP 200, DB updated, updatedAt refreshed
1. Client → PUT `/api/v1/manager/conversation/themes/{id}` with a valid MANAGER Bearer token and `{"title":"New Title","description":"New Desc"}`.
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationThemeController → validate the request body and call `ConversationThemeService.updateTheme()`.
4. ConversationThemeService → retrieve the theme by ID (deleted_at IS NULL).
5. ConversationThemeService → apply the title and description changes.
6. ConversationThemeService → persist the updated theme (updatedAt refreshed).
7. System → return HTTP 200 with the updated theme response.

---

### TC_CT_UTH_002 — optional fields only → HTTP 200, title unchanged in DB
1. Client → PUT `/api/v1/manager/conversation/themes/{id}` with a valid MANAGER Bearer token and `{"titleJapanese":"家族","jlptLevel":"N4"}`.
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationThemeController → validate the request body and call `ConversationThemeService.updateTheme()`.
4. ConversationThemeService → retrieve the theme by ID (deleted_at IS NULL).
5. ConversationThemeService → apply titleJapanese and jlptLevel changes without altering the original title.
6. ConversationThemeService → persist the updated theme.
7. System → return HTTP 200 with the updated theme response (original title unchanged).

---

### TC_CT_UTH_003 — blank title → HTTP 400 VALIDATION_ERROR, message='Title must not be blank'
1. Client → PUT `/api/v1/manager/conversation/themes/{id}` with a valid MANAGER Bearer token and `{"title":""}`.
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationThemeController → call `ConversationThemeService.updateTheme()`.
4. ConversationThemeService → detect that the provided title is blank (BusinessException).
5. System → return HTTP 400 VALIDATION_ERROR response (data=null, message='Title must not be blank').

---

### TC_CT_UTH_004 — non-existent themeId → HTTP 404, code='NOT_FOUND'
1. Client → PUT `/api/v1/manager/conversation/themes/00000000-0000-0000-0000-000000000000` with a valid MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationThemeController → call `ConversationThemeService.updateTheme()`.
4. ConversationThemeService → attempt to retrieve the theme by ID.
5. ConversationThemeService → detect that no theme with the given ID exists.
6. System → return HTTP 404 NOT_FOUND response.

---

### TC_CT_UTH_005 — soft-deleted theme → HTTP 404, code='NOT_FOUND'
1. Client → PUT `/api/v1/manager/conversation/themes/{id}` with a valid MANAGER Bearer token (theme is soft-deleted).
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationThemeController → call `ConversationThemeService.updateTheme()`.
4. ConversationThemeService → attempt to retrieve the theme by ID (deleted_at IS NULL filter).
5. ConversationThemeService → detect that the theme has been soft-deleted (not found).
6. System → return HTTP 404 NOT_FOUND response.

---

### TC_CT_UTH_006 — no token → HTTP 401 Unauthorized
1. Client → PUT `/api/v1/manager/conversation/themes/{id}` with no Authorization header.
2. AuthFilter → detect that no token is present.
3. AuthFilter → reject the request as unauthenticated.
4. System → return HTTP 401 Unauthorized response.

---

### TC_CT_UTH_007 — LEARNER updates theme → HTTP 403 Forbidden
1. Client → PUT `/api/v1/manager/conversation/themes/{id}` with a valid LEARNER Bearer token.
2. AuthFilter → parse the token and resolve the user identity (role=LEARNER).
3. AuthFilter → detect that the LEARNER role is not authorized for this endpoint.
4. System → return HTTP 403 Forbidden response.

---

## Section 4 — Delete Theme `DELETE /api/v1/manager/conversation/themes/{id}` [P2 Standard]

### TC_CT_DTH_001 — delete theme (no topics) → HTTP 200, deleted_at set, absent from list
1. Client → DELETE `/api/v1/manager/conversation/themes/{id}` with a valid MANAGER Bearer token (theme has no topics).
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationThemeController → call `ConversationThemeService.deleteTheme()`.
4. ConversationThemeService → retrieve the theme by ID (deleted_at IS NULL).
5. ConversationThemeService → set deleted_at and deleted_by (manager UUID) on the theme.
6. ConversationThemeService → persist the soft-deleted theme.
7. System → return HTTP 200 success response ("Nội dung đã được xoá"); theme absent from subsequent list queries.

---

### TC_CT_DTH_002 — mixed topics → active cascade-deleted, pre-deleted unchanged
1. Client → DELETE `/api/v1/manager/conversation/themes/{id}` with a valid MANAGER Bearer token (theme has 2 active topics + 1 pre-deleted topic).
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationThemeController → call `ConversationThemeService.deleteTheme()`.
4. ConversationThemeService → retrieve the theme by ID (deleted_at IS NULL).
5. ConversationThemeService → cascade-delete all active child topics (set deleted_at and deleted_by = manager UUID).
6. ConversationThemeService → skip the pre-deleted topic (deleted_at already set — NOT overwritten).
7. ConversationThemeService → soft-delete the theme itself.
8. System → return HTTP 200 success response; all active topics cascade-deleted, pre-deleted topic's deleted_at unchanged.

---

### TC_CT_DTH_003 — non-existent themeId → HTTP 404, code='NOT_FOUND'
1. Client → DELETE `/api/v1/manager/conversation/themes/00000000-0000-0000-0000-000000000000` with a valid MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationThemeController → call `ConversationThemeService.deleteTheme()`.
4. ConversationThemeService → attempt to retrieve the theme by ID.
5. ConversationThemeService → detect that no theme with the given ID exists.
6. System → return HTTP 404 NOT_FOUND response.

---

### TC_CT_DTH_004 — already-deleted theme → HTTP 404, code='NOT_FOUND'
1. Client → DELETE `/api/v1/manager/conversation/themes/{id}` with a valid MANAGER Bearer token (theme already soft-deleted).
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationThemeController → call `ConversationThemeService.deleteTheme()`.
4. ConversationThemeService → attempt to retrieve the theme by ID (deleted_at IS NULL filter).
5. ConversationThemeService → detect that the theme is already soft-deleted (not found).
6. System → return HTTP 404 NOT_FOUND response.

---

### TC_CT_DTH_005 — no token → HTTP 401 Unauthorized
1. Client → DELETE `/api/v1/manager/conversation/themes/{id}` with no Authorization header.
2. AuthFilter → detect that no token is present.
3. AuthFilter → reject the request as unauthenticated.
4. System → return HTTP 401 Unauthorized response.

---

### TC_CT_DTH_006 — LEARNER deletes theme → HTTP 403 Forbidden
1. Client → DELETE `/api/v1/manager/conversation/themes/{id}` with a valid LEARNER Bearer token.
2. AuthFilter → parse the token and resolve the user identity (role=LEARNER).
3. AuthFilter → detect that the LEARNER role is not authorized for this endpoint.
4. System → return HTTP 403 Forbidden response.

---

## Section 5 — View Theme Detail `GET /api/v1/manager/conversation/themes/{id}` [P3 Sanity]

### TC_CT_VTI_001 — view detail → HTTP 200, id/title/status/createdAt/updatedAt/topicCount present
1. Client → GET `/api/v1/manager/conversation/themes/{id}` with a valid MANAGER Bearer token.
2. AuthFilter → parse the token and resolve the user identity.
3. ConversationThemeController → call `ConversationThemeService.getThemeById()`.
4. ConversationThemeService → retrieve the theme by ID (deleted_at IS NULL).
5. ConversationThemeService → build the ConversationThemeResponse with all required fields.
6. System → return HTTP 200 with the theme detail response (id, title, status, createdAt, updatedAt, topicCount present).


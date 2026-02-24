# Use Case Specification: Conversation Topic Management

## Document Information

| Field   | Value                                            |
| ------- | ------------------------------------------------ |
| Module  | Conversation                                     |
| Version | 1.0                                              |
| Scope   | Manager-managed conversation topics (chủ đề nhỏ) |

---

## Actors

| Actor   | Description                                                               |
| ------- | ------------------------------------------------------------------------- |
| Manager | Authenticated user with `ROLE_MANAGER`. Manages all conversation content. |
| System  | Backend application performing persistence and validation.                |

---

## UC-01: Create Conversation Topic

**Use Case ID:** UC-TOPIC-01
**Name:** Create Conversation Topic
**Actor:** Manager
**Endpoint:** `POST /api/v1/manager/conversation/topics`

### Preconditions

- Manager is authenticated (valid JWT with `ROLE_MANAGER`).
- `title` field is provided and non-blank.

### Main Flow

| Step | Actor   | Action                                                                                                                                               |
| ---- | ------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1    | Manager | Sends POST request with `CreateTopicRequest` body (title required; themeId, courseId, scenario, vocabularyHints, grammarPoints, jlptLevel optional). |
| 2    | System  | Validates request (`@Valid`): title not blank, size constraints.                                                                                     |
| 3    | System  | Maps request to `ConversationTopic` entity via `ConversationTopicMapper.toEntity()`.                                                                 |
| 4    | System  | Sets `createdBy` and `updatedBy` to authenticated `managerId`.                                                                                       |
| 5    | System  | Persists entity to `speaking.conversation_topics`. Default `status = DRAFT`.                                                                         |
| 6    | System  | Resolves `courseName` from `CourseRepository` if `courseId` is present.                                                                              |
| 7    | System  | Returns `201 Created` with `ConversationTopicResponse`.                                                                                              |

### Alternative Flows

| Step | Condition                           | Outcome                                                  |
| ---- | ----------------------------------- | -------------------------------------------------------- |
| 2a   | `title` is blank or missing         | Returns `400 Bad Request` with validation error details. |
| 6a   | `courseId` set but course not found | `courseName` in response is `null`; no error thrown.     |

### Postconditions

- New `ConversationTopic` row persists with `status = DRAFT`, `deletedAt = NULL`.
- `createdBy` and `updatedBy` recorded for audit.

---

## UC-02: Get Conversation Topic by ID

**Use Case ID:** UC-TOPIC-02
**Name:** Get Conversation Topic
**Actor:** Manager
**Endpoint:** `GET /api/v1/manager/conversation/topics/{topicId}`

### Preconditions

- Manager is authenticated.
- `topicId` is a valid UUID.

### Main Flow

| Step | Actor   | Action                                                                  |
| ---- | ------- | ----------------------------------------------------------------------- |
| 1    | Manager | Sends GET request with `topicId` as path variable.                      |
| 2    | System  | Loads `ConversationTopic` from repository by `topicId`.                 |
| 3    | System  | Verifies `deletedAt IS NULL` (record is active).                        |
| 4    | System  | Resolves `courseName` from `CourseRepository` if `courseId` is present. |
| 5    | System  | Returns `200 OK` with full `ConversationTopicResponse`.                 |

### Alternative Flows

| Step | Condition                              | Outcome                                                |
| ---- | -------------------------------------- | ------------------------------------------------------ |
| 3a   | Topic not found or `deletedAt != NULL` | Returns `404 Not Found` (`ResourceNotFoundException`). |

---

## UC-03: List All Topics (Paginated)

**Use Case ID:** UC-TOPIC-03
**Name:** List All Conversation Topics
**Actor:** Manager
**Endpoint:** `GET /api/v1/manager/conversation/topics?page=0&size=10`

### Preconditions

- Manager is authenticated.

### Main Flow

| Step | Actor   | Action                                                                                       |
| ---- | ------- | -------------------------------------------------------------------------------------------- |
| 1    | Manager | Sends GET request with optional `page` and `size` query parameters.                          |
| 2    | System  | Applies `Pageable` with default `Sort.by("orderIndex").ascending()`, max size capped at 100. |
| 3    | System  | Queries `ConversationTopicRepository.findByDeletedAtIsNull(pageable)`.                       |
| 4    | System  | Maps each result to `ConversationTopicSummaryResponse`.                                      |
| 5    | System  | Returns `200 OK` with `PageResponse<ConversationTopicSummaryResponse>`.                      |

### Data Returned Per Topic

`id`, `themeId`, `title`, `titleJapanese`, `category`, `jlptLevel`, `difficultyScore`, `isRoleplay`, `thumbnailUrl`, `status`, `orderIndex`

---

## UC-04: List Topics by Theme

**Use Case ID:** UC-TOPIC-04
**Name:** List Topics Belonging to a Theme
**Actor:** Manager
**Endpoint:** `GET /api/v1/manager/conversation/themes/{themeId}/topics?page=0&size=10`

### Preconditions

- Manager is authenticated.
- `themeId` is a valid UUID.

### Main Flow

| Step | Actor   | Action                                                                                    |
| ---- | ------- | ----------------------------------------------------------------------------------------- |
| 1    | Manager | Sends GET with `themeId` and optional pagination params.                                  |
| 2    | System  | Queries `ConversationTopicRepository.findByThemeIdAndDeletedAtIsNull(themeId, pageable)`. |
| 3    | System  | Returns `200 OK` with `PageResponse<ConversationTopicSummaryResponse>`.                   |

### Notes

- Returns empty page (not 404) if `themeId` has no topics.
- Does not validate existence of the parent theme in this flow.

---

## UC-05: Update Conversation Topic

**Use Case ID:** UC-TOPIC-05
**Name:** Update Conversation Topic
**Actor:** Manager
**Endpoint:** `PUT /api/v1/manager/conversation/topics/{topicId}`

### Preconditions

- Manager is authenticated.
- Topic with `topicId` exists and is not soft-deleted.

### Main Flow

| Step | Actor   | Action                                                                                                                              |
| ---- | ------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| 1    | Manager | Sends PUT with `topicId` and `UpdateTopicRequest` body (all fields optional).                                                       |
| 2    | System  | Validates request (size and range constraints).                                                                                     |
| 3    | System  | Loads and verifies active topic (see UC-02 steps 2-3).                                                                              |
| 4    | System  | Applies changes via `ConversationTopicMapper.updateEntity()` (null fields are ignored — `NullValuePropertyMappingStrategy.IGNORE`). |
| 5    | System  | Sets `updatedBy = managerId`.                                                                                                       |
| 6    | System  | Persists updated entity.                                                                                                            |
| 7    | System  | Returns `200 OK` with updated `ConversationTopicResponse`.                                                                          |

### Alternative Flows

| Step | Condition                         | Outcome                    |
| ---- | --------------------------------- | -------------------------- |
| 3a   | Topic not found or soft-deleted   | Returns `404 Not Found`.   |
| 2a   | `difficultyScore` outside [1, 10] | Returns `400 Bad Request`. |

### Notes

- `themeId` is **immutable** after creation (ignored in update mapper).
- `status` is **immutable** via this endpoint; use the publish/archive endpoints.

---

## UC-06: Publish Conversation Topic

**Use Case ID:** UC-TOPIC-06
**Name:** Publish Conversation Topic
**Actor:** Manager
**Endpoint:** `PATCH /api/v1/manager/conversation/topics/{topicId}/publish`

### Preconditions

- Manager is authenticated.
- Topic exists and is not soft-deleted.

### Main Flow

| Step | Actor   | Action                                                                  |
| ---- | ------- | ----------------------------------------------------------------------- |
| 1    | Manager | Sends PATCH to publish endpoint.                                        |
| 2    | System  | Loads active topic.                                                     |
| 3    | System  | Sets `status = PUBLISHED`, `updatedBy = managerId`.                     |
| 4    | System  | Persists and returns `200 OK` with updated `ConversationTopicResponse`. |

### Postconditions

- Topic becomes visible to learners via `GET /api/v1/learner/conversation/themes/{themeId}/topics`.

---

## UC-07: Delete (Soft-Delete) Conversation Topic

**Use Case ID:** UC-TOPIC-07
**Name:** Delete Conversation Topic
**Actor:** Manager
**Endpoint:** `DELETE /api/v1/manager/conversation/topics/{topicId}`

### Preconditions

- Manager is authenticated.
- Topic exists and is not already soft-deleted.

### Main Flow

| Step | Actor   | Action                                                           |
| ---- | ------- | ---------------------------------------------------------------- |
| 1    | Manager | Sends DELETE request with `topicId`.                             |
| 2    | System  | Loads active topic.                                              |
| 3    | System  | Sets `deletedAt = LocalDateTime.now()`, `deletedBy = managerId`. |
| 4    | System  | Persists (soft delete — row is NOT physically removed from DB).  |
| 5    | System  | Returns `200 OK` with `ApiResponse<Void>` and success message.   |

### Alternative Flows

| Step | Condition                  | Outcome                                                     |
| ---- | -------------------------- | ----------------------------------------------------------- |
| 2a   | Topic not found            | Returns `404 Not Found`.                                    |
| 2b   | Topic already soft-deleted | Returns `404 Not Found` (treated identically to not found). |

### Postconditions

- Topic no longer appears in any listing (`findByDeletedAtIsNull` excludes it).
- Data is retained in the database for audit purposes.

---

## Business Rules

| Rule ID | Description                                                                                           |
| ------- | ----------------------------------------------------------------------------------------------------- |
| BR-01   | Only users with `ROLE_MANAGER` can create, update, publish, or delete topics.                         |
| BR-02   | `title` is mandatory for topic creation; max 255 characters.                                          |
| BR-03   | New topics default to `status = DRAFT` and are not visible to learners until published.               |
| BR-04   | `themeId` is set at creation and cannot be changed via update.                                        |
| BR-05   | `status` can only be changed via dedicated publish/archive endpoints, not through the general update. |
| BR-06   | Soft-deleted topics are logically invisible but physically retained.                                  |
| BR-07   | `difficultyScore` must be in the range [1, 10].                                                       |
| BR-08   | `courseId` on a topic provides vocabulary/grammar context; it is optional and nullable.               |

---

## Error Responses

| HTTP Status | Error Code       | Trigger Condition                                                             |
| ----------- | ---------------- | ----------------------------------------------------------------------------- |
| 400         | VALIDATION_ERROR | Missing `title`, constraint violations (`@NotBlank`, `@Size`, `@Min`/`@Max`). |
| 401         | UNAUTHORIZED     | No or invalid JWT token.                                                      |
| 403         | FORBIDDEN        | Authenticated user lacks `ROLE_MANAGER`.                                      |
| 404         | NOT_FOUND        | Topic does not exist or has been soft-deleted.                                |

---

## Data Model Reference

```
speaking.conversation_topics
├── id             UUID           PK
├── theme_id       UUID           FK → conversation_themes.id (nullable)
├── course_id      UUID           FK → courses.id (nullable)
├── title          VARCHAR(255)   NOT NULL
├── title_japanese VARCHAR(255)
├── description    TEXT
├── scenario       TEXT
├── vocabulary_hints  JSONB       default '[]'
├── grammar_points    JSONB       default '[]'
├── useful_expressions JSONB      default '[]'
├── category       VARCHAR(100)
├── jlpt_level     jlpt_level     ENUM(N5,N4,N3,N2,N1)
├── difficulty_score INTEGER      default 1
├── is_roleplay    BOOLEAN        default false
├── roleplay_context JSONB
├── thumbnail_url  TEXT
├── status         content_status ENUM(DRAFT,PUBLISHED,ARCHIVED) default 'DRAFT'
├── order_index    INTEGER        default 0
├── created_at     TIMESTAMP
├── updated_at     TIMESTAMP
├── created_by     UUID
├── updated_by     UUID
├── deleted_at     TIMESTAMP      NULL = active
└── deleted_by     UUID
```

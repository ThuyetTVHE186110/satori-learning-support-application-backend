# CLAUDE.md — Satori Learning Support Application (Backend)

A multi-module Spring Boot monorepo providing REST APIs for a Japanese-language learning platform with AI-assisted conversation, roleplay, curriculum import, and assignment management.

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language / Runtime | Java 21 |
| Framework | Spring Boot 3.3 |
| Build | Maven multi-module (`pom.xml` at root) |
| Primary DB | PostgreSQL (with schemas per domain) |
| Cache / Session | Redis |
| ORM | Spring Data JPA / Hibernate |
| Security | Spring Security 6 — stateless JWT (BCrypt passwords) |
| External AI | OpenAI (quiz generation, explanations, conversation, roleplay) |
| External Storage | Cloudinary (images, audio) |
| OCR | Google Vision API (curriculum PDF ingestion) |
| Pronunciation | Azure Cognitive Services Speech → SpeechSuper (fallback) |
| Google Auth | Firebase Admin SDK (Google OAuth token verification) |
| Email | Spring Mail (SMTP — password reset OTPs) |
| API Docs | SpringDoc OpenAPI / Swagger UI (`/swagger-ui/**`) |

---

## Module Map

```
root/
├── common/            Shared ApiResponse<T>, PageResponse<T>, GlobalExceptionHandler,
│                      custom validators (@NoEmoji, @SafeText, @ValidJson), PromptUtils
├── infrastructure/    Security (SecurityConfig, JwtAuthFilter), Redis, Cloudinary,
│                      Email, base JPA entities (BaseEntity, AuditableEntity, SoftDeletableEntity)
├── modules/
│   ├── user/          Users, roles, sessions, JWT auth, Google OAuth, OTP/password reset, profiles
│   ├── class/         Class CRUD, member management; OPERATION_MANAGER full access, TEACHER read-only own classes
│   ├── course/        Courses, Lessons, Vocabulary, GrammarPoints; CONTENT_MANAGER manage, public browse
│   ├── assignment/    QUIZ/WRITING/TRANSLATION assignments, AI quiz generation, learner submissions
│   ├── conversation/  Conversation Themes, Topics, Missions; AI-generated context; learner personal topics
│   ├── roleplay/      Roleplay sessions (topic-based + free-talk), mission evaluation, pronunciation scoring
│   ├── curriculum-import/ Async 7-stage PDF→Lesson pipeline (OCR→chunk→classify→extract→validate→embed→persist)
│   └── pronunciation/ Pronunciation provider abstraction (Azure primary, SpeechSuper fallback)
└── start/             StartApplication — component scan root, integration test boot
```

---

## API Base Path

All endpoints are under `/api/v1/`.

| Segment | Access |
|---------|--------|
| `/api/v1/auth/**` | Public (login, Google, forgot-password, OTP, refresh) |
| `/api/v1/courses/**` (GET only) | Public (no auth required) |
| `/api/v1/manager/**` | CONTENT_MANAGER (courses, lessons, vocab, grammar, conversation themes/topics/missions, curriculum import, roleplay missions) |
| `/api/v1/users/**` | ADMIN only |
| `/api/v1/classes/**` | OPERATION_MANAGER (full CRUD) + TEACHER (read-only own classes) |
| `/api/v1/teacher/assignments/**` | TEACHER |
| `/api/v1/learner/**` | LEARNER |
| `/api/v1/profile/**` | Any authenticated user |

---

## Key Conventions

### Naming Patterns
- Controllers: `{Role}{Domain}Controller` (e.g. `ManagerCourseController`, `LearnerAssignmentController`)
- Services: `{Domain}Service` interface + `{Domain}ServiceImpl` implementation
- DTOs: `Create{X}Request`, `Update{X}Request`, `{X}Response`, `{X}SummaryResponse`
- Mappers: `{Domain}Mapper` (MapStruct-style, NullValuePropertyMappingStrategy applies — partial updates use nullable checks in service layer)
- Exceptions: `{Domain}NotFoundException extends ResourceNotFoundException`, `BusinessException` for rule violations

### Soft Delete Strategy
- `SoftDeletableEntity` provides `deletedAt` + `deletedBy` (UUID)
- Hard delete is only used for learner personal topics (`UserConversationTopic`)
- All queries filter `WHERE deleted_at IS NULL` by convention; Specifications enforce this
- `restore()` method clears both fields

### Error Handling
- `GlobalExceptionHandler` (`@RestControllerAdvice`) maps:
  - `ResourceNotFoundException` → 404
  - `BusinessException` → 400
  - `AccessDeniedException` → 403
  - `MethodArgumentNotValidException` → 400 with field map
  - `Exception` (catch-all) → 500
- Response shape always `ApiResponse<T>` `{ success, code, message, data }`
- Module-local exceptions extend global base classes; no per-module `@ControllerAdvice`

### Role / Auth System
- `UserRole` enum: `LEARNER`, `TEACHER`, `CONTENT_MANAGER`, `OPERATION_MANAGER`, `ADMIN`
  - `CONTENT_MANAGER` — manages learning content: courses, lessons, vocabulary, grammar points, conversation themes/topics/missions, roleplay missions, curriculum imports
  - `OPERATION_MANAGER` — manages operational aspects: classes (full CRUD), class member management
  - `ADMIN` — full access to user management (`/api/v1/users/**`); can create/update/delete any user
- `UserStatus` enum: `ACTIVE`, `INACTIVE`, `PENDING_ACTIVATION`
- JWT tokens stored as Redis sessions keyed by `sessionId` (UUID); `UserSession` entity tracks device info
- `@PreAuthorize("hasRole('CONTENT_MANAGER')")` or `@PreAuthorize("hasRole('OPERATION_MANAGER')")` on class or method; `@EnableMethodSecurity` active
- `AuthenticationPrincipal` resolves to `UUID` (user id) when `@AuthenticationPrincipal(expression = "id")` is used, or `CustomUserDetails` otherwise
- `ClassController.isManager()` helper checks for `ROLE_OPERATION_MANAGER` authority from `SecurityContextHolder`

### Specification Pattern
- `CourseSpecification`, `ClassSpecification`, `AssignmentSpecification` implement `JpaSpecificationExecutor` for dynamic filters
- Filters combine with AND; always include `deletedAt IS NULL` predicate

### PostgreSQL Enum Mapping
- Enums stored as PostgreSQL named types (e.g. `user_role`, `jlpt_level`, `course_status`)
- Annotated with `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` on the entity field
- Schema SQL must define matching `CREATE TYPE ... AS ENUM` before table creation

### Pagination
- `PageResponse<T>` wraps Spring `Page<T>` into `{ content, page, size, totalElements, totalPages }`
- Teacher/Learner assignment controllers use **1-based** page numbering; all others use **0-based**

### Async / Background Work
- Curriculum import uses `importTaskExecutor` (configured in `AsyncImportConfig`)
- Roleplay parallel tasks (AI response, mission eval, pronunciation) use `roleplayTaskExecutor` (`RoleplayAsyncConfig`)
- Import retry/cleanup via `ImportRetryScheduler` (`@Scheduled`)
- Event-driven embedding: `ContentEmbeddingEvent` → `ContentEmbeddingEventListener`

### DB Schema Segmentation
| Schema | Tables | Role(-s) with write access |
|--------|--------|---------------------------|
| `user_management` | users, user_sessions, user_learning_preferences | ADMIN (user CRUD), any authenticated (own session/profile) |
| `learning_content` | courses, lessons, vocabulary, grammar_points, lesson_vocabulary, lesson_grammar | CONTENT_MANAGER |
| `class_management` | classes, class_members | OPERATION_MANAGER |
| `assignment` | assignments, assignment_questions, assignment_submissions, assignment_targets, writing_assignments, quiz_questions | TEACHER (create/manage), LEARNER (submissions) |
| `speaking` | conversation_themes, conversation_topics, conversation_missions, user_conversation_topics | CONTENT_MANAGER (themes/topics/missions); LEARNER (personal topics) |
| `roleplay` | roleplay_sessions, roleplay_messages, session_missions, message_pronunciations, roleplay_missions | CONTENT_MANAGER (missions), LEARNER (sessions) |
| `curriculum_import` | curriculum_imports, import_chunks | CONTENT_MANAGER |

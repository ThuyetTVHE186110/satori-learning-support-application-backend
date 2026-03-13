# AGENTS.md

## Project Snapshot
- Java 21, Spring Boot 3.3, Maven multi-module monorepo (`pom.xml` at root).
- Runtime app is `start` module; `com.fu.StartApplication` scans all modules (`start/src/main/java/com/fu/StartApplication.java`).
- Modules are feature-oriented (`modules/user`, `modules/course`, `modules/conversation`, `modules/roleplay`, etc.) plus shared `common` and `infrastructure`.

## Architecture and Boundaries
- `common`: shared API envelope and exception handling (`common/src/main/java/com/fu/common/response/ApiResponse.java`, `common/src/main/java/com/fu/common/exception/GlobalExceptionHandler.java`).
- `infrastructure`: cross-cutting config (security, Redis cache, persistence base classes, email, external clients).
- Feature modules own controller/service/repository/entity stacks; `start` composes them.
- Security model is path + role based in `infrastructure/src/main/java/com/fu/infrastructure/config/SecurityConfig.java`:
  - public auth endpoints under `/api/v1/auth/**`
  - manager endpoints under `/api/v1/manager/**`
  - authenticated-by-default fallback.

## Data and Persistence Patterns
- PostgreSQL + Redis are first-class dependencies (`environment/DOCKER_README.md`).
- Soft delete is pervasive via `SoftDeletableEntity` and `deletedAt/deletedBy`; queries usually filter `deletedAt IS NULL`.
- PostgreSQL enums are used in entities (`@JdbcTypeCode(SqlTypes.NAMED_ENUM)`), so schema SQL must match enum names.
- DB bootstrap/migrations are scripted in `environment/docker/init-scripts/postgres/` (ordered SQL files).

## High-Impact Flows
- Curriculum import is an async 7-stage pipeline (ingest -> chunk -> classify -> AI extract -> validate -> embed -> persist) in `modules/curriculum-import/.../ImportOrchestrator.java`.
- Retry/cleanup are scheduler-driven (`ImportRetryScheduler`) and use `importTaskExecutor` (`AsyncImportConfig`).
- Roleplay sessions run parallel async tasks (AI response, mission evaluation, pronunciation) in `modules/roleplay/.../RoleplaySessionServiceImpl.java` with `roleplayTaskExecutor`.
- Pronunciation provider strategy is Azure -> SpeechSuper fallback, plus optional Japanese sidecar merge (`CompositePronunciationProvider`).

## Build and Run Workflows
```powershell
mvn clean verify
mvn -pl start -am spring-boot:run
mvn -pl modules/user -am test
mvn -pl modules/curriculum-import -am test
```

```powershell
cd environment
docker-compose up -d
docker-compose ps
```

## Testing Conventions (Important)
- Many controller tests are `@WebMvcTest` with module test configs that exclude DB/Redis auto-config (`UserModuleTestConfig`, `ConversationModuleTestConfig`).
- Some integration tests require module-specific test bootstraps with full entity scan (`UserTestApplication`, `ConversationTestApplication`).
- Cross-module integration tests may run from `start` to load all modules (example: `start/src/test/java/com/fu/conversation/ConversationTopicManagementTest.java`).
- `start/src/test/resources/application-test.yml` expects a real PostgreSQL `satori_test` DB initialized via `init-test-db.sql`.

## Agent Guidelines for This Repo
- Preserve response shape: controllers should return `ApiResponse<T>` and rely on centralized exception mapping.
- Respect soft-delete semantics; avoid hard deletes unless a repository/service explicitly calls for it.
- When adding endpoints, align path style with existing role segmentation (`/api/v1/manager/...`, `/api/v1/auth/...`, learner/public split).
- Prefer module-local changes first; only move to `common`/`infrastructure` when behavior is truly cross-cutting.
- For new async/background logic, wire a dedicated executor/config bean rather than reusing unrelated thread pools.
- Treat credentials in config files as local/dev placeholders; use env overrides and avoid introducing new secrets in source.

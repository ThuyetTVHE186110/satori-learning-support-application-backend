# Satori — AI-Powered Japanese Learning Platform (Backend)

Backend for **Satori**, a Japanese-language learning platform for Vietnamese learners, built as my graduation thesis at FPT University (Jan–Apr 2026). It serves web and mobile clients with courses, classes, assignments, AI conversation and role-play practice, pronunciation scoring, and an AI pipeline that turns teaching materials into structured lessons.

**Stack:** Java 21 · Spring Boot 3.3 · Spring AI 1.0 (OpenAI) · PostgreSQL + pgvector · Redis · Kuromoji · Azure Speech · Google Document AI / Vision · Docker · GitHub Actions

---

## Architecture

A **modular monolith**: one deployable application, split into Maven modules with clear boundaries and a **schema per module** in PostgreSQL. Each module can later be extracted into a service without rewriting its domain logic.

```
start/            Application entry point and environment profiles (dev, systest, uat, prod)
common/           Shared kernel: web, security, validation, Japanese text utilities (Kuromoji)
infrastructure/   Cross-cutting adapters: JPA, Redis cache, pgvector, Spring AI, actuator
modules/
  user/             Accounts, JWT authentication, roles, auth rate limiting
  course/ class/    Course catalogue, classes and enrolment
  assignment/       Assignments, OCR of submitted work, AI-assisted grading
  practice/         Practice exercises and progress
  conversation/     Agentic RAG curriculum chatbot (advisor chain, tools, evaluation)
  roleplay/         Scenario role-play with grammar/clause evaluation of learner answers
  pronunciation/    Pronunciation scoring via Azure Speech (pluggable providers)
  curriculum-import/ Multi-agent pipeline: teaching materials -> structured lessons
  achievement/      Gamification and achievements
  notification/     Email and push notifications
  analytics/ monitoring/  Learning analytics and system health
environment/      Dockerfile, Docker Compose per environment, DB init scripts
```

PostgreSQL schemas: `user_management`, `learning_content`, `assignment`, `practice`, `progress`, `ai_conversation`, `speaking`, `pronunciation`, `gamification`, `notification`, `shop`, `system_admin`.

## AI features

### Curriculum chatbot: agentic RAG
A bilingual (Vietnamese / Japanese) chatbot that answers learners' questions from their own course material, built as a Spring AI advisor chain plus tool calling:

| Step | Component | What it does |
|---|---|---|
| 1 | `IntentRouterAdvisor` | Semantic intent classifier (exemplar-based, replacing an earlier regex filter) refuses off-topic questions before any retrieval or LLM cost |
| 2 | Chat memory | Conversation history per learner session |
| 3 | `RetrievalAdvisor` | One LLM call detects intent (COUNT / EXPLAIN / LOOKUP / EXAMPLE / GENERAL) and translates the query; COUNT questions go to **SQL** for exact numbers, others run **bilingual vector search** on pgvector (original + translated query merged by max similarity) with content-type filters and a quality gate on cosine similarity |
| 4 | `RelevanceCheckAdvisor` | Self-RAG style check: drops retrieved context that isn't actually relevant, so it can't mislead the answer |
| 5 | Tools (`CurriculumQueryTools`) | The model can call `searchCurriculum` and `countContent` itself; per-request course/lesson scope is passed via tool context, keeping the bean thread-safe |
| 6 | `GroundingAdvisor` | Corrective-RAG style check after generation: flags answers not supported by the retrieved curriculum |
| 7 | `RagEvaluator` | Asynchronous, RAGAS-inspired quality monitoring (faithfulness, context relevance, answer relevance) that runs after the user already has the answer |

Answers return their source lessons, and a separate streaming-safe client keeps the same guardrails for streamed responses.

### Curriculum import: a multi-agent pipeline with grounding checks
Turns uploaded teaching documents (PDFs, scans, spreadsheets) into structured vocabulary and grammar lessons:

1. **Document understanding:** Google Document AI / Vision for OCR and layout.
2. **Agents:** `ContentClassifierAgent` → `VocabularyExtractionAgent` / `GrammarExtractionAgent` → `ContentValidationAgent` → `SelfReflectionAgent`.
3. **`GroundingVerifier` (anti-hallucination):** every extracted item is cross-checked against the source text using exact match, fuzzy character match (for OCR noise) and kanji-level match, plus a check that the reading (furigana) appears near the term. Ungrounded items are rejected, not published.
4. **Embeddings:** stored in pgvector for semantic search over lesson content.

### Role-play practice
LLM-driven speaking scenarios through Spring AI. Learner answers are evaluated with **Kuromoji-based clause detection** to check grammatical structure, not just keywords.

### Pronunciation
Scoring through Azure Speech pronunciation assessment, behind a provider interface (`CompositePronunciationProvider`) so the scoring engine can be swapped.

## Engineering practices

- **CI/CD (GitHub Actions):** unit tests on every push; smoke system tests against real PostgreSQL + Redis; separate UAT and production deployment workflows; Docker images published to GHCR.
- **Environments:** `dev`, `systest`, `uat`, `prod` profiles, with secrets supplied via environment variables.
- **Security:** JWT authentication, Spring Security, per-endpoint auth rate limiting.
- **API docs:** OpenAPI / Swagger UI (springdoc).
- **Code quality:** MapStruct for mapping, Bean Validation, Actuator health checks, about 60 test classes covering agents, embeddings and evaluation logic.

## Running locally

Requirements: Java 21, Maven, Docker.

```bash
# start PostgreSQL (with pgvector) and Redis
cd environment && docker compose up -d

# run the application
cd .. && mvn -pl start -am spring-boot:run
```

Configuration (database, Redis, OpenAI, Azure Speech, Google Cloud, email) is read from environment variables; see `environment/DOCKER_README.md`.

## My role

Backend architect and main backend developer: designed the module structure and database schemas, built the AI pipelines (agentic RAG chatbot, curriculum import, grounding verification, role-play evaluation), and set up the Docker and CI/CD workflows.
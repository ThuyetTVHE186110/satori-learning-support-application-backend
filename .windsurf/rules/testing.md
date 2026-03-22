---
trigger: model_decision
description: Spring Boot backend rule. Use for API flow, debugging, or class analysis. Trace from Controller → Service → Repository. Identify DB, Redis, external calls. Do not assume missing logic; ask for files. Keep output concise and structured.
globs: 
---

# WHEN TO APPLY THESE RULES

Apply when:
- User asks about API flow, execution flow
- User asks about specific class/service/repository
- User is debugging backend issues (400, 500, unexpected behavior)

Do NOT apply when:
- General programming questions
- Non-project related topics

---

# PROJECT RULES

## Context Loading Strategy

- For high-level flow → read docs first
- For debugging / errors → go directly to code
- For specific class → open that class immediately

- Docs are for overview, not source of truth
- Code is always the source of truth

## Architecture
- Spring Boot layered architecture:
  Controller -> Service -> Repository
- Controller: handle request/response only
- Service: contains all business logic
- Repository: only database access

## Flow Tracing Strategy
When analyzing a flow:
1. Start from Controller (entry point)
2. Trace Service layer step-by-step
3. Identify:
   - DTO transformations
   - External calls (DB, Redis, APIs)
4. Do NOT skip intermediate methods
5. Do NOT assume missing logic → ask for file if needed

## Key Modules
- roleplay-service: handle AI conversation
- redis-cache: store session + prompt

## Cache
- Redis key format:
  session:{sessionId}:history
- Redis used for short-term conversation state

## External Services
- OpenAIClient handles AI requests

## Constraints
- Do NOT scan entire repository unless explicitly required
- Prefer minimal relevant files
- If missing context → ask user instead of guessing
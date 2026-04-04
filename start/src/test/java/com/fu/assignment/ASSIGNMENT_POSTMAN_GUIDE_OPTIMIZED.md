# Assignment — Postman Manual Testing Guide (Optimized)

> **Only manual Postman tests for scenarios that cannot be fully tested by integration/system tests.**
>
> Tests requiring **real external services** (Cloudinary, OpenAI) that need manual verification.

## Prerequisites

- Server running (`mvn -pl start -am spring-boot:run`)
- PostgreSQL + Redis started (`docker-compose up -d`)
- Valid Cloudinary credentials in `application.yml`
- Valid OpenAI API key in `application.yml`
- Teacher + Learner accounts (learner enrolled in class)

---

## 1. Cloudinary — Real File Upload ✅ KEEP

> **Why test in Postman?** Cannot fully mock Cloudinary in integration tests. Need to verify real file upload.

### PM_AS_CLOUD_001 — Upload audio when creating Quiz

```
POST /api/v1/teacher/assignments/class/{classId}/quiz
Content-Type: multipart/form-data
Header: Authorization: Bearer {teacher_token}

Part "data":
{
  "title": "Listening Quiz",
  "dueDate": "2026-12-31T23:59:59",
  "status": "DRAFT",
  "questions": []
}

Part "audioFile": select actual .mp3 file (3-5 MB)
```

**Verify:**
- [ ] Response 200, `data.audioUrl` contains valid Cloudinary URL
- [ ] Open URL in browser → audio plays
- [ ] Cloudinary dashboard → file exists in folder `satori/assignment-audio`

---

### PM_AS_CLOUD_002 — Upload images when Learner submits Writing

```
Step 1: POST /api/v1/learner/assignments/{id}/start  (start assignment first)

Step 2: Submit Writing:
POST /api/v1/learner/assignments/{assignmentId}/submit-writing
Content-Type: multipart/form-data
Header: Authorization: Bearer {learner_token}

Part "images": select 1-3 actual .png/.jpg files
```

**Verify:**
- [ ] Response 200, `data.imageUrls` is array of Cloudinary URLs
- [ ] Open each URL → images display correctly
- [ ] `data.status` = `"SUBMITTED"`

---

## 📌 Skipped Tests (Already Covered)

> ❌ **NO NEED** to test in Postman — already covered by integration & system tests:

### Why Skip PM_AS_AI_001 ~ PM_AS_AI_005?

**Already Tested In:**
- ✅ **AssignmentIntegrationTest** (mock OpenAI)
  - PM_AS_AI_001: Generate 3 questions → QuizAiGenerationServiceTest
  - PM_AS_AI_002: Mixed question types → validate logic
  - PM_AS_AI_003: Explain saved question → update DB
  - PM_AS_AI_004: Explain draft → no DB update
  - PM_AS_AI_005: Edge case (nonsense prompt) → error handling

- ✅ **System Test (UI)**
  - Real OpenAI API key verification
  - UX flow (form submission → result display)
  - Error messages rendering

**Postman Retest = Waste of:**
- 💰 API costs ($$ per call)
- ⏱️ Execution time (5-10s per call)
- 🔄 Redundant verification

---

### Why Skip PM_AS_E2E_001 ~ PM_AS_E2E_002?

**Already Tested In:**
- ✅ **AssignmentIntegrationTest.tc_as_t_cv_001()** etc.
  - Full Quiz flow (create → submit → grade)
  - Full Writing flow (create → submit images → grade)
  - All status transitions

- ✅ **System Test (UI)**
  - End-to-end user journey
  - UX validation
  - Real-world scenarios

**Postman Retest = Double testing:**
- Same flow logic
- Same database transitions
- Same response format

---

## 📊 Test Coverage Summary

| Layer | Test Type | Scope | Tools |
|-------|-----------|-------|-------|
| **Unit** | Logic testing | AI generation, grading, validation | JUnit + Mockito |
| **Integration** | Flow testing | AI (mock), DB, caching | AssignmentIntegrationTest |
| **E2E** | User journey | Full flows (Quiz, Writing) | AssignmentIntegrationTest |
| **System** | Real services | Real OpenAI, UI | Browser test |
| **API Manual** | External upload | Real Cloudinary upload | Postman ✅ |

---

## 🎯 Conclusion

**Keep Postman tests focused on:**
- ✅ **Real external file uploads** (Cloudinary)
- ❌ **Skip:** AI generation, E2E flows, status transitions

**This reduces:**
- Test execution time: 15+ min → 5-10 min
- API costs: Multiple OpenAI calls → None
- Maintenance burden: Fewer manual tests to keep in sync

---

## Testing Checklist

- [ ] **PM_AS_CLOUD_001**: Audio upload works + Cloudinary integration OK
- [ ] **PM_AS_CLOUD_002**: Image upload works + images display

Done! ✨


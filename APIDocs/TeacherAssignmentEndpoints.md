# Teacher Assignment API Reference

All endpoints require an authenticated user with the `TEACHER` role and are rooted at `https://<host>/api/v1/teacher/assignments`. Responses follow the shared envelope types:

- `ApiResponse<T>` → `{ "success": true, "code": "SUCCESS", "data": T, ... }`
- `PageResponse<T>` → `{ "content": [...], "page": 1, "size": 10, ... }`

## 1. AI Question Generation

### POST `/questions/ai-generate`
- **Purpose:** Generate quiz questions from an uploaded file or raw text.
- **Headers:** `Content-Type: multipart/form-data`
- **Body:**
  - `file` (optional `MultipartFile`): PDF/DOCX containing source text.
  - `request` (JSON, required): serialized `AiQuizGenerationRequest`
    - `teacherPrompt` *(string, required, 5-2000 chars)*
    - `questionCount` *(number, required)*
    - `questionTypes` *(array<string>, optional, default `['multiple_choice']`)*
    - `jlptLevel` *(enum, optional)*
    - `rawText` *(string, optional if `file` provided)*
    - `assignmentId` *(UUID, optional → auto-link questions)*
- **Response:** `ApiResponse<List<QuizQuestionResponse>>` (each question persisted as DRAFT).

### POST `/questions/{id}/ai-explain`
- **Purpose:** Ask AI to generate explanations for an existing quiz question.
- **Path Params:** `id` (question UUID)
- **Body:** none.
- **Response:** `ApiResponse<QuizQuestionResponse>` containing updated `aiExplanation` fields.

### POST `/questions/ai-explain-draft`
- **Purpose:** Generate explanation for a draft question without saving.
- **Body:** `AiExplanationRequest`
  - `questionText`, `questionType`, `options`, `correctAnswer`, etc.
- **Response:** `ApiResponse<AiExplanationResponse>` with `explanation` / `explanationVi` strings.

## 2. Full Assignment UPSERT (QUIZ / WRITING / TRANSLATION)

Each UPSERT endpoint saves metadata plus its content in one call. Provide `classId` in the path when creating, omit when updating.

### POST `/class/{classId}/quiz`
### PUT `/{id}/quiz`
- **Body:** `SaveFullQuizRequest`
  - Assignment fields: `title`, `description`, `instructions`, `dueDate`, `timeLimitMinutes`, `lateSubmissionAllowed`, `maxScore`, `passingScore`, `allowRetry`, `maxRetries`, etc.
  - `questions`: array of `QuizQuestionDraftDto` (each contains `id` optional, `questionText`, `questionType`, `options`, `correctAnswer`, `orderIndex`, `points`, etc.).
- **Response:** `ApiResponse<AssignmentResponse>` with synced question list.

### POST `/class/{classId}/writing`
### PUT `/{id}/writing`
### POST `/class/{classId}/translation`
### PUT `/{id}/translation`
- **Body:** `SaveFullWritingRequest`
  - Assignment metadata (same as quiz fields).
  - Writing-specific fields: `prompt`, `promptJapanese`, `sampleAnswer`, `sampleAnswerJapanese`, `minWords`, `maxWords`, `sourceText`, `translationDirection` (`JA_TO_VI` or `VI_TO_JA`).
- **Response:** `ApiResponse<AssignmentResponse>` (includes `writingContent`).

## 3. Assignment Metadata Update & Lifecycle

### PUT `/{id}`
- **Purpose:** Update assignment metadata and/or transition status.
- **Body:** `UpdateAssignmentRequest`
  - General fields (all optional): `title`, `description`, `instructions`, `dueDate`, `timeLimitMinutes`, `lateSubmissionAllowed`, `lateSubmissionPenaltyPercent`, `maxScore`, `passingScore`, `shuffleQuestions`, `shuffleOptions`, `showCorrectAnswers`, `allowRetry`, `maxRetries`, `lessonId`.
  - `status` *(enum optional)* supports transitions: `DRAFT → PUBLISHED`, `PUBLISHED → CLOSED`.
    - Publishing validates: quizzes have ≥1 question, writing/translation has content.
- **Response:** `ApiResponse<AssignmentResponse>`.

### GET `/{id}`
- **Purpose:** Fetch assignment details.
- **Response Body (`AssignmentResponse`):** includes metadata, `questions` (for quizzes), `writingContent` (for writing/translation), `submissionCount`.

### DELETE `/{id}`
- **Purpose:** Soft-delete assignment (only if status = DRAFT).
- **Response:** `ApiResponse<Void>`.

## 4. Assignment Listings

### GET `/`
- **Purpose:** List assignments created by the authenticated teacher.
- **Query Params:** `page` (default 1), `size` (default 10).
- **Response:** `PageResponse<AssignmentSummaryResponse>` (includes `questionCount`, `submissionCount`, status, due date, etc.).

### GET `/class/{classId}`
- **Purpose:** List assignments belonging to a specific class.
- **Query Params:** `page`, `size` (same defaults).
- **Response:** `PageResponse<AssignmentSummaryResponse>`.

## 5. Cloning

### POST `/{id}/clone`
- **Purpose:** Duplicate an assignment (QUIZ/WRITING/TRANSLATION) to another class.
- **Body:** `CloneAssignmentRequest`
  - `targetClassId` *(UUID, required)*
  - `newTitle` *(string, optional)*
  - `newDueDate` *(datetime, optional)*
- **Response:** `ApiResponse<AssignmentResponse>` for the new DRAFT clone.

## 6. Submissions & Grading

### GET `/{id}/submissions`
- **Purpose:** Paginate submissions for an assignment (teacher view).
- **Query Params:** `page`, `size` (default 1/10).
- **Response:** `PageResponse<SubmissionSummaryResponse>` with submission metadata, learner info, scores, status.

### GET `/submissions/{id}`
- **Purpose:** Fetch full submission details (answers, scores, feedback).
- **Response:** `ApiResponse<SubmissionResponse>`.

### POST `/submissions/{id}/grade-writing`
- **Purpose:** Grade WRITING/TRANSLATION submissions.
- **Body:** `GradeWritingRequest`
  - `teacherScore` *(number, required)*
  - `teacherComment` *(string, optional)*
  - `feedback` *(string, optional)*
- **Response:** `ApiResponse<SubmissionResponse>` reflecting new status `GRADED`.

---
**Notes:**
- All timestamps use ISO-8601 in UTC.
- IDs are UUID strings.
- For pagination endpoints, the service uses Spring's 0-based pages internally; the controller converts to 1-based values in responses.


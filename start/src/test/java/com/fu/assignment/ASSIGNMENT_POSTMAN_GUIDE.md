# Assignment — Postman Manual Testing Guide

> Các scenario dưới đây cần test thủ công qua Postman vì liên quan đến **real external services** (Cloudinary, OpenAI) — không thể validate đầy đủ bằng mock.

## Yêu cầu trước khi test

- Server đang chạy (`mvn -pl start -am spring-boot:run`)
- PostgreSQL + Redis đã khởi động (`docker-compose up -d`)
- Cloudinary credentials hợp lệ trong `application.yml`
- OpenAI API key hợp lệ trong `application.yml`
- Có sẵn teacher account + learner account (đã enroll vào class)

---

## 1. Cloudinary — Real File Upload

### PM_AS_CLOUD_001 — Upload audio khi tạo Quiz

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

Part "audioFile": chọn file .mp3 thực tế (3-5 MB)
```

**Verify:**
- [ ] Response 200, `data.audioUrl` chứa URL Cloudinary hợp lệ
- [ ] Mở URL trong browser → file audio phát được
- [ ] Cloudinary dashboard → file tồn tại trong folder `satori/assignment-audio`

---

### PM_AS_CLOUD_002 — Upload ảnh khi Learner submit Writing

```
POST /api/v1/learner/assignments/{assignmentId}/submit-writing
Content-Type: multipart/form-data
Header: Authorization: Bearer {learner_token}

Bước 1: POST /api/v1/learner/assignments/{id}/start  (start assignment trước)

Bước 2: Submit:
  Part "data": {"writtenAnswer":"テスト回答","timeSpentSeconds":120}
  Part "images": chọn 1-3 file .png/.jpg thực tế
```

**Verify:**
- [ ] Response 200, `data.imageUrls` là mảng các URL Cloudinary
- [ ] Mở từng URL → ảnh hiển thị đúng
- [ ] `data.status` = `"SUBMITTED"`


## 2. OpenAI — Real AI Generation

### PM_AS_AI_001 — AI generate quiz questions (basic)

```
POST /api/v1/teacher/assignments/questions/ai-generate
Content-Type: application/json
Header: Authorization: Bearer {teacher_token}

{
  "teacherPrompt": "Tạo câu hỏi trắc nghiệm về từ vựng bài 1 N5",
  "classId": "{classId_có_course_có_lesson}",
  "questionCount": 3,
  "questionTypes": ["multiple_choice"]
}
```

**Verify:**
- [ ] Response 200, `data` là mảng 3 câu hỏi
- [ ] Mỗi câu có: `questionText` (tiếng Việt), `options` (JSON hợp lệ), `correctAnswer`
- [ ] Nội dung phù hợp prompt N5
- [ ] Furigana `<ruby>` tags đúng format (chỉ cho Kanji)
- [ ] `explanation` không rỗng

---

### PM_AS_AI_002 — AI generate: prompt phức tạp, nhiều loại câu hỏi

```
POST /api/v1/teacher/assignments/questions/ai-generate
{
  "teacherPrompt": "Tạo câu hỏi bài 1 đến 5, hỗn hợp multiple_choice và fill_blank",
  "classId": "{classId}",
  "questionCount": 10,
  "questionTypes": ["multiple_choice", "fill_blank"]
}
```

**Verify:**
- [ ] Response 200
- [ ] Có cả hai loại `multiple_choice` và `fill_blank`
- [ ] Số lượng có thể ≤ 10 nếu bị truncate (kiểm tra server log)

---

### PM_AS_AI_003 — AI explain question đã lưu

```
POST /api/v1/teacher/assignments/questions/{questionId}/ai-explain
Header: Authorization: Bearer {teacher_token}
```

**Verify:**
- [ ] Response 200
- [ ] `data.explanation` không rỗng, bằng tiếng Việt
- [ ] Nội dung giải thích liên quan đến câu hỏi
- [ ] Check DB: `quiz_questions` table → `explanation` column đã cập nhật

---

### PM_AS_AI_004 — AI explain draft (preview, không lưu DB)

```
POST /api/v1/teacher/assignments/questions/ai-explain-draft
Content-Type: application/json
Header: Authorization: Bearer {teacher_token}

{
  "questionText": "Chọn đáp án đúng: 「日本語」は何ですか？",
  "options": "[{\"id\":\"A\",\"text\":\"Tiếng Nhật\"},{\"id\":\"B\",\"text\":\"Tiếng Anh\"}]",
  "correctAnswer": "A"
}
```

**Verify:**
- [ ] Response 200, `data.explanation` có nội dung hợp lý
- [ ] KHÔNG lưu vào DB (chỉ preview)

---

### PM_AS_AI_005 — AI với prompt rỗng/nonsense

```
POST /api/v1/teacher/assignments/questions/ai-generate
{
  "teacherPrompt": "asdfghjkl random text",
  "questionCount": 1,
  "questionTypes": ["multiple_choice"]
}
```

**Verify:**
- [ ] AI vẫn trả response (có thể sinh câu hỏi chung)
- [ ] Server không crash

---

## 3. End-to-End Flows

### PM_AS_E2E_001 — Full Quiz Flow: Teacher → AI → Learner → Grade

```
1. Teacher login → POST /api/v1/auth/login
2. AI generate 5 câu → POST .../questions/ai-generate
3. Tạo quiz PUBLISHED (include AI questions + audio)
   → POST .../class/{classId}/quiz
4. Learner login → GET .../learner/assignments → thấy quiz
5. Learner start → POST .../learner/assignments/{id}/start
6. Learner submit → POST .../learner/assignments/submit  (gửi đáp án)
7. Teacher view submissions → GET .../{id}/submissions
8. Teacher clone → POST .../{id}/clone  (sang class khác)
```

**Verify:**
- [ ] Quiz auto-graded, score chính xác
- [ ] Clone tạo DRAFT mới, không copy submissions

---

### PM_AS_E2E_002 — Full Writing Flow: Submit + Images + Grade

```
1. Teacher tạo WRITING → POST .../class/{classId}/writing (PUBLISHED)
2. Learner start → POST .../learner/assignments/{id}/start
3. Learner submit writing + 3 ảnh
   → POST .../learner/assignments/{id}/submit-writing (multipart)
4. Teacher grade → POST .../submissions/{id}/grade-writing
5. Learner xem kết quả → GET .../learner/assignments/submissions/{id}
```

**Verify:**
- [ ] `status` transitions: IN_PROGRESS → SUBMITTED → GRADED
- [ ] `imageUrls` có 3 URL, mở được
- [ ] Score + feedback hiển thị cho learner

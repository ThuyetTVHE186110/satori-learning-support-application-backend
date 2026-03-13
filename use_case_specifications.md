# Đặc tả Use Case (Use Case Specifications)

Tài liệu này cung cấp đặc tả chi tiết cho 13 tính năng thuộc nhóm **Speaking Practice**, **Practice Task Management** và **Assignment**. Tester có thể sử dụng tài liệu này để hình dung rõ logic hệ thống, các luồng ngoại lệ và lên kịch bản kiểm thử (Test Cases) chi tiết.

---

## I. Nhóm tính năng Speaking Practice (Thực hành nói)

### 1. View Conversation Theme List (Xem danh sách phân nhóm chủ đề)
*   **Actor:** Learner (Học viên)
*   **Mô tả:** Học viên xem danh sách các chủ đề lớn (Themes) có sẵn trong hệ thống được định vị dựa trên tiến độ học tập.
*   **Preconditions (Điều kiện tiên quyết):** Learner đã đăng nhập thành công.
*   **Main Flow:**
    1. Learner điều hướng đến mục "Speaking Practice".
    2. Hệ thống gọi API `GET /api/v1/learner/conversation/themes`. Có thể truyền tham số `level` (vd: N5) để lọc theo tiến độ hiện tại, và phân trang `page`, `size`.
    3. Hệ thống trả về danh sách các Themes đã được xuất bản (Published), sắp xếp theo thứ tự `orderIndex`.
    4. Giao diện hiển thị danh sách Theme cho Learner.
*   **Alternative Flow:**
    *   *Search bằng từ khóa:* Learner nhập từ khóa vào ô tìm kiếm. API được gọi với tham số `keyword`. Hệ thống trả về danh sách Theme có chứa từ khóa.
*   **Postconditions:** Danh sách Theme được hiển thị chính xác.
*   **API liên quan:** `LearnerConversationController.getPublishedThemes`

### 2. View Conversation Topic List (Xem danh sách chủ đề thực hành)
*   **Actor:** Learner
*   **Mô tả:** Khi chọn một Theme, Learner sẽ thấy danh sách các chủ đề con (Topics) thuộc Theme đó.
*   **Preconditions:** Learner đã chọn một Theme cụ thể.
*   **Main Flow:**
    1. Learner nhấp vào một Theme.
    2. Hệ thống gọi API `GET /api/v1/learner/conversation/themes/{themeId}/topics`.
    3. Hệ thống trả về danh sách các Topics thuộc Theme đã chọn.
    4. Giao diện hiển thị danh sách các Topic.
*   **Alternative Flow:**
    *   *Theme không tồn tại hoặc chưa publish:* Hệ thống trả về lỗi 404 hoặc danh sách rỗng.
*   **Postconditions:** Danh sách Topic được hiển thị.
*   **API liên quan:** `LearnerConversationController.getTopicsByTheme`

### 3. Practice Conversation with AI (Thực hành hội thoại với AI)
*   **Actor:** Learner
*   **Mô tả:** Learner tham gia một phiên đóng vai (roleplay session) với đối tác AI dựa trên Topic đã chọn, nhận hướng dẫn và báo cáo kết quả cuối cùng.
*   **Preconditions:** Learner đã chọn một Topic hợp lệ và bắt đầu thực hành.
*   **Main Flow:**
    1. Learner bắt đầu phiên thực hành trên một Topic. Hệ thống gọi API `POST /api/v1/learner/roleplay/sessions` (hoặc `free-talk/sessions` nếu trò chuyện tự do).
    2. Hệ thống khởi tạo Context, thiết lập Mission và trả về thông tin `SessionResponse`.
    3. Learner gửi tin nhắn (text hoặc audio). Hệ thống gọi API `POST /api/v1/learner/roleplay/sessions/{sessionId}/messages`.
    4. Hệ thống AI xử lý tin nhắn và phản hồi lại, kèm theo tiến độ hoàn thành các nhiệm vụ (Missions).
    5. Learner kết thúc phiên. Hệ thống gọi API `POST /api/v1/learner/roleplay/sessions/{sessionId}/complete`.
    6. Hệ thống đánh giá toàn bộ cuộc hội thoại, tính điểm, gợi ý cải thiện và trả về báo cáo hiệu suất (Performance Report).
*   **Alternative Flow:**
    *   *Learner gửi file audio hỏng/quá dung lượng:* Hệ thống trả về lỗi (400 Bad Request).
    *   *Learner tự ý thoát khi chưa xong:* Hệ thống gọi API `POST /api/v1/learner/roleplay/sessions/{sessionId}/abandon`. Phiên sẽ bị đánh dấu hủy bỏ, không có báo cáo đầy đủ.
*   **Postconditions:** Phiên học được lưu lại lịch sử kèm điểm số phân tích.
*   **API liên quan:** `LearnerRoleplayController` (`startSession`, `sendMessage`, `completeSession`, `abandonSession`)

### 4. Create Custom Conversation Topic (Tạo chủ đề giao tiếp tùy chỉnh)
*   **Actor:** Learner
*   **Mô tả:** Learner tự tạo một Topic riêng bằng cách cung cấp bối cảnh, từ vựng hoặc tình huống mong muốn.
*   **Preconditions:** Learner đã đăng nhập.
*   **Main Flow:**
    1. Learner nhập tiêu đề và bối cảnh mong muốn.
    2. Hệ thống gọi API `POST /api/v1/learner/conversation/my-topics` với payload chứa thông tin.
    3. Topic được lưu trữ vào không gian cá nhân của Learner.
    4. Learner có thể yêu cầu AI sinh ra Context (từ vựng, ngữ pháp gợi ý) bằng cách gọi API `POST /api/v1/learner/conversation/my-topics/{topicId}/generate-context`.
    5. AI trả về Context chi tiết cho Topic tự tạo.
*   **Alternative Flow:**
    *   *Trùng tên, bỏ trống trường bắt buộc:* Hệ thống validation trả về lỗi 400.
*   **Postconditions:** Chủ đề tùy chỉnh được tạo thành công, chỉ Learner đó nhìn thấy.
*   **API liên quan:** `LearnerConversationController.createMyTopic`, `generateContext`.

---

## II. Nhóm tính năng Practice Task Management (Giáo viên quản lý bài tập)

### 5. View Practice Task List (Xem danh sách bài tập đã tạo)
*   **Actor:** Teacher (Giáo viên)
*   **Mô tả:** Giáo viên xem danh sách các bài tập (Assignments) mình đã tạo hoặc lọc theo một lớp học cụ thể.
*   **Preconditions:** Teacher đã đăng nhập.
*   **Main Flow:**
    1. Teacher truy cập màn hình quản lý bài tập.
    2. Hệ thống gọi API `GET /api/v1/teacher/assignments` (xem tất cả) hoặc `GET /api/v1/teacher/assignments/class/{classId}` (lọc theo lớp).
    3. Hệ thống trả về danh sách Assignment, phân trang.
    4. Giao diện hiển thị danh sách gồm Tiêu đề, Hạn chót (Due date), Loại (Quiz/Writing), Trạng thái (Draft/Published).
*   **Postconditions:** DS bài tập hiển thị chính xác theo lớp.
*   **API liên quan:** `TeacherAssignmentController.getMyAssignments`, `getClassAssignments`

### 6. View Practice Tasks Information (Xem chi tiết bài tập)
*   **Actor:** Teacher
*   **Mô tả:** Giáo viên xem thông tin chi tiết của một bài tập cụ thể, bao gồm mô tả, các câu hỏi/đề bài, và số lượng bài đã nộp.
*   **Preconditions:** Teacher chọn một bài tập hợp lệ.
*   **Main Flow:**
    1. Teacher click vào một bài tập.
    2. Hệ thống gọi API `GET /api/v1/teacher/assignments/{id}`.
    3. Hệ thống trả về toàn bộ meta-data cùng danh sách câu hỏi (nếu là quiz) hoặc nội dung prompt (nếu là dạng writing/translation).
*   **Postconditions:** Dữ liệu chi tiết hiển thị đúng.
*   **API liên quan:** `TeacherAssignmentController.getAssignment`

### 7. Create Practice Task (Tạo bài tập mới)
*   **Actor:** Teacher
*   **Mô tả:** Giáo viên tạo một bài tập mới (Quiz định dạng trắc nghiệm, Writing viết tự luận, Translation dịch thuật) cho một lớp học. Có thể upload file (audio).
*   **Preconditions:** Teacher có quyền quản lý classId được chỉ định.
*   **Main Flow:**
    1. Teacher nhập thông tin chung: Tiêu đề, Hạn nộp (dueDate), Mô tả.
    2. Teacher nhập nội dung câu hỏi (đối với Quiz) hoặc Prompt (đối với Writing/Translation). *Có thể dùng AI generate câu hỏi/giải thích.*
    3. Khi lưu, tùy vào loại bài tập, ứng dụng gọi:
        *   Quiz: `POST /api/v1/teacher/assignments/class/{classId}/quiz` (Hỗ trợ multipart file audio).
        *   Writing: `POST /api/v1/teacher/assignments/class/{classId}/writing`
        *   Translation: `POST /api/v1/teacher/assignments/class/{classId}/translation`
    4. Hệ thống validate và Upsert (tạo mới Assignment và map các Question/Content).
*   **Alternative Flow:**
    *   *Lỗi file đính kèm:* Dung lượng quá lớn, sai định dạng -> HTTP 400.
    *   *Lỗi thiếu câu trả lời đúng (Quiz):* Lỗi logic validation -> HTTP 400.
*   **Postconditions:** Bài tập được tạo và giao cho lớp.
*   **API liên quan:** `TeacherAssignmentController`

### 8. Update Practice Task (Cập nhật bài tập)
*   **Actor:** Teacher
*   **Mô tả:** Sửa đổi thông tin bài tập (tiêu đề, câu hỏi, thời hạn).
*   **Preconditions:** Bài tập thuôc sở hữu của Teacher.
*   **Main Flow:**
    1. Teacher mở bài tập và chỉnh sửa.
    2. Giao diện gọi các API tương ứng (PUT `/quiz`, `/writing`, `/translation` với `{id}`).
    3. Hệ thống đồng bộ (sync) các câu hỏi, xóa bỏ câu hỏi bị loại bỏ và cập nhật nội dung.
*   **Alternative Flow:**
    *   *Bài tập đã có người nộp:* Hệ thống có thể chặn cập nhật (tùy nghiệp vụ) hoặc hiển thị cảnh báo.
*   **Postconditions:** Bài tập được cập nhật dữ liệu.
*   **API liên quan:** `TeacherAssignmentController.updateFullQuiz/Writing/Translation`

### 9. Delete Practice Task (Xóa bài tập)
*   **Actor:** Teacher
*   **Mô tả:** Giáo viên xóa một bài tập (Soft-delete). Hệ thống chỉ cho phép xóa bài tập ở trạng thái DRAFT.
*   **Preconditions:** Assignment đang ở trạng thái DRAFT.
*   **Main Flow:**
    1. Teacher bấm chọn nút Xóa Assignment.
    2. Hệ thống gọi API `DELETE /api/v1/teacher/assignments/{id}`.
    3. Hệ thống đánh dấu xóa mềm, bài tập biến mất khỏi giao diện.
*   **Alternative Flow:**
    *   *Assignment không ở trạng thái DRAFT (đã Publish/đã có người làm):* Hệ thống báo lỗi, từ chối xóa để bảo vệ tính toàn vẹn dữ liệu.
*   **Postconditions:** Bài tập không còn xuất hiện trong danh sách lớp học.
*   **API liên quan:** `TeacherAssignmentController.deleteAssignment`

### 10. View Practice Task Results (Xem kết quả, chấm điểm)
*   **Actor:** Teacher
*   **Mô tả:** Giáo viên xem danh sách các học viên đã nộp bài, xem chi tiết bài làm, thực hiện chấm điểm và đưa ra phản hồi (Feedback) cho dạng Writing/Translation.
*   **Preconditions:** Có ít nhất 1 bài tập đã được nộp.
*   **Main Flow:**
    1. Mở xem danh sách nộp bài bài tập: Gọi API `GET /api/v1/teacher/assignments/{id}/submissions`.
    2. Click vào từng bài nộp cụ thể: Gọi API `GET /api/v1/teacher/assignments/submissions/{id}` để xem chi tiết bài làm.
    3. Nếu là dạng Writing/Translation (Cần chấm tay), giáo viên nhập Điểm (Score) và Nhận xét (Teacher comment).
    4. Giáo viên bấm Chấm bài: Gọi API `POST /api/v1/teacher/assignments/submissions/{id}/grade-writing`.
    5. Trạng thái bài làm chuyển sang GRADED.
*   **Alternative Flow:**
    *   *Dạng bài Quiz:* Hệ thống auto-grade, giáo viên chỉ việc xem điểm, không cần chấm tay.
*   **Postconditions:** Bài tập được đánh dấu là đã chấm (GRADED). Điểm được lưu.
*   **API liên quan:** `TeacherAssignmentController.getSubmissions`, `getSubmission`, `gradeWritingSubmission`

---

## III. Nhóm tính năng Assignment (Học viên làm bài tập)

### 11. View Assigned Practice Tasks (Xem danh sách bài tập được giao)
*   **Actor:** Learner
*   **Mô tả:** Học viên xem danh sách các bài tập giáo viên đã gán cho mình thông qua các lớp đang tham gia.
*   **Preconditions:** Learner đang Join vào lớp học và có Assignment loại Published.
*   **Main Flow:**
    1. Learner truy cập trang danh sách bài tập.
    2. Hệ thống gọi API `GET /api/v1/learner/assignments`.
    3. Trả về danh sách bài tập bao gồm Tên, Hạn nộp (dueDate), Trạng thái nộp bài của học sinh (Chưa làm, Đã nộp, Đã chấm).
*   **Postconditions:** Danh sách Assignment được hiển thị.
*   **API liên quan:** `LearnerAssignmentController.getAvailableAssignments`

### 12. Do Practice Task (Làm và nộp bài tập)
*   **Actor:** Learner
*   **Mô tả:** Learner bắt đầu làm một bài tập, chọn các đáp án trong quiz hoặc điền nội dung tự luận (hỗ trợ đính kèm file) và tiến hành nộp.
*   **Preconditions:** Bài tập còn hạn nộp (dueDate hợp lệ), Learner chưa nộp bài đạt giới hạn tối đa (nếu có).
*   **Main Flow:**
    1. Learner xem chi tiết bài tập bằng `GET /api/v1/learner/assignments/{id}`. (API trả về câu hỏi nhưng không kèm đáp án đúng).
    2. Learner bấm bắt đầu: Gọi `POST /api/v1/learner/assignments/{id}/start` để ghi nhận thời điểm làm.
    3. Nhập đáp án trên giao diện.
    4. Learner bấm nút Submit.
        *   Nếu là Quiz (Trắc nghiệm): Gọi `POST /api/v1/learner/assignments/submit`. Hệ thống tự động chấm điểm và trả về kết quả (Submitted/Graded).
        *   Nếu là Writing/Translation (Tự luận): Gọi `POST /api/v1/learner/assignments/{id}/submit-writing` với nội dung text và tối đa N files hình ảnh đính kèm (Multipart upload).
*   **Alternative Flow:**
    *   *Nộp quá hạn:* API reject (HTTP 400 - Overdue).
    *   *Nộp thiếu trường bắt buộc:* Lỗi validation.
*   **Postconditions:** Bài nộp thành công, chờ giáo viên chấm hoặc hiển thị điểm (với Quiz).
*   **API liên quan:** `LearnerAssignmentController` (`getAssignmentDetails`, `startAssignment`, `submitQuizAssignment`, `submitWritingAssignment`)

### 13. View Practice Task Results (Xem kết quả bài tập đã chấm)
*   **Actor:** Learner
*   **Mô tả:** Learner xem chi tiết lại một bài đã nộp: đối với báo cáo bao gồm câu trả lời, nhận xét (feedback), giải thích từ giáo viên hoặc hệ thống, và điểm đạt được.
*   **Preconditions:** Bài thi đã được nộp thành công, bài tự luận đã chuyển sang trạng thái GRADED.
*   **Main Flow:**
    1. Learner nhấp vào bài làm đã hoàn thành từ lịch sử học tập (`GET /api/v1/learner/assignments/submissions`).
    2. Hệ thống gọi `GET /api/v1/learner/assignments/submissions/{id}`.
    3. Giao diện hiển thị chi tiết câu trả lời của Learner, đáp án đúng (từ server), Giải thích (AI hoặc Teacher insight), Điểm đạt được, Comment của giáo viên.
*   **Postconditions:** Dữ liệu điểm số và feedback hiển thị đầy đủ, Read-only.
*   **API liên quan:** `LearnerAssignmentController.getMySubmissionDetails`

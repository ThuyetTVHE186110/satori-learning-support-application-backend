-- ==========================================================================
-- Script 40: Mock Closed Class Data with Completed Submissions
-- Seeds a CLOSED class with completed assignments and student submissions.
-- Teachers: Tanaka Sensei (a0000000-0000-0000-0000-000000000003)
-- Learner 1: Nguyen Van A (a0000000-0000-0000-0000-000000000004) - also in ACTIVE class
-- Learner 3: Suspended User (a0000000-0000-0000-0000-000000000006)
-- Learner 4: Inactive User (a0000000-0000-0000-0000-000000000007)
-- ==========================================================================

DO $$
DECLARE
    v_teacher_id    UUID := 'a0000000-0000-0000-0000-000000000003';
    v_learner1_id   UUID := 'a0000000-0000-0000-0000-000000000004';
    v_learner3_id   UUID := 'a0000000-0000-0000-0000-000000000006';
    v_learner4_id   UUID := 'a0000000-0000-0000-0000-000000000007';
    v_closed_class_id UUID := 'c0000000-0000-0000-0000-000000000002';
    v_manager_id    UUID := 'a0000000-0000-0000-0000-000000000010';
    
    v_closed_course_id UUID := 'd0000000-0000-0000-0000-000000000002';
    
    v_closed_quiz1_id UUID := 'b0000000-0000-0000-0000-000000000011';
    v_closed_quiz2_id UUID := 'b0000000-0000-0000-0000-000000000012';
    v_closed_writing1_id UUID := 'b0000000-0000-0000-0000-000000000013';
    v_closed_translation1_id UUID := 'b0000000-0000-0000-0000-000000000014';
    
    v_c_q1_id       UUID := 'e0000000-0000-0000-0000-000000000020';
    v_c_q2_id       UUID := 'e0000000-0000-0000-0000-000000000021';
    v_c_q3_id       UUID := 'e0000000-0000-0000-0000-000000000022';
    v_c_q4_id       UUID := 'e0000000-0000-0000-0000-000000000023';
    v_c_q5_id       UUID := 'e0000000-0000-0000-0000-000000000024';
    v_c_q6_id       UUID := 'e0000000-0000-0000-0000-000000000025';
    v_c_q7_id       UUID := 'e0000000-0000-0000-0000-000000000026';
    v_c_q8_id       UUID := 'e0000000-0000-0000-0000-000000000027';
BEGIN

-- 0. Create Course for Closed Class (if not exists)
IF NOT EXISTS (SELECT 1 FROM learning_content.courses WHERE id = v_closed_course_id) THEN
    INSERT INTO learning_content.courses (id, name, description, jlpt_level, order_index, status, created_by)
    VALUES (v_closed_course_id, 'Tiếng Nhật N4 nâng cao', 'Khóa học tiếng Nhật trình độ N4 dành cho người đã học N5.', 'N4', 2, 'ACTIVE', v_teacher_id);
    
    RAISE NOTICE 'Closed Course created: Tiếng Nhật N4 nâng cao';
END IF;

-- 1. Create Closed Class (if not exists)
IF NOT EXISTS (SELECT 1 FROM user_management.classes WHERE id = v_closed_class_id) THEN
    INSERT INTO user_management.classes (id, name, description, course_id, max_students, status, start_date, end_date, created_at, created_by)
    VALUES (v_closed_class_id, 'Lớp N4 - Khóa tháng 1 2026', 'Lớp học tiếng Nhật N4 đã kết thúc vào tháng 12 năm ngoái.', v_closed_course_id, 20, 'CLOSED'::user_management.class_status, CURRENT_DATE - INTERVAL '30 days', CURRENT_DATE - INTERVAL '10 days', CURRENT_TIMESTAMP - INTERVAL '30 days', v_manager_id);
    
    RAISE NOTICE 'Closed Class created: Lớp N4 - Khóa tháng 1 2026';
END IF;

-- 2. Add Class Members to Closed Class
INSERT INTO user_management.class_members (class_id, user_id, joined_at)
VALUES (v_closed_class_id, v_learner1_id, CURRENT_TIMESTAMP - INTERVAL '120 days'),
       (v_closed_class_id, v_learner3_id, CURRENT_TIMESTAMP - INTERVAL '120 days'),
       (v_closed_class_id, v_learner4_id, CURRENT_TIMESTAMP - INTERVAL '120 days')
ON CONFLICT (class_id, user_id) DO NOTHING;

-- 3. Create Quiz Questions for Closed Class (Reading & Grammar N4)
INSERT INTO assignment.quiz_questions (id, question_text, question_type, options, correct_answer, explanation, jlpt_level, created_by)
VALUES 
(v_c_q1_id, 'Câu nào sử dụng ++「ている」++ đúng nhất?', 'multiple_choice',
 '[{"id": "A", "text": "彼は毎日公園で走っている。"}, {"id": "B", "text": "彼は毎日公園で走る。"}, {"id": "C", "text": "彼は毎日公園で走った。"}]'::JSONB,
 'A', '「ている」diễn tả hành động đang diễn ra hoặc thói quen. "Anh ta chạy bộ ở công viên mỗi ngày" - hành động lặp lại.', 'N4', v_teacher_id),
 
(v_c_q2_id, 'Từ nào là bậc so sánh của 「速い」（nhanh）?', 'multiple_choice',
 '[{"id": "A", "text": "もっと速い"}, {"id": "B", "text": "速くない"}, {"id": "C", "text": "速める"}]'::JSONB,
 'A', '「もっと速い」nghĩa là "nhanh hơn" - bậc so sánh của tính từ.', 'N4', v_teacher_id),

(v_c_q3_id, '「と思う」 thường được sử dụng để làm gì?', 'multiple_choice',
 '[{"id": "A", "text": "Diễn tả suy nghĩ/quan điểm cá nhân"}, {"id": "B", "text": "Diễn tả sự sợ hãi"}, {"id": "C", "text": "Diễn tả sự yêu cầu"}]'::JSONB,
 'A', '「と思う」diễn tả quan điểm, suy nghĩ của người nói hoặc người khác.', 'N4', v_teacher_id),

(v_c_q4_id, 'Khi nào dùng 「〜になる」?', 'multiple_choice',
 '[{"id": "A", "text": "Diễn tả sự thay đổi trạng thái"}, {"id": "B", "text": "Diễn tả sự lặp lại"}, {"id": "C", "text": "Diễn tả mục đích"}]'::JSONB,
 'A', '「〜になる」diễn tả sự thay đổi từ trạng thái này sang trạng thái khác.', 'N4', v_teacher_id),

(v_c_q5_id, '「〜のに」 có thể dùng để:', 'multiple_choice',
 '[{"id": "A", "text": "Diễn tả mục đích"}, {"id": "B", "text": "Diễn tả mâu thuẫn (nhưng)"}, {"id": "C", "text": "Cả A và B"}]'::JSONB,
 'C', '「〜のに」có thể diễn tả mục đích (dùng để) hoặc mâu thuẫn (nhưng, mà).', 'N4', v_teacher_id),

(v_c_q6_id, 'Hán tự 「学生」 đọc như thế nào?', 'multiple_choice',
 '[{"id": "A", "text": "がくせい"}, {"id": "B", "text": "がっこう"}, {"id": "C", "text": "べんきょう"}]'::JSONB,
 'A', '「学生」(gakusei) nghĩa là học sinh/sinh viên.', 'N4', v_teacher_id),

(v_c_q7_id, '「約束」 có nghĩa là gì?', 'multiple_choice',
 '[{"id": "A", "text": "Lịch trình"}, {"id": "B", "text": "Hứa hẹn/Cam kết"}, {"id": "C", "text": "Thư tín"}]'::JSONB,
 'B', '「約束」(yakusoku) nghĩa là hứa hẹn, cam kết, hoặc lịch hẹn.', 'N4', v_teacher_id),

(v_c_q8_id, 'Câu nào biểu thị lời đề nghị đúng?', 'multiple_choice',
 '[{"id": "A", "text": "一緒に行きませんか。"}, {"id": "B", "text": "一緒に行きました。"}, {"id": "C", "text": "一緒に行きます。"}]'::JSONB,
 'A', '「〜ませんか」dùng để mời/đề nghị ai đó cùng làm điều gì.', 'N4', v_teacher_id)
ON CONFLICT (id) DO NOTHING;

-- 4. Create Assignments for Closed Class (All CLOSED status since class ended)
-- Assignment 1: Quiz on Grammar (CLOSED)
INSERT INTO assignment.assignments (id, title, description, assignment_type, class_id, due_date, status, has_submissions, created_by)
VALUES (v_closed_quiz1_id, 'Kiểm tra Ngữ pháp N4 - Tuần 1', 'Bài kiểm tra về các cấu trúc ngữ pháp N4 đã học trong tuần đầu tiên.', 'QUIZ', v_closed_class_id, CURRENT_TIMESTAMP - INTERVAL '60 days', 'CLOSED', TRUE, v_teacher_id)
ON CONFLICT (id) DO NOTHING;

INSERT INTO assignment.assignment_questions (assignment_id, question_id, order_index, points)
VALUES (v_closed_quiz1_id, v_c_q1_id, 0, 5), (v_closed_quiz1_id, v_c_q2_id, 1, 5), (v_closed_quiz1_id, v_c_q3_id, 2, 5)
ON CONFLICT (assignment_id, question_id) DO NOTHING;

-- Assignment 2: Quiz on Vocabulary & Kanji (CLOSED)
INSERT INTO assignment.assignments (id, title, description, assignment_type, class_id, due_date, status, has_submissions, created_by)
VALUES (v_closed_quiz2_id, 'Luyện tập Từ vựng & Kanji N4', 'Bài luyện tập về từ vựng và hán tự cơ bản trong N4.', 'QUIZ', v_closed_class_id, CURRENT_TIMESTAMP - INTERVAL '50 days', 'CLOSED', TRUE, v_teacher_id)
ON CONFLICT (id) DO NOTHING;

INSERT INTO assignment.assignment_questions (assignment_id, question_id, order_index, points)
VALUES (v_closed_quiz2_id, v_c_q4_id, 0, 3), (v_closed_quiz2_id, v_c_q5_id, 1, 3), (v_closed_quiz2_id, v_c_q6_id, 2, 2), (v_closed_quiz2_id, v_c_q7_id, 3, 2), (v_closed_quiz2_id, v_c_q8_id, 4, 2)
ON CONFLICT (assignment_id, question_id) DO NOTHING;

-- Assignment 3: Writing - Ngôn tự bản thân (CLOSED)
INSERT INTO assignment.assignments (id, title, description, assignment_type, class_id, due_date, status, has_submissions, created_by)
VALUES (v_closed_writing1_id, 'Viết về sở thích của bạn', 'Hãy viết một đoạn văn (100-150 chữ) về sở thích cá nhân của bạn bằng tiếng Nhật.', 'WRITING', v_closed_class_id, CURRENT_TIMESTAMP - INTERVAL '45 days', 'CLOSED', TRUE, v_teacher_id)
ON CONFLICT (id) DO NOTHING;

INSERT INTO assignment.writing_assignments (assignment_id, prompt)
VALUES (v_closed_writing1_id, 'Viết về sở thích bao gồm: Sở thích là gì, tại sao bạn thích điều đó, bao lâu bạn làm điều đó mỗi tuần.')
ON CONFLICT (assignment_id) DO NOTHING;

-- Assignment 4: Translation - Dịch đoạn văn (CLOSED)
INSERT INTO assignment.assignments (id, title, description, assignment_type, class_id, due_date, status, has_submissions, created_by)
VALUES (v_closed_translation1_id, 'Luyện dịch: Bài học về thời tiết', 'Dịch đoạn văn về thời tiết từ tiếng Việt sang tiếng Nhật.', 'TRANSLATION', v_closed_class_id, CURRENT_TIMESTAMP - INTERVAL '40 days', 'CLOSED', TRUE, v_teacher_id)
ON CONFLICT (id) DO NOTHING;

INSERT INTO assignment.writing_assignments (assignment_id, prompt)
VALUES (v_closed_translation1_id, 'Dịch sang tiếng Nhật: "Hôm nay thời tiết rất đẹp. Mặt trời sáng chiếu, không khí ấm áp và thơm mùi hoa."')
ON CONFLICT (assignment_id) DO NOTHING;

-- 5a. Create Submissions for Learner 1 (also in ACTIVE class)
-- Quiz 1: Very good score
INSERT INTO assignment.assignment_submissions (id, assignment_id, user_id, status, started_at, submitted_at, score, correct_count, total_questions, answers, graded_by, graded_at)
VALUES (uuid_generate_v4(), v_closed_quiz1_id, v_learner1_id, 'GRADED', CURRENT_TIMESTAMP - INTERVAL '60 days', CURRENT_TIMESTAMP - INTERVAL '60 days 20 minutes', 96.66::numeric, 3, 3,
    format('[{"questionId": "%s", "selectedAnswer": "A", "isCorrect": true}, {"questionId": "%s", "selectedAnswer": "A", "isCorrect": true}, {"questionId": "%s", "selectedAnswer": "A", "isCorrect": true}]', v_c_q1_id, v_c_q2_id, v_c_q3_id)::JSONB,
    v_teacher_id, CURRENT_TIMESTAMP - INTERVAL '59 days')
ON CONFLICT DO NOTHING;

-- Quiz 2: Excellent score
INSERT INTO assignment.assignment_submissions (id, assignment_id, user_id, status, started_at, submitted_at, score, correct_count, total_questions, answers, graded_by, graded_at)
VALUES (uuid_generate_v4(), v_closed_quiz2_id, v_learner1_id, 'GRADED', CURRENT_TIMESTAMP - INTERVAL '50 days', CURRENT_TIMESTAMP - INTERVAL '50 days 18 minutes', 100.0::numeric, 5, 5,
    format('[{"questionId": "%s", "selectedAnswer": "A", "isCorrect": true}, {"questionId": "%s", "selectedAnswer": "C", "isCorrect": true}, {"questionId": "%s", "selectedAnswer": "A", "isCorrect": true}, {"questionId": "%s", "selectedAnswer": "B", "isCorrect": true}, {"questionId": "%s", "selectedAnswer": "A", "isCorrect": true}]', v_c_q4_id, v_c_q5_id, v_c_q6_id, v_c_q7_id, v_c_q8_id)::JSONB,
    v_teacher_id, CURRENT_TIMESTAMP - INTERVAL '49 days')
ON CONFLICT DO NOTHING;

-- Writing 1: Excellent submission
INSERT INTO assignment.assignment_submissions (id, assignment_id, user_id, status, started_at, submitted_at, score, image_urls, feedback, graded_by, graded_at)
VALUES (uuid_generate_v4(), v_closed_writing1_id, v_learner1_id, 'GRADED', CURRENT_TIMESTAMP - INTERVAL '45 days', CURRENT_TIMESTAMP - INTERVAL '45 days 5 hours', 98.0::numeric, '["https://res.cloudinary.com/dtn0i6fvs/image/upload/v1774022189/satori/assignment-submissions/b0000000-0000-0000-0000-000000000013/a0000000-0000-0000-0000-000000000004/sample5.jpg"]'::JSONB, 'Xuất sắc! Bài viết rất hoàn hảo, cấu trúc rõ ràng, ngữ pháp chính xác, từ vựng phong phú.', v_teacher_id, CURRENT_TIMESTAMP - INTERVAL '44 days')
ON CONFLICT DO NOTHING;

-- Translation 1: Outstanding submission
INSERT INTO assignment.assignment_submissions (id, assignment_id, user_id, status, started_at, submitted_at, score, image_urls, feedback, graded_by, graded_at)
VALUES (uuid_generate_v4(), v_closed_translation1_id, v_learner1_id, 'GRADED', CURRENT_TIMESTAMP - INTERVAL '40 days', CURRENT_TIMESTAMP - INTERVAL '40 days 3 hours', 99.0::numeric, '["https://res.cloudinary.com/dtn0i6fvs/image/upload/v1774022189/satori/assignment-submissions/b0000000-0000-0000-0000-000000000014/a0000000-0000-0000-0000-000000000004/sample6.jpg"]'::JSONB, 'Dịch tuyệt vời! Chính xác hoàn toàn, sử dụng từ vựng chuyên môn đúng cách. Đây là một bài dịch mẫu mực!', v_teacher_id, CURRENT_TIMESTAMP - INTERVAL '39 days')
ON CONFLICT DO NOTHING;

-- 5b. Create Submissions for Learner 3 (Complete submissions)
-- Quiz 1: Full score
INSERT INTO assignment.assignment_submissions (id, assignment_id, user_id, status, started_at, submitted_at, score, correct_count, total_questions, answers, graded_by, graded_at)
VALUES (uuid_generate_v4(), v_closed_quiz1_id, v_learner3_id, 'GRADED', CURRENT_TIMESTAMP - INTERVAL '58 days', CURRENT_TIMESTAMP - INTERVAL '58 days 30 minutes', 100.0::numeric, 3, 3,
    format('[{"questionId": "%s", "selectedAnswer": "A", "isCorrect": true}, {"questionId": "%s", "selectedAnswer": "A", "isCorrect": true}, {"questionId": "%s", "selectedAnswer": "A", "isCorrect": true}]', v_c_q1_id, v_c_q2_id, v_c_q3_id)::JSONB,
    v_teacher_id, CURRENT_TIMESTAMP - INTERVAL '57 days')
ON CONFLICT DO NOTHING;

-- Quiz 2: Partial score (80%)
INSERT INTO assignment.assignment_submissions (id, assignment_id, user_id, status, started_at, submitted_at, score, correct_count, total_questions, answers, graded_by, graded_at)
VALUES (uuid_generate_v4(), v_closed_quiz2_id, v_learner3_id, 'GRADED', CURRENT_TIMESTAMP - INTERVAL '48 days', CURRENT_TIMESTAMP - INTERVAL '48 days 25 minutes', 80.0::numeric, 4, 5,
    format('[{"questionId": "%s", "selectedAnswer": "A", "isCorrect": true}, {"questionId": "%s", "selectedAnswer": "B", "isCorrect": false}, {"questionId": "%s", "selectedAnswer": "A", "isCorrect": true}, {"questionId": "%s", "selectedAnswer": "B", "isCorrect": true}, {"questionId": "%s", "selectedAnswer": "A", "isCorrect": true}]', v_c_q4_id, v_c_q5_id, v_c_q6_id, v_c_q7_id, v_c_q8_id)::JSONB,
    v_teacher_id, CURRENT_TIMESTAMP - INTERVAL '47 days')
ON CONFLICT DO NOTHING;

-- Writing 1: Submitted with feedback
INSERT INTO assignment.assignment_submissions (id, assignment_id, user_id, status, started_at, submitted_at, score, image_urls, feedback, graded_by, graded_at)
VALUES (uuid_generate_v4(), v_closed_writing1_id, v_learner3_id, 'GRADED', CURRENT_TIMESTAMP - INTERVAL '44 days', CURRENT_TIMESTAMP - INTERVAL '43 days 10 hours', 90.0::numeric, '["https://res.cloudinary.com/dtn0i6fvs/image/upload/v1774022189/satori/assignment-submissions/b0000000-0000-0000-0000-000000000013/a0000000-0000-0000-0000-000000000006/sample1.jpg"]'::JSONB, 'Bài viết rất tốt! Cú pháp chính xác, từ vựng phong phú. Chỉ cần chú ý thêm mấy chi tiết nhỏ.', v_teacher_id, CURRENT_TIMESTAMP - INTERVAL '42 days')
ON CONFLICT DO NOTHING;

-- Translation 1: Submitted and graded
INSERT INTO assignment.assignment_submissions (id, assignment_id, user_id, status, started_at, submitted_at, score, image_urls, feedback, graded_by, graded_at)
VALUES (uuid_generate_v4(), v_closed_translation1_id, v_learner3_id, 'GRADED', CURRENT_TIMESTAMP - INTERVAL '39 days', CURRENT_TIMESTAMP - INTERVAL '38 days 12 hours', 85.0::numeric, '["https://res.cloudinary.com/dtn0i6fvs/image/upload/v1774022189/satori/assignment-submissions/b0000000-0000-0000-0000-000000000014/a0000000-0000-0000-0000-000000000006/sample2.jpg"]'::JSONB, 'Dịch chính xác, tuy nhiên một vài từ vựng chuyên môn có thể dịch tốt hơn. Tổng thể rất tốt!', v_teacher_id, CURRENT_TIMESTAMP - INTERVAL '37 days')
ON CONFLICT DO NOTHING;

-- 6. Create Submissions for Learner 4 (Mixed submissions)
-- Quiz 1: Lower score (60%)
INSERT INTO assignment.assignment_submissions (id, assignment_id, user_id, status, started_at, submitted_at, score, correct_count, total_questions, answers, graded_by, graded_at)
VALUES (uuid_generate_v4(), v_closed_quiz1_id, v_learner4_id, 'GRADED', CURRENT_TIMESTAMP - INTERVAL '58 days', CURRENT_TIMESTAMP - INTERVAL '58 days 20 minutes', 60.0::numeric, 1, 3,
    format('[{"questionId": "%s", "selectedAnswer": "A", "isCorrect": true}, {"questionId": "%s", "selectedAnswer": "B", "isCorrect": false}, {"questionId": "%s", "selectedAnswer": "B", "isCorrect": false}]', v_c_q1_id, v_c_q2_id, v_c_q3_id)::JSONB,
    v_teacher_id, CURRENT_TIMESTAMP - INTERVAL '57 days')
ON CONFLICT DO NOTHING;

-- Quiz 2: Good score (90%)
INSERT INTO assignment.assignment_submissions (id, assignment_id, user_id, status, started_at, submitted_at, score, correct_count, total_questions, answers, graded_by, graded_at)
VALUES (uuid_generate_v4(), v_closed_quiz2_id, v_learner4_id, 'GRADED', CURRENT_TIMESTAMP - INTERVAL '49 days', CURRENT_TIMESTAMP - INTERVAL '49 days 15 minutes', 90.0::numeric, 4, 5,
    format('[{"questionId": "%s", "selectedAnswer": "A", "isCorrect": true}, {"questionId": "%s", "selectedAnswer": "C", "isCorrect": true}, {"questionId": "%s", "selectedAnswer": "A", "isCorrect": true}, {"questionId": "%s", "selectedAnswer": "B", "isCorrect": true}, {"questionId": "%s", "selectedAnswer": "A", "isCorrect": true}]', v_c_q4_id, v_c_q5_id, v_c_q6_id, v_c_q7_id, v_c_q8_id)::JSONB,
    v_teacher_id, CURRENT_TIMESTAMP - INTERVAL '48 days')
ON CONFLICT DO NOTHING;

-- Writing 1: Submitted late but graded
INSERT INTO assignment.assignment_submissions (id, assignment_id, user_id, status, started_at, submitted_at, score, image_urls, feedback, graded_by, graded_at)
VALUES (uuid_generate_v4(), v_closed_writing1_id, v_learner4_id, 'GRADED', CURRENT_TIMESTAMP - INTERVAL '46 days', CURRENT_TIMESTAMP - INTERVAL '44 days 5 hours', 75.0::numeric, '["https://res.cloudinary.com/dtn0i6fvs/image/upload/v1774022189/satori/assignment-submissions/b0000000-0000-0000-0000-000000000013/a0000000-0000-0000-0000-000000000007/sample3.jpg"]'::JSONB, 'Bài viết tương đối tốt, nhưng nên cấu trúc lại câu cho rõ ràng hơn. Một số lỗi nhỏ cần sửa.', v_teacher_id, CURRENT_TIMESTAMP - INTERVAL '42 days')
ON CONFLICT DO NOTHING;

-- Translation 1: Submitted and graded (Perfect score)
INSERT INTO assignment.assignment_submissions (id, assignment_id, user_id, status, started_at, submitted_at, score, image_urls, feedback, graded_by, graded_at)
VALUES (uuid_generate_v4(), v_closed_translation1_id, v_learner4_id, 'GRADED', CURRENT_TIMESTAMP - INTERVAL '38 days', CURRENT_TIMESTAMP - INTERVAL '37 days 8 hours', 100.0::numeric, '["https://res.cloudinary.com/dtn0i6fvs/image/upload/v1774022189/satori/assignment-submissions/b0000000-0000-0000-0000-000000000014/a0000000-0000-0000-0000-000000000007/sample4.jpg"]'::JSONB, 'Xuất sắc! Dịch hoàn hảo, không có lỗi nào. Đây là một bài dịch mẫu mực.', v_teacher_id, CURRENT_TIMESTAMP - INTERVAL '36 days')
ON CONFLICT DO NOTHING;

-- 7. Bind assignment targets to closed class
INSERT INTO assignment.assignment_targets (assignment_id, class_id, assigned_by)
VALUES (v_closed_quiz1_id, v_closed_class_id, v_teacher_id),
       (v_closed_quiz2_id, v_closed_class_id, v_teacher_id),
       (v_closed_writing1_id, v_closed_class_id, v_teacher_id),
       (v_closed_translation1_id, v_closed_class_id, v_teacher_id)
ON CONFLICT DO NOTHING;

-- 8. Assign closed class teacher via history table
INSERT INTO user_management.class_teacher_history (id, class_id, teacher_id, assigned_at, released_at, created_at, created_by)
VALUES (uuid_generate_v4(), v_closed_class_id, v_teacher_id, CURRENT_DATE - INTERVAL '120 days', CURRENT_DATE - INTERVAL '30 days', CURRENT_TIMESTAMP - INTERVAL '30 days', v_manager_id)
ON CONFLICT DO NOTHING;

RAISE NOTICE 'Mock data for Closed Class and completed assignments seeded successfully.';

END $$;

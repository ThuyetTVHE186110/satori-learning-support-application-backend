-- ==========================================================================
-- Script 23: Mock Assignment Data
-- Seeds assignments, questions, submissions, and class data for testing.
-- Teacher: Tanaka Sensei (a0000000-0000-0000-0000-000000000003)
-- Learner 1: Nguyen Van A (a0000000-0000-0000-0000-000000000004)
-- Learner 2: Tran Thi B (a0000000-0000-0000-0000-000000000005)
-- ==========================================================================

DO $$
DECLARE
    v_teacher_id  UUID := 'a0000000-0000-0000-0000-000000000003';
    v_learner1_id UUID := 'a0000000-0000-0000-0000-000000000004';
    v_learner2_id UUID := 'a0000000-0000-0000-0000-000000000005';
    v_class_id    UUID := 'c0000000-0000-0000-0000-000000000001';
    
    v_quiz1_id    UUID := 'b0000000-0000-0000-0000-000000000001';
    v_writing1_id UUID := 'b0000000-0000-0000-0000-000000000002';
    v_quiz2_id    UUID := 'b0000000-0000-0000-0000-000000000003';
    v_quiz3_id    UUID := 'b0000000-0000-0000-0000-000000000004';
    v_quiz4_id    UUID := 'b0000000-0000-0000-0000-000000000005';
    v_writing2_id UUID := 'b0000000-0000-0000-0000-000000000006';
    
    v_q1_id       UUID := 'e0000000-0000-0000-0000-000000000001';
    v_q2_id       UUID := 'e0000000-0000-0000-0000-000000000002';
    v_q3_id       UUID := 'e0000000-0000-0000-0000-000000000003';
    v_q4_id       UUID := 'e0000000-0000-0000-0000-000000000004';
    v_q5_id       UUID := 'e0000000-0000-0000-0000-000000000005';
BEGIN

-- 1. Create Class (if not exists)
IF NOT EXISTS (SELECT 1 FROM user_management.classes WHERE id = v_class_id) THEN
    INSERT INTO user_management.classes (id, name, description, teacher_id, max_students, is_active, created_at)
    VALUES (v_class_id, 'Lớp Tiếng Nhật N5 - Cấp tốc', 'Lớp học dành cho người mới bắt đầu, tập trung vào giao tiếp và JLPT N5.', v_teacher_id, 30, TRUE, CURRENT_TIMESTAMP);
    
    RAISE NOTICE 'Class created: Lớp Tiếng Nhật N5';
END IF;

-- 2. Add Class Members
INSERT INTO user_management.class_members (class_id, user_id, joined_at)
VALUES (v_class_id, v_learner1_id, CURRENT_TIMESTAMP),
       (v_class_id, v_learner2_id, CURRENT_TIMESTAMP)
ON CONFLICT (class_id, user_id) DO NOTHING;

-- 3. Create Quiz Questions
INSERT INTO assignment.quiz_questions (id, question_text, question_type, options, correct_answer, explanation, jlpt_level, created_by)
VALUES 
(v_q1_id, '「食べる」の読み方は何ですか？', 'multiple_choice', 
 '[{"id": "A", "text": "たべる"}, {"id": "B", "text": "のめる"}, {"id": "C", "text": "ねる"}]'::JSONB, 
 'A', '「食べる」 là động từ nhóm 2, đọc là たべる (Taberu).', 'N5', v_teacher_id),
(v_q2_id, 'Khoảng trống: わたしは　にほんご　_____　べんきょうします。', 'multiple_choice',
 '[{"id": "A", "text": "を"}, {"id": "B", "text": "に"}, {"id": "C", "text": "で"}]'::JSONB,
 'A', 'Trợ từ "を" dùng để chỉ tân ngữ trực tiếp của động từ べんきょうします.', 'N5', v_teacher_id),
(v_q3_id, '日本料理（にほんりょうり）は　おいしいですか？', 'true_false',
 '[{"id": "TRUE", "text": "はい"}, {"id": "FALSE", "text": "いいえ"}]'::JSONB,
 'TRUE', 'Món ăn Nhật thường được coi là ngon.', 'N5', v_teacher_id),
(v_q4_id, 'Dịch sang tiếng Nhật: "Chào buổi sáng"', 'fill_blank',
 null, 'おはようございます', 'Lời chào buổi sáng trang trọng.', 'N5', v_teacher_id),
(v_q5_id, 'Chọn trợ từ đúng: どこ　____　いきますか。', 'multiple_choice',
 '[{"id": "A", "text": "へ"}, {"id": "B", "text": "を"}, {"id": "C", "text": "が"}]'::JSONB,
 'A', 'Trợ từ "へ" chỉ phương hướng di chuyển.', 'N5', v_teacher_id)
ON CONFLICT (id) DO NOTHING;

-- 4. Create Assignments
-- Assignment 1: Quiz (PUBLISHED)
INSERT INTO assignment.assignments (id, title, description, assignment_type, class_id, due_date, status, has_submissions, created_by)
VALUES (v_quiz1_id, 'Kiểm tra Từ vựng & Ngữ pháp Bài 1', 'Bài kiểm tra nhanh về kiến thức cơ bản đã học trong tuần đầu tiên.', 'QUIZ', v_class_id, CURRENT_TIMESTAMP + INTERVAL '7 days', 'PUBLISHED', TRUE, v_teacher_id)
ON CONFLICT (id) DO NOTHING;

INSERT INTO assignment.assignment_questions (assignment_id, question_id, order_index, points)
VALUES (v_quiz1_id, v_q1_id, 0, 3), (v_quiz1_id, v_q2_id, 1, 3), (v_quiz1_id, v_q3_id, 2, 4)
ON CONFLICT (assignment_id, question_id) DO NOTHING;

-- Assignment 2: Writing (PUBLISHED)
INSERT INTO assignment.assignments (id, title, description, assignment_type, class_id, due_date, status, has_submissions, created_by)
VALUES (v_writing1_id, 'Giới thiệu bản thân (自己紹介)', 'Hãy viết một đoạn văn ngắn (50-100 chữ) giới thiệu về bản thân bằng tiếng Nhật.', 'WRITING', v_class_id, CURRENT_TIMESTAMP + INTERVAL '10 days', 'PUBLISHED', TRUE, v_teacher_id)
ON CONFLICT (id) DO NOTHING;

INSERT INTO assignment.writing_assignments (assignment_id, prompt)
VALUES (v_writing1_id, 'Viết đoạn văn giới thiệu bản thân bao gồm: Tên, quốc tịch, công việc/trường học và sở thích.')
ON CONFLICT (assignment_id) DO NOTHING;

-- Assignment 3: Quiz (DRAFT)
INSERT INTO assignment.assignments (id, title, description, assignment_type, class_id, due_date, status, created_by)
VALUES (v_quiz2_id, 'Bản thảo Kiểm tra Giữa kỳ', 'Đề thi thử giữa kỳ - GV đang biên soạn.', 'QUIZ', v_class_id, CURRENT_TIMESTAMP + INTERVAL '30 days', 'DRAFT', v_teacher_id)
ON CONFLICT (id) DO NOTHING;

-- Assignment 4: Quiz (CLOSED)
INSERT INTO assignment.assignments (id, title, description, assignment_type, class_id, due_date, status, has_submissions, created_by)
VALUES (v_quiz3_id, 'Kiểm tra đọc hiểu N5 (Đã kết thúc)', 'Bài tập đọc hiểu Hiragana cơ bản.', 'QUIZ', v_class_id, CURRENT_TIMESTAMP - INTERVAL '1 day', 'CLOSED', TRUE, v_teacher_id)
ON CONFLICT (id) DO NOTHING;
 
-- Assignment 5: New Quiz (PUBLISHED - No Submissions)
INSERT INTO assignment.assignments (id, title, description, assignment_type, class_id, due_date, status, has_submissions, created_by)
VALUES (v_quiz4_id, 'Kiểm tra bổ sung: Trợ từ và Chào hỏi', 'Bài tập luyện tập thêm về các trợ từ cơ bản và câu chào hỏi.', 'QUIZ', v_class_id, CURRENT_TIMESTAMP + INTERVAL '14 days', 'PUBLISHED', FALSE, v_teacher_id)
ON CONFLICT (id) DO NOTHING;
 
INSERT INTO assignment.assignment_questions (assignment_id, question_id, order_index, points)
VALUES (v_quiz4_id, v_q4_id, 0, 5), (v_quiz4_id, v_q5_id, 1, 5)
ON CONFLICT (assignment_id, question_id) DO NOTHING;
 
-- Assignment 6: New Writing (PUBLISHED - No Submissions)
INSERT INTO assignment.assignments (id, title, description, assignment_type, class_id, due_date, status, has_submissions, created_by)
VALUES (v_writing2_id, 'Kể về gia đình của bạn', 'Hãy viết một đoạn văn ngắn mô tả về gia đình bạn (số lượng thành viên, nghề nghiệp...).', 'WRITING', v_class_id, CURRENT_TIMESTAMP + INTERVAL '12 days', 'PUBLISHED', FALSE, v_teacher_id)
ON CONFLICT (id) DO NOTHING;
 
INSERT INTO assignment.writing_assignments (assignment_id, prompt)
VALUES (v_writing2_id, 'Viết về gia đình bạn bằng tiếng Nhật. Sử dụng các từ vựng đã học về gia đình.')
ON CONFLICT (assignment_id) DO NOTHING;

-- 5. Create Submissions
-- Learner 1: Graded Quiz
INSERT INTO assignment.assignment_submissions (id, assignment_id, user_id, status, started_at, submitted_at, score, correct_count, total_questions, time_spent_seconds, answers, graded_by, graded_at)
VALUES (uuid_generate_v4(), v_quiz1_id, v_learner1_id, 'GRADED', CURRENT_TIMESTAMP - INTERVAL '2 days', CURRENT_TIMESTAMP - INTERVAL '2 days 1 hour', 10.0, 3, 3, 600, 
    format('[{"questionId": "%s", "selectedAnswer": "A", "isCorrect": true}, {"questionId": "%s", "selectedAnswer": "A", "isCorrect": true}, {"questionId": "%s", "selectedAnswer": "TRUE", "isCorrect": true}]', v_q1_id, v_q2_id, v_q3_id)::JSONB,
    v_teacher_id, CURRENT_TIMESTAMP - INTERVAL '1 day')
ON CONFLICT DO NOTHING;

-- Learner 1: In Progress Writing
INSERT INTO assignment.assignment_submissions (id, assignment_id, user_id, status, started_at, time_spent_seconds)
VALUES (uuid_generate_v4(), v_writing1_id, v_learner1_id, 'IN_PROGRESS', CURRENT_TIMESTAMP - INTERVAL '30 minutes', 120)
ON CONFLICT DO NOTHING;

-- Learner 2: Submitted Writing (Waiting for Grade)
INSERT INTO assignment.assignment_submissions (id, assignment_id, user_id, status, started_at, submitted_at, written_answer, time_spent_seconds)
VALUES (uuid_generate_v4(), v_writing1_id, v_learner2_id, 'SUBMITTED', CURRENT_TIMESTAMP - INTERVAL '5 hours', CURRENT_TIMESTAMP - INTERVAL '4 hours', 'はじめまして。私はチャンです。ベトナムから来ました。趣味は読書です。よろしくお願いします。', 900)
ON CONFLICT DO NOTHING;

-- Learner 2: Overdue Quiz
INSERT INTO assignment.assignment_submissions (id, assignment_id, user_id, status, started_at, score)
VALUES (uuid_generate_v4(), v_quiz3_id, v_learner2_id, 'OVERDUE', CURRENT_TIMESTAMP - INTERVAL '2 days', 0.0)
ON CONFLICT DO NOTHING;

-- 6. Bind targets (Mapping assignments to class)
INSERT INTO assignment.assignment_targets (assignment_id, class_id, assigned_by)
VALUES (v_quiz1_id, v_class_id, v_teacher_id),
       (v_writing1_id, v_class_id, v_teacher_id),
       (v_quiz2_id, v_class_id, v_teacher_id),
       (v_quiz3_id, v_class_id, v_teacher_id),
       (v_quiz4_id, v_class_id, v_teacher_id),
       (v_writing2_id, v_class_id, v_teacher_id)
ON CONFLICT DO NOTHING;

RAISE NOTICE 'Mock data for Assignment module seeded successfully.';

END $$;

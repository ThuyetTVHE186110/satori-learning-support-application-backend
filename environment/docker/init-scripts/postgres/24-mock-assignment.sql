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
    v_quiz5_id    UUID := 'b0000000-0000-0000-0000-000000000007';
    v_quiz6_id    UUID := 'b0000000-0000-0000-0000-000000000008';
    
    v_q1_id       UUID := 'e0000000-0000-0000-0000-000000000001';
    v_q2_id       UUID := 'e0000000-0000-0000-0000-000000000002';
    v_q3_id       UUID := 'e0000000-0000-0000-0000-000000000003';
    v_q4_id       UUID := 'e0000000-0000-0000-0000-000000000004';
    v_q5_id       UUID := 'e0000000-0000-0000-0000-000000000005';
    v_q6_id       UUID := 'e0000000-0000-0000-0000-000000000006';
    v_q7_id       UUID := 'e0000000-0000-0000-0000-000000000007';
    v_q8_id       UUID := 'e0000000-0000-0000-0000-000000000008';
    v_q9_id       UUID := 'e0000000-0000-0000-0000-000000000009';
    v_q10_id      UUID := 'e0000000-0000-0000-0000-000000000010';
    v_q11_id      UUID := 'e0000000-0000-0000-0000-000000000011';
    v_q12_id      UUID := 'e0000000-0000-0000-0000-000000000012';
    v_q13_id      UUID := 'e0000000-0000-0000-0000-000000000013';
    v_q14_id      UUID := 'e0000000-0000-0000-0000-000000000014';
    v_q15_id      UUID := 'e0000000-0000-0000-0000-000000000015';
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
(v_q1_id, '「[食]{た}べる」の[読]{よ}み[方]{かた}は[何]{なん}ですか？', 'multiple_choice', 
 '[{"id": "A", "text": "[食]{た}べる"}, {"id": "B", "text": "[飲]{の}める"}, {"id": "C", "text": "[寝]{ね}る"}]'::JSONB, 
 'A', '「[食]{た}べる」 là động từ nhóm 2, đọc là たべる (Taberu).', 'N5', v_teacher_id),
(v_q2_id, 'Khoảng trống: わたしは ++[日本語]{にほんご}++ _____ [勉強]{べんきょう}します。', 'multiple_choice',
 '[{"id": "A", "text": "を"}, {"id": "B", "text": "に"}, {"id": "C", "text": "で"}]'::JSONB,
 'A', 'Trợ từ "を" dùng để chỉ tân ngữ trực tiếp của động từ べんきょうします.', 'N5', v_teacher_id),
(v_q3_id, '++[日本料理]{にほんりょうり}++ là gì?', 'multiple_choice',
 '[{"id": "A", "text": "Món ăn Nhật"}, {"id": "B", "text": "Món ăn Trung"}, {"id": "C", "text": "Món ăn Hàn"}]'::JSONB,
 'A', '日本料理 (Nihon ryouri) là món ăn Nhật Bản.', 'N5', v_teacher_id),
(v_q4_id, 'Chọn câu chào ++"Chào buổi sáng"++ đúng:', 'multiple_choice',
 '[{"id": "A", "text": "おはようございます"}, {"id": "B", "text": "こんにちは"}, {"id": "C", "text": "こんばんは"}]'::JSONB,
 'A', 'おはようございます (Ohayou gozaimasu) là lời chào buổi sáng.', 'N5', v_teacher_id),
(v_q5_id, 'Chọn trợ từ đúng: どこ ____ [行]{い}きますか。', 'multiple_choice',
 '[{"id": "A", "text": "へ"}, {"id": "B", "text": "を"}, {"id": "C", "text": "が"}]'::JSONB,
 'A', 'Trợ từ "へ" chỉ phương hướng di chuyển.', 'N5', v_teacher_id),
-- New Questions for QUIZ 5 (Adjectives)
(v_q6_id, '[性癖]{せいへき}「[赤]{あか}い」 có nghĩa là gì?', 'multiple_choice', 
 '[{"id": "A", "text": "Màu đỏ"}, {"id": "B", "text": "Màu xanh"}, {"id": "C", "text": "Màu trắng"}]'::JSONB, 
 'A', '[赤]{あか}い (Akai) có nghĩa là màu đỏ.', 'N5', v_teacher_id),
(v_q7_id, 'Ngược nghĩa với 「[大]{おお}きい」(To) là gì?', 'multiple_choice', 
 '[{"id": "A", "text": "[小]{ちい}さい"}, {"id": "B", "text": "[高]{たか}い"}, {"id": "C", "text": "[近]{ちか}い"}]'::JSONB, 
 'A', '[大]{おお}きい (Ookii) là to lớn, ngược lại là [小]{ちい}さい (Chiisai) là nhỏ.', 'N5', v_teacher_id),
(v_q8_id, '「[楽]{たの}しい」 nghĩa là gì?', 'multiple_choice', 
 '[{"id": "A", "text": "Vui vẻ"}, {"id": "B", "text": "Buồn bã"}, {"id": "C", "text": "Đáng sợ"}]'::JSONB, 
 'A', '[楽]{たの}しい (Tanoshii) nghĩa là vui vẻ, hào hứng.', 'N5', v_teacher_id),
(v_q9_id, '「[忙]{いそが}しい」 nghĩa là gì?', 'multiple_choice', 
 '[{"id": "A", "text": "Bận rộn"}, {"id": "B", "text": "Rảnh rỗi"}, {"id": "C", "text": "Mệt mỏi"}]'::JSONB, 
 'A', '[忙]{いそ가}しい (Isogashii) nghĩa là bận rộn.', 'N5', v_teacher_id),
(v_q10_id, '「[難]{むずか}しい」 nghĩa là gì?', 'multiple_choice', 
 '[{"id": "A", "text": "Khó"}, {"id": "B", "text": "Dễ"}, {"id": "C", "text": "Thú vị"}]'::JSONB, 
 'A', '[難]{むずか}しい (Muzukashii) nghĩa là khó khăn.', 'N5', v_teacher_id),
-- New Questions for QUIZ 6 (Kanji)
(v_q11_id, 'Hán tự của ++"Mặt trời/Ngày"++ là gì?', 'multiple_choice', 
 '[{"id": "A", "text": "[日]{ひ}"}, {"id": "B", "text": "[月]{つき}"}, {"id": "C", "text": "[火]{ひ}"}]'::JSONB, 
 'A', '[日]{ひ} (Nichi/Hi) biểu thị mặt trời hoặc ngày.', 'N5', v_teacher_id),
(v_q12_id, 'Hán tự của ++"Mặt trăng/Tháng"++ là gì?', 'multiple_choice', 
 '[{"id": "A", "text": "[月]{つき}"}, {"id": "B", "text": "[日]{ひ}"}, {"id": "C", "text": "[水]{みず}"}]'::JSONB, 
 'A', '[月]{つき} (Getsu/Tsuki) biểu thị mặt trăng hoặc tháng.', 'N5', v_teacher_id),
(v_q13_id, 'Hán tự của ++"Cây"++ là gì?', 'multiple_choice', 
 '[{"id": "A", "text": "[木]{き}"}, {"id": "B", "text": "[本]{ほん}"}, {"id": "C", "text": "[休]{やす}み"}]'::JSONB, 
 'A', '[木]{き} (Moku/Ki) biểu thị cái cây.', 'N5', v_teacher_id),
(v_q14_id, 'Hán tự của ++"Núi"++ là gì?', 'multiple_choice', 
 '[{"id": "A", "text": "[山]{やま}"}, {"id": "B", "text": "[川]{かわ}"}, {"id": "C", "text": "[田]{だ}"}]'::JSONB, 
 'A', '[山]{やま} (San/Yama) biểu thị ngọn núi.', 'N5', v_teacher_id),
(v_q15_id, 'Hán tự của ++"Nước"++ là gì?', 'multiple_choice', 
 '[{"id": "A", "text": "[水]{みず}"}, {"id": "B", "text": "[金]{かね}"}, {"id": "C", "text": "[土]{つち}"}]'::JSONB, 
 'A', '[水]{みず} (Sui/Mizu) biểu thị nước.', 'N5', v_teacher_id)
ON CONFLICT (id) DO NOTHING;

-- 4. Create Assignments
-- Assignment 1: Quiz (PUBLISHED)
INSERT INTO assignment.assignments (id, title, description, assignment_type, class_id, due_date, status, has_submissions, created_by)
VALUES (v_quiz1_id, 'Kiểm tra Từ vựng & Ngữ pháp bài 1', 'Bài kiểm tra nhanh về kiến thức cơ bản đã học trong tuần đầu tiên.', 'QUIZ', v_class_id, CURRENT_TIMESTAMP + INTERVAL '7 days', 'PUBLISHED', TRUE, v_teacher_id)
ON CONFLICT (id) DO NOTHING;

INSERT INTO assignment.assignment_questions (assignment_id, question_id, order_index, points)
VALUES (v_quiz1_id, v_q1_id, 0, 3), (v_quiz1_id, v_q2_id, 1, 3), (v_quiz1_id, v_q3_id, 2, 4)
ON CONFLICT (assignment_id, question_id) DO NOTHING;

-- Assignment 2: Writing (PUBLISHED)
INSERT INTO assignment.assignments (id, title, description, assignment_type, class_id, due_date, status, has_submissions, created_by)
VALUES (v_writing1_id, 'Giới thiệu bản thân', 'Hãy viết một đoạn văn ngắn (50-100 chữ) giới thiệu về bản thân bằng tiếng Nhật.', 'WRITING', v_class_id, CURRENT_TIMESTAMP + INTERVAL '10 days', 'PUBLISHED', TRUE, v_teacher_id)
ON CONFLICT (id) DO NOTHING;

INSERT INTO assignment.writing_assignments (assignment_id, prompt)
VALUES (v_writing1_id, 'Viết đoạn văn ++[自己紹介]{じこしょうかい}++ bao gồm: [名前]{なまえ}, [国]{くに}, [仕事]{しごと}/[学校]{がっこう} và [趣味]{しゅみ}.')
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

-- Assignment 7: Quiz Adjectives (PUBLISHED)
INSERT INTO assignment.assignments (id, title, description, assignment_type, class_id, due_date, status, has_submissions, created_by)
VALUES (v_quiz5_id, 'Luyện tập Tính từ N5', 'Kiểm tra ý nghĩa các tính từ đuôi -i thông dụng trong N5.', 'QUIZ', v_class_id, CURRENT_TIMESTAMP + INTERVAL '10 days', 'PUBLISHED', FALSE, v_teacher_id)
ON CONFLICT (id) DO NOTHING;

INSERT INTO assignment.assignment_questions (assignment_id, question_id, order_index, points)
VALUES (v_quiz5_id, v_q6_id, 0, 2), (v_quiz5_id, v_q7_id, 1, 2), (v_quiz5_id, v_q8_id, 2, 2), (v_quiz5_id, v_q9_id, 3, 2), (v_quiz5_id, v_q10_id, 4, 2)
ON CONFLICT (assignment_id, question_id) DO NOTHING;

-- Assignment 8: Quiz Kanji (PUBLISHED)
INSERT INTO assignment.assignments (id, title, description, assignment_type, class_id, due_date, status, has_submissions, created_by)
VALUES (v_quiz6_id, 'Hán tự cơ bản: Thiên nhiên', 'Nhận diện các chữ Hán cơ bản về chủ đề thiên nhiên.', 'QUIZ', v_class_id, CURRENT_TIMESTAMP + INTERVAL '12 days', 'PUBLISHED', FALSE, v_teacher_id)
ON CONFLICT (id) DO NOTHING;

INSERT INTO assignment.assignment_questions (assignment_id, question_id, order_index, points)
VALUES (v_quiz6_id, v_q11_id, 0, 2), (v_quiz6_id, v_q12_id, 1, 2), (v_quiz6_id, v_q13_id, 2, 2), (v_quiz6_id, v_q14_id, 3, 2), (v_quiz6_id, v_q15_id, 4, 2)
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
VALUES (uuid_generate_v4(), v_quiz1_id, v_learner1_id, 'GRADED', CURRENT_TIMESTAMP - INTERVAL '2 days', CURRENT_TIMESTAMP - INTERVAL '2 days 1 hour', 100.0, 3, 3, 600,
    format('[{"questionId": "%s", "selectedAnswer": "A", "isCorrect": true}, {"questionId": "%s", "selectedAnswer": "A", "isCorrect": true}, {"questionId": "%s", "selectedAnswer": "A", "isCorrect": true}]', v_q1_id, v_q2_id, v_q3_id)::JSONB,
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
       (v_quiz5_id, v_class_id, v_teacher_id),
       (v_quiz6_id, v_class_id, v_teacher_id),
       (v_writing2_id, v_class_id, v_teacher_id)
ON CONFLICT DO NOTHING;

RAISE NOTICE 'Mock data for Assignment module seeded successfully.';

END $$;

-- Mock data for class_teacher_history
-- This links teachers to classes after the teacher_id column was removed from the classes table.
-- Teacher: Tanaka Sensei (a0000000-0000-0000-0000-000000000003)
-- Class: Lớp Tiếng Nhật N5 - Cấp tốc (c0000000-0000-0000-0000-000000000001)

INSERT INTO user_management.class_teacher_history (
    id, teacher_id, class_id, assigned_at, released_at, created_at, created_by
) VALUES 
    -- 1. Tanaka Sensei (Current Teacher of Class N5)
    (
        'e0000000-0000-0000-0000-000000000001', 
        'a0000000-0000-0000-0000-000000000003', -- teacher_id
        'c0000000-0000-0000-0000-000000000001', -- class_id
        '2025-01-01',                          -- assigned_at
        NULL,                                  -- released_at (active)
        CURRENT_TIMESTAMP, 
        'a0000000-0000-0000-0000-000000000001'-- created_by (admin)
    ),

    -- 2. Historical entry: Another teacher who previously taught this class (Optional/Example)
    (
        'e0000000-0000-0000-0000-000000000002', 
        'a0000000-0000-0000-0000-000000000003', -- teacher_id
        'c0000000-0000-0000-0000-000000000001', -- class_id (historical assignment)
        '2024-06-01',                          -- assigned_at
        '2024-12-31',                          -- released_at (ended)
        CURRENT_TIMESTAMP, 
        'a0000000-0000-0000-0000-000000000001' -- created_by (admin)
    )
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------
-- MOCK LEARNING CONTENT (Lessons, Vocab, Grammar)
-- ---------------------------------------------------------

DO $$
DECLARE
    v_course_id   UUID := 'd0000000-0000-0000-0000-000000000001';
    v_teacher_id  UUID := 'a0000000-0000-0000-0000-000000000003';
    
    v_lesson1_id  UUID := 'f0000000-0000-0000-0000-000000000001';
    v_lesson2_id  UUID := 'f0000000-0000-0000-0000-000000000002';
    
    v_vocab1_id   UUID := '10000000-0000-0000-0000-000000000001';
    v_vocab2_id   UUID := '10000000-0000-0000-0000-000000000002';
    
    v_grammar1_id UUID := '20000000-0000-0000-0000-000000000001';
BEGIN

    -- 1. Create Lessons
    INSERT INTO learning_content.lessons (id, course_id, title, title_japanese, description, order_index, status, created_by)
    VALUES
    (v_lesson1_id, v_course_id, 'Chào hỏi cơ bản', 'あいさつ', 'Các mẫu câu chào hỏi thông dụng trong tiếng Nhật.', 1, 'PUBLISHED', v_teacher_id),
    (v_lesson2_id, v_course_id, 'Giới thiệu bản thân', '自己紹介', 'Cách giới thiệu tên, quốc tịch và nghề nghiệp.',  2, 'PUBLISHED', v_teacher_id)
    ON CONFLICT (id) DO NOTHING;

    -- 2. Create Vocabulary
    INSERT INTO learning_content.vocabulary (id, japanese_text, reading, meaning_vi, jlpt_level, created_by)
    VALUES
    (v_vocab1_id, 'おはよう', 'おはよう', 'Chào buổi sáng', 'N5', v_teacher_id),
    (v_vocab2_id, 'わたし', 'わたし', 'Tôi (bản thân)', 'N5', v_teacher_id)
    ON CONFLICT (id) DO NOTHING;

    -- 3. Create Grammar Points
    INSERT INTO learning_content.grammar_points (id, pattern, pattern_reading, meaning_vi, short_explanation, jlpt_level, category, created_by)
    VALUES
    (v_grammar1_id, '～は ～です', '～は ～です', 'Là...', 'Cấu trúc câu khẳng định cơ bản nhất.', 'N5', 'SENTENCE_PATTERN', v_teacher_id)
    ON CONFLICT (id) DO NOTHING;

    -- 4. Map Content to Lessons
    INSERT INTO learning_content.lesson_vocabulary (lesson_id, vocabulary_id, order_index)
    VALUES (v_lesson1_id, v_vocab1_id, 1), (v_lesson2_id, v_vocab2_id, 1)
    ON CONFLICT (lesson_id, vocabulary_id) DO NOTHING;

    INSERT INTO learning_content.lesson_grammar (lesson_id, grammar_id, order_index)
    VALUES (v_lesson2_id, v_grammar1_id, 1)
    ON CONFLICT (lesson_id, grammar_id) DO NOTHING;

    RAISE NOTICE 'Mock learning content seeded successfully.';
END $$;

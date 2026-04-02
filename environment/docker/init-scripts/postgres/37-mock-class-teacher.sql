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


-- Mục đích: Tạo bảng lưu lịch sử giáo viên dạy lớp và cập nhật view active classes để không chứa thông tin giáo viên trực tiếp.
DROP VIEW IF EXISTS user_management.v_active_classes;
ALTER TABLE user_management.classes DROP IF EXISTS teacher_id;
CREATE OR REPLACE VIEW user_management.v_active_classes AS
SELECT * FROM user_management.classes
WHERE deleted_at IS NULL AND is_active = TRUE;

-- Lịch sử giáo viên dạy lớp: Một lớp có thể có nhiều giáo viên qua các thời kỳ, và một giáo viên có thể dạy nhiều lớp. Bảng này sẽ lưu lại lịch sử đó, bao gồm thời gian bắt đầu và kết thúc dạy của mỗi giáo viên cho mỗi lớp.
DROP TABLE IF EXISTS user_management.class_teacher_history;

CREATE TABLE user_management.class_teacher_history
(
    -- Khóa chính sử dụng UUID
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),

    -- Các khóa ngoại kết nối tới bảng Teacher (User) và Class
    teacher_id  UUID NOT NULL,
    class_id    UUID NOT NULL,

    -- Thông tin nghiệp vụ chính
    assigned_at DATE NOT NULL DEFAULT CURRENT_TIMESTAMP ,
    released_at DATE,

    -- Các trường Audit (Dùng TIMESTAMP WITH TIME ZONE để chuẩn hóa múi giờ)
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_by  UUID,
    updated_by  UUID,

    CONSTRAINT fk_cth_teacher
        FOREIGN KEY (teacher_id)
            REFERENCES user_management.users(id)
            ON DELETE CASCADE, -- Nếu xóa user, xóa luôn lịch sử dạy (hoặc dùng SET NULL tùy bạn)

    CONSTRAINT fk_cth_class
        FOREIGN KEY (class_id)
            REFERENCES user_management.classes(id)
            ON DELETE CASCADE,
    -- Ràng buộc logic: Ngày nghỉ dạy không được trước ngày vào dạy
    CONSTRAINT check_dates CHECK (released_at IS NULL OR released_at >= assigned_at)
);

-- Tạo Index để truy vấn lịch sử của 1 lớp hoặc 1 giáo viên nhanh hơn
CREATE INDEX idx_cth_class_id ON user_management.class_teacher_history (class_id);
CREATE INDEX idx_cth_teacher_id ON user_management.class_teacher_history (teacher_id);
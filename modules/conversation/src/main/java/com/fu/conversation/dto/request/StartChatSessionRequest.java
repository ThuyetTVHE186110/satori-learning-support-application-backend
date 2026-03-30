package com.fu.conversation.dto.request;

import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.UUID;

/**
 * Request tạo phiên chat hỏi đáp nội dung giáo trình.
 *
 * courseId KHÔNG bắt buộc — backend tự resolve từ lớp học đang active của learner.
 * Quy tắc nghiệp vụ: 1 learner tối đa 1 lớp active tại 1 thời điểm → 1 course duy nhất.
 * Truyền courseId khi frontend muốn override tường minh (ví dụ: từ trang course detail).
 */
@Data
public class StartChatSessionRequest {

    /** UUID của khóa học (optional). Nếu null → backend tự resolve từ lớp đang học. */
    private UUID courseId;

    /** UUID của bài học cụ thể (optional). Nếu null → search toàn khóa học. */
    private UUID lessonId;

    /** Cấp độ JLPT của người học (optional, dùng để tùy chỉnh system prompt). */
    @Pattern(regexp = "^(N[1-5])?$", message = "jlptLevel phải là N1, N2, N3, N4 hoặc N5")
    private String jlptLevel;
}

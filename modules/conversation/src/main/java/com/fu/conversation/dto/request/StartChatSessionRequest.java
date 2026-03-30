package com.fu.conversation.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.UUID;

/**
 * Request tạo phiên chat hỏi đáp nội dung giáo trình.
 */
@Data
public class StartChatSessionRequest {

    /** UUID của khóa học cần hỏi. Bắt buộc. */
    @NotNull(message = "courseId không được để trống")
    private UUID courseId;

    /** UUID của bài học cụ thể (optional). Nếu null → search toàn khóa học. */
    private UUID lessonId;

    /** Cấp độ JLPT của người học (optional, dùng để tùy chỉnh system prompt). */
    @Pattern(regexp = "^(N[1-5])?$", message = "jlptLevel phải là N1, N2, N3, N4 hoặc N5")
    private String jlptLevel;
}

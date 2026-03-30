package com.fu.conversation.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Response cho mỗi lượt chat trong phiên hỏi đáp giáo trình.
 */
@Data
@Builder
public class ChatMessageResponse {

    private UUID sessionId;
    private UUID messageId;

    /** Câu trả lời từ AI (tiếng Việt + ví dụ tiếng Nhật) */
    private String content;

    /**
     * Danh sách nguồn tham chiếu được dùng để tạo câu trả lời.
     * Frontend có thể hiển thị "Nguồn: Bài 3 - Từ vựng: 食べる"
     */
    private List<ChatSourceReference> sources;

    /** True nếu không tìm thấy thông tin liên quan trong giáo trình */
    private boolean noContextFound;

    private LocalDateTime timestamp;
}

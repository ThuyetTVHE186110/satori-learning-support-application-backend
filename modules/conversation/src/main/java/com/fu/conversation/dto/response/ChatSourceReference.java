package com.fu.conversation.dto.response;

import lombok.Builder;
import lombok.Data;

/**
 * Tham chiếu nguồn được dùng trong câu trả lời RAG.
 * Hiển thị cho learner biết câu trả lời được lấy từ bài học nào.
 */
@Data
@Builder
public class ChatSourceReference {

    /** Loại nội dung: VOCABULARY, GRAMMAR, GRAMMAR_EXPLANATION, DIALOG, EXAMPLE */
    private String contentType;

    /** Văn bản tiếng Nhật chính (từ vựng hoặc mẫu câu) */
    private String japaneseText;

    /** Nghĩa tiếng Việt (nếu có) */
    private String meaningVi;

    /** Tên bài học */
    private String lessonTitle;

    /** Số bài học (orderIndex) */
    private Integer lessonNumber;

    /** Độ tương đồng cosine (0.0 - 1.0) */
    private double similarity;
}

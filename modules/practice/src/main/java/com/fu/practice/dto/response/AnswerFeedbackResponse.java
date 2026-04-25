package com.fu.practice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Phản hồi sau khi học viên nộp câu trả lời.
 * Lúc này mới reveal correctAnswer và explanation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnswerFeedbackResponse {

    private boolean isCorrect;
    private int score;

    /** Nhận xét ngắn gọn bằng tiếng Việt. */
    private String feedback;

    /**
     * Đáp án đúng — chỉ reveal sau khi nộp.
     * Với các dạng chọn option (MC, TRUE_FALSE, FILL_BLANK, TRANSLATION): là integer id.
     * Với SENTENCE_ORDER: "0,1,2,3". Với MATCHING: "0:4,1:5". Dùng để highlight UI.
     */
    private String correctAnswer;

    /**
     * Text đáp án đúng (resolved từ options) — để FE hiển thị trực tiếp không cần lookup.
     * Ví dụ: "先生" với MC/FILL_BLANK, "Đúng" với TRUE_FALSE,
     * "これは辞書です" với SENTENCE_ORDER (token được ghép theo thứ tự id đúng).
     * null cho MATCHING và các trường hợp không resolve được.
     */
    private String correctAnswerText;

    /**
     * Echo text đáp án user đã chọn (resolved từ options).
     * null nếu không resolve được (user gửi sai format, legacy text-answer, v.v.).
     */
    private String userAnswerText;

    /** Giải thích chi tiết bằng tiếng Việt (từ AI hoặc rule-based). */
    private String explanation;

    /** Liệu phiên đã hoàn thành sau câu này không. */
    private boolean sessionCompleted;

    /**
     * Câu hỏi tiếp theo — trả về ngay để FE hiển thị không cần gọi thêm request.
     * null nếu sessionCompleted = true hoặc nếu việc sinh câu hỏi thất bại.
     */
    private PracticeItemResponse nextItem;

    // ── Mastery delta ───────────────────────────────────────────────────────

    /**
     * Mastery level trước khi trả lời câu này.
     * null nếu item không có refContentId (không track được).
     */
    private Integer masteryLevelBefore;

    /**
     * Mastery level sau khi trả lời câu này.
     * null nếu item không có refContentId.
     */
    private Integer masteryLevelAfter;

    /**
     * Từ / mẫu ngữ pháp tiếng Nhật của item vừa trả lời.
     * Ví dụ: "先生" — dùng để hiển thị "先生: ◔ → ◑" trên UI.
     * null nếu không track được.
     */
    private String entityJapanese;
}

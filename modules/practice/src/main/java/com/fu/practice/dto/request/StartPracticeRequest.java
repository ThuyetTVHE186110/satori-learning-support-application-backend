package com.fu.practice.dto.request;

import com.fu.practice.entity.PracticeItemType;
import com.fu.practice.entity.PracticePreset;
import com.fu.practice.entity.PracticeSessionType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Request bắt đầu một phiên luyện tập mới.
 *
 * Hai chế độ hoạt động:
 *
 * AUTO MODE (selectedVocabIds và selectedGrammarIds đều null/rỗng):
 *   - Hệ thống tự chọn vocab/grammar theo sessionType
 *   - Số câu hỏi = preset.itemCount (mặc định STANDARD = 10)
 *
 * EXPLICIT SELECTION MODE (có ít nhất 1 trong 2 list):
 *   - Học viên tự chọn từng vocab/grammar muốn luyện
 *   - Số câu hỏi = tổng số items được chọn (tối đa 20)
 *   - Nếu chọn > 20 items: hệ thống ưu tiên items có mastery_level thấp nhất
 *   - preset bị bỏ qua
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StartPracticeRequest {

    @NotNull(message = "Vui lòng chọn bài học để luyện tập.")
    private UUID lessonId;

    @NotNull(message = "Vui lòng chọn loại phiên luyện tập.")
    private PracticeSessionType sessionType;

    /**
     * Preset số câu hỏi — chỉ dùng trong auto mode.
     * QUICK=5, STANDARD=10, FULL=20.
     * Mặc định: STANDARD.
     */
    @Builder.Default
    private PracticePreset preset = PracticePreset.STANDARD;

    /**
     * Dạng câu hỏi — optional.
     * Null/rỗng = hệ thống tự chọn theo sessionType.
     */
    @Size(max = 6, message = "Chọn tối đa 6 dạng câu hỏi.")
    private List<PracticeItemType> itemTypes;

    /**
     * Explicit selection: UUIDs của Vocabulary items muốn luyện.
     * Null/rỗng = auto mode (dùng tất cả vocab của bài học).
     */
    @Size(max = 50, message = "Chọn tối đa 50 từ vựng mỗi lần.")
    private List<UUID> selectedVocabIds;

    /**
     * Explicit selection: UUIDs của GrammarPoint items muốn luyện.
     * Null/rỗng = auto mode (dùng tất cả ngữ pháp của bài học).
     */
    @Size(max = 30, message = "Chọn tối đa 30 mẫu ngữ pháp mỗi lần.")
    private List<UUID> selectedGrammarIds;

    /** Convenience: kiểm tra có phải explicit mode không. */
    public boolean isExplicitMode() {
        return (selectedVocabIds != null && !selectedVocabIds.isEmpty())
            || (selectedGrammarIds != null && !selectedGrammarIds.isEmpty());
    }
}

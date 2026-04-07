package com.fu.practice.dto.request;

import com.fu.practice.entity.PracticeItemType;
import com.fu.practice.entity.PracticeSessionType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

    @Min(value = 1, message = "Số lượng câu hỏi tối thiểu là 1.")
    @Max(value = 20, message = "Số lượng câu hỏi tối đa là 20.")
    @Builder.Default
    private int itemCount = 10;

    /**
     * Dạng câu hỏi muốn luyện tập — optional.
     * Nếu để trống, hệ thống tự chọn dạng phù hợp với sessionType.
     * Có thể chọn nhiều dạng để luyện xen kẽ.
     * Ví dụ: [MULTIPLE_CHOICE, TRUE_FALSE] → chỉ sinh 2 dạng này.
     */
    @Size(max = 6, message = "Chọn tối đa 6 dạng câu hỏi.")
    private List<PracticeItemType> itemTypes;
}

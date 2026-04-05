package com.fu.practice.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request kiểm tra ngay lập tức một cặp MATCHING.
 * Không thay đổi trạng thái DB — chỉ dùng để phản hồi real-time trên UI.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VerifyPairRequest {

    @NotNull(message = "leftId không được null.")
    @Min(value = 0, message = "leftId phải >= 0.")
    private Integer leftId;

    @NotNull(message = "rightId không được null.")
    @Min(value = 0, message = "rightId phải >= 0.")
    private Integer rightId;
}

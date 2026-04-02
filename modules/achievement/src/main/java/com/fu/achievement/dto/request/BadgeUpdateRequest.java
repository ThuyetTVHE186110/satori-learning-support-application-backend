package com.fu.achievement.dto.request;

import com.fu.achievement.enums.BadgeType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Data;
import lombok.experimental.FieldDefaults;

@Data
@FieldDefaults(level = AccessLevel.PRIVATE)
public class BadgeUpdateRequest {

    @NotBlank(message = "Tên danh hiệu không được để trống")
    String name;

    String description;

    @NotNull(message = "Loại danh hiệu không được để trống")
    BadgeType badgeType;

    @NotNull(message = "Mức yêu cầu không được để trống")
    @Min(value = 1, message = "Mức yêu cầu tối thiểu là 1")
    Integer requirementValue;

    Integer expReward;
}

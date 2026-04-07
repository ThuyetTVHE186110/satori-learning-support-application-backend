package com.fu.achievement.dto.response;

import com.fu.achievement.enums.BadgeStatus;
import com.fu.achievement.enums.BadgeType;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class BadgeSummaryResponse {
    UUID id;
    String name;
    String description;
    BadgeType badgeType;
    Integer expReward;
    Integer requirementValue;
    String iconUrl;
    BadgeStatus status;
    LocalDateTime createdAt;
    Long achieversCount;
}

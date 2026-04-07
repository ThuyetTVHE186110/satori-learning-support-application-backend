package com.fu.achievement.dto.response;

import com.fu.achievement.enums.BadgeType;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class LearnerBadgeResponse {
    UUID badgeId;
    String name;
    String description;
    BadgeType badgeType;
    Integer requirementValue;
    Integer expReward;
    String iconUrl;
    Boolean earned;
    LocalDateTime earnedAt;
    Integer currentValue;
    Integer progressPercent;
    Boolean isFeatured;
}

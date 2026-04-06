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
public class UserAchievementResponse {
    UUID badgeId;
    String badgeName;
    String description;
    BadgeType badgeType;
    String iconUrl;
    Integer expReward;
    LocalDateTime earnedAt;
    Boolean isFeatured;
}

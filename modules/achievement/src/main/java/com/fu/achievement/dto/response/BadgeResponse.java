package com.fu.achievement.dto.response;

import com.fu.achievement.enums.BadgeStatus;
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
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class BadgeResponse {
    UUID id;
    String name;
    String description;
    BadgeType badgeType;
    Integer requirementValue;
    String iconUrl;
    Integer expReward;
    BadgeStatus status;
    LocalDateTime createdAt;

    Long achieversCount;
    //Learner with status ACTIVE, INACTIVE
    Long totalLearnerCount;
}

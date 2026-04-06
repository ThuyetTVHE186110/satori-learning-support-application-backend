package com.fu.achievement.dto.response;

import com.fu.achievement.enums.BadgeType;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class UserAchievementProgressDTO {
    UUID userId;
    
    // Current values for progress tracking
    Integer streakCurrent;
    Integer streakLongest;
    Integer practiceCount;
    Integer speakingCount;
    Integer currentLevel;
    Integer totalExp;
    
    // Progress toward each badge
    List<BadgeProgressItem> badgeProgress;
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @FieldDefaults(level = AccessLevel.PRIVATE)
    public static class BadgeProgressItem {
        UUID badgeId;
        String badgeName;
        BadgeType badgeType;
        Integer requirementValue;
        Integer currentValue;
        Boolean earned;
        
        public Integer getProgress() {
            if (requirementValue == null || requirementValue == 0) return 0;
            return Math.min(100, (currentValue * 100) / requirementValue);
        }
    }
}

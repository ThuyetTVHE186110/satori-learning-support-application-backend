package com.fu.achievement.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Notification response when user levels up
 * Returned in session/submission responses
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LevelUpNotificationResponse {
    
    /** Previous level before this action */
    private Integer previousLevel;
    
    /** New level after this action */
    private Integer newLevel;
    
    /** EXP gained from this action */
    private Integer expGained;
    
    /** Total EXP accumulated */
    private Integer totalExp;
    
    /** EXP needed to reach next level */
    private Integer expToNextLevel;
    
    /** EXP progress in current level (0-100%) */
    private Integer progressPercentage;
    
    /** True if level actually increased */
    private Boolean isLevelUp;
}

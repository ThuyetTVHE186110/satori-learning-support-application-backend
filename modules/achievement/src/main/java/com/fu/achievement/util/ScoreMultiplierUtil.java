package com.fu.achievement.util;

import java.math.BigDecimal;

/**
 * Utility for calculating score-based EXP multipliers.
 * Rewards high performance with additional EXP.
 * 
 * Formula: multiplier = 1.0 + (score / 100) * 0.5
 * 
 * Examples:
 * - Score 100%: 1.0 + (100/100)*0.5 = 1.5 (50% bonus)
 * - Score 80%:  1.0 + (80/100)*0.5 = 1.4 (40% bonus)
 * - Score 60%:  1.0 + (60/100)*0.5 = 1.3 (30% bonus)
 * - Score 40%:  1.0 + (40/100)*0.5 = 1.2 (20% bonus)
 * - Score 0%:   1.0 + (0/100)*0.5 = 1.0 (no bonus)
 */
public class ScoreMultiplierUtil {
    
    /**
     * Calculate EXP multiplier based on score
     * @param score Score from 0-100, or null for no bonus
     * @return Multiplier (1.0 to 1.5)
     */
    public static double calculateMultiplier(Integer score) {
        if (score == null || score < 0) {
            return 1.0;
        }
        
        // Cap score at 100 for calculation
        int cappedScore = Math.min(score, 100);
        
        // Formula: 1.0 + (score/100) * 0.5
        // Result range: [1.0, 1.5]
        return 1.0 + ((double) cappedScore / 100.0) * 0.5;
    }
    
    /**
     * Calculate EXP multiplier from BigDecimal score (e.g., submission.getScore())
     * @param score Score as BigDecimal (0-100)
     * @return Multiplier (1.0 to 1.5)
     */
    public static double calculateMultiplier(BigDecimal score) {
        if (score == null) {
            return 1.0;
        }
        return calculateMultiplier(score.intValue());
    }
    
    /**
     * Apply multiplier to base EXP
     * @param baseExp Base experience points
     * @param multiplier Score-based multiplier (1.0 to 1.5)
     * @return Final EXP amount (rounded down)
     */
    public static int applyMultiplier(int baseExp, double multiplier) {
        if (baseExp <= 0) {
            return 0;
        }
        return (int) Math.floor(baseExp * multiplier);
    }
    
    /**
     * All-in-one: Calculate final EXP from base + score
     * @param baseExp Base experience points
     * @param score Score 0-100
     * @return Final EXP (baseExp with score bonus applied)
     */
    public static int calculateFinalExp(int baseExp, Integer score) {
        double multiplier = calculateMultiplier(score);
        return applyMultiplier(baseExp, multiplier);
    }
    
    /**
     * All-in-one: Calculate final EXP from base + score (BigDecimal version)
     * @param baseExp Base experience points
     * @param score Score 0-100 as BigDecimal
     * @return Final EXP (baseExp with score bonus applied)
     */
    public static int calculateFinalExp(int baseExp, BigDecimal score) {
        double multiplier = calculateMultiplier(score);
        return applyMultiplier(baseExp, multiplier);
    }
}

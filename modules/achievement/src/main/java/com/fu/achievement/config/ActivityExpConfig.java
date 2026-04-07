package com.fu.achievement.config;

import com.fu.achievement.util.ScoreMultiplierUtil;

/**
 * Configuration for activity-based EXP rewards.
 * Each activity type has a multiplier applied to BASE_EXP.
 * 
 * BASE_EXP is now tier-based (see LevelExpTierConfig):
 * - Level 1-15: BASE_EXP = 30
 * - Level 16-30: BASE_EXP = 37
 * - Level 31-50: BASE_EXP = 45
 * 
 * Final formula: Activity_EXP = BASE_EXP(level) * ActivityMultiplier * ScoreMultiplier(score)
 */
public class ActivityExpConfig {
    
    /**
     * Default BASE_EXP when level is not provided (legacy compatibility)
     */
    public static final int DEFAULT_BASE_EXP = 30;
    
    /**
     * Activity types with their EXP multipliers
     */
    public enum ActivityType {
        /**
         * Quiz assignment completion: 50% of base
         * L1-15: 30 * 0.50 = 15 EXP (base)
         * L16-30: 37 * 0.50 = 18.5 EXP (base)
         * L31-50: 45 * 0.50 = 22.5 EXP (base)
         */
        QUIZ_ASSIGNMENT(0.50),
        
        /**
         * Writing assignment submission: 50% of base
         * L1-15: 30 * 0.50 = 15 EXP (base)
         * L16-30: 37 * 0.50 = 18.5 EXP (base)
         * L31-50: 45 * 0.50 = 22.5 EXP (base)
         */
        WRITING_ASSIGNMENT(0.50),
        
        /**
         * AI practice session completion: 80% of base
         * L1-15: 30 * 0.80 = 24 EXP (base)
         * L16-30: 37 * 0.80 = 29.6 EXP (base)
         * L31-50: 45 * 0.80 = 36 EXP (base)
         */
        PRACTICE_AI(0.80),
        
        /**
         * Speaking/roleplay session completion: 120% of base
         * L1-15: 30 * 1.20 = 36 EXP (base)
         * L16-30: 37 * 1.20 = 44.4 EXP (base)
         * L31-50: 45 * 1.20 = 54 EXP (base)
         */
        SPEAKING(1.20);
        
        private final double multiplier;
        
        ActivityType(double multiplier) {
            this.multiplier = multiplier;
        }
        
        /**
         * Calculate the EXP reward for this activity type (without score bonus, legacy method)
         * @return EXP amount (DEFAULT_BASE_EXP * multiplier)
         */
        public int getExpReward() {
            return (int) (DEFAULT_BASE_EXP * multiplier);
        }
        
        /**
         * Calculate EXP with level-based tier (no score bonus)
         * @param currentLevel Player's current level
         * @return EXP amount (tier-based BASE_EXP * multiplier)
         */
        public int getExpReward(int currentLevel) {
            int baseExp = LevelExpTierConfig.getBaseExpForLevel(currentLevel);
            return (int) (baseExp * multiplier);
        }
        
        /**
         * Calculate EXP with level-based tier AND score bonus
         * @param currentLevel Player's current level
         * @param score Performance score 0-100, or null for no bonus
         * @return EXP amount (tier-based BASE_EXP * multiplier * score_multiplier)
         */
        public int getExpReward(int currentLevel, Integer score) {
            int baseExp = getExpReward(currentLevel);
            double scoreMultiplier = ScoreMultiplierUtil.calculateMultiplier(score);
            return (int) Math.floor(baseExp * scoreMultiplier);
        }
        
        /**
         * Get the activity multiplier (e.g., 0.50 for quiz, 0.80 for practice)
         * @return multiplier value
         */
        public double getMultiplier() {
            return multiplier;
        }
    }
}

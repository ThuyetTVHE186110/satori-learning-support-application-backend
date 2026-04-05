package com.fu.achievement.config;

/**
 * Configuration for activity-based EXP rewards.
 * Each activity type has a multiplier applied to BASE_EXP.
 */
public class ActivityExpConfig {
    
    /**
     * Base EXP value that multipliers are applied to
     */
    public static final int BASE_EXP = 30;
    
    /**
     * Activity types with their EXP multipliers
     */
    public enum ActivityType {
        /**
         * Quiz assignment completion: 50% of base = 15 EXP
         */
        QUIZ_ASSIGNMENT(0.50),
        
        /**
         * Writing assignment submission: 50% of base = 15 EXP
         */
        WRITING_ASSIGNMENT(0.50),
        
        /**
         * AI practice session completion: 80% of base = 24 EXP
         */
        PRACTICE_AI(0.80),
        
        /**
         * Speaking/roleplay session completion: 120% of base = 36 EXP
         */
        SPEAKING(1.20);
        
        private final double multiplier;
        
        ActivityType(double multiplier) {
            this.multiplier = multiplier;
        }
        
        /**
         * Calculate the EXP reward for this activity type
         * @return EXP amount (BASE_EXP * multiplier)
         */
        public int getExpReward() {
            return (int) (BASE_EXP * multiplier);
        }
        
        /**
         * Get the multiplier for this activity type
         * @return multiplier value
         */
        public double getMultiplier() {
            return multiplier;
        }
    }
}

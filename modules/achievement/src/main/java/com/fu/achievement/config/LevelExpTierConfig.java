package com.fu.achievement.config;

/**
 * Configuration for EXP reward tiers based on player level.
 * As players advance, BASE_EXP increases in tiers to make progression feel non-linear.
 * 
 * Tier structure:
 * - Early game (L1-15): BASE_EXP = 30 (fast progression feel)
 * - Mid game (L16-30): BASE_EXP = 37 (+23% boost)
 * - Late game (L31-50): BASE_EXP = 45 (+22% boost)
 * 
 * Progression formula: Activity_EXP = BASE_EXP * Activity_Multiplier * (1.0 + Score_Bonus)
 * Example: L25 Practice (100% score) = 37 * 0.8 * 1.5 = 44.4 EXP
 */
public class LevelExpTierConfig {
    
    /**
     * Maximum player level (hard cap)
     */
    public static final int MAX_LEVEL = 50;
    
    /**
     * Tier breakpoints and their BASE_EXP values
     */
    public enum ExpTier {
        TIER_1(1, 15, 30, "Early Game"),
        TIER_2(16, 30, 37, "Mid Game"),
        TIER_3(31, 50, 45, "Late Game");
        
        private final int minLevel;
        private final int maxLevel;
        private final int baseExp;
        private final String name;
        
        ExpTier(int minLevel, int maxLevel, int baseExp, String name) {
            this.minLevel = minLevel;
            this.maxLevel = maxLevel;
            this.baseExp = baseExp;
            this.name = name;
        }
        
        public int getMinLevel() {
            return minLevel;
        }
        
        public int getMaxLevel() {
            return maxLevel;
        }
        
        public int getBaseExp() {
            return baseExp;
        }
        
        public String getTierName() {
            return name;
        }
    }
    
    /**
     * Get BASE_EXP multiplier for a given level
     * @param level Player current level
     * @return BASE_EXP value for that tier, or default 30 if level is invalid
     */
    public static int getBaseExpForLevel(int level) {
        if (level < 1) {
            return ExpTier.TIER_1.baseExp;
        }
        
        if (level <= ExpTier.TIER_1.maxLevel) {
            return ExpTier.TIER_1.baseExp;
        } else if (level <= ExpTier.TIER_2.maxLevel) {
            return ExpTier.TIER_2.baseExp;
        } else if (level <= ExpTier.TIER_3.maxLevel) {
            return ExpTier.TIER_3.baseExp;
        } else {
            // Beyond max level, use last tier's value
            return ExpTier.TIER_3.baseExp;
        }
    }
    
    /**
     * Get the tier name for display/logging
     * @param level Player current level
     * @return Tier name (e.g., "Mid Game")
     */
    public static String getTierName(int level) {
        if (level <= ExpTier.TIER_1.maxLevel) {
            return ExpTier.TIER_1.getTierName();
        } else if (level <= ExpTier.TIER_2.maxLevel) {
            return ExpTier.TIER_2.getTierName();
        } else {
            return ExpTier.TIER_3.getTierName();
        }
    }
    
    /**
     * Check if level is valid (within 1 to MAX_LEVEL)
     * @param level Level to check
     * @return true if level is valid
     */
    public static boolean isValidLevel(int level) {
        return level >= 1 && level <= MAX_LEVEL;
    }
}

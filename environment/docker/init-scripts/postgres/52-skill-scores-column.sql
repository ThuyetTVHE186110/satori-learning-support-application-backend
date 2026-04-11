-- Migration: Add skill_scores JSONB column to user_learning_preferences
-- Stores the 5-dimension "Satori Pentagon" radar data: VOCABULARY, GRAMMAR, PRONUNCIATION, FLUENCY, SPEAKING

ALTER TABLE user_management.user_learning_preferences
    ADD COLUMN IF NOT EXISTS skill_scores jsonb DEFAULT '[]'::jsonb;

COMMENT ON COLUMN user_management.user_learning_preferences.skill_scores
    IS 'Satori Pentagon skill radar scores (5 dimensions). Each entry: {skill, score, previousScore, trend, dataPoints, lastUpdated}';

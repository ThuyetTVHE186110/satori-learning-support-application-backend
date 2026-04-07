-- =========================================================================
-- Mock Data for gamification.badges with updated badge_type Enum
-- Types: 'LEARNING_STREAK', 'AI_SPEAKING_COUNT', 'AI_PRACTICE_COUNT', 'LEARNING_LEVEL'
-- Removed columns: name_japanese, requirement_type, rarity, coin_reward
-- =========================================================================

-- Clear existing badges (if any were inserted)
TRUNCATE TABLE gamification.badges CASCADE;

-- Insert mock data matching new enums
INSERT INTO gamification.badges (name, description, badge_type, requirement_value, icon_url, exp_reward) VALUES
-- LEARNING_STREAK
('Week Warrior', 'Maintain a 7-day streak', 'LEARNING_STREAK', 7, '/badges/week-warrior.png', 50),
('Month Master', 'Maintain a 30-day streak', 'LEARNING_STREAK', 30, '/badges/month-master.png', 200),
('Century Streak', 'Maintain a 100-day streak', 'LEARNING_STREAK', 100, '/badges/century-streak.png', 500),

-- AI_SPEAKING_COUNT
('Shy Speaker', 'Complete 10 AI speaking sessions', 'AI_SPEAKING_COUNT', 10, '/badges/shy-speaker.png', 30),
('Conversation King', 'Complete 100 AI speaking sessions', 'AI_SPEAKING_COUNT', 100, '/badges/conversation-king.png', 150),

-- AI_PRACTICE_COUNT
('First Practice', 'Complete your first AI practice', 'AI_PRACTICE_COUNT', 1, '/badges/first-step.png', 10),
('Practice Pro', 'Complete 50 AI practices', 'AI_PRACTICE_COUNT', 50, '/badges/practice-pro.png', 100),

-- LEARNING_LEVEL
('Level 10', 'Reach level 10', 'LEARNING_LEVEL', 10, '/badges/level-10.png', 100),
('Level 50', 'Reach level 50', 'LEARNING_LEVEL', 50, '/badges/level-50.png', 500),
('Master Level', 'Reach level 100', 'LEARNING_LEVEL', 100, '/badges/level-master.png', 1000);

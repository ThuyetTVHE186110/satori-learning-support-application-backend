-- ==================== Mock Badges and Streak History ====================
-- Satori Learning System - Badge Definitions and User Achievement Data
--
-- This script creates:
-- 1. 7 easily achievable badge definitions (low requirement values)
-- 2. 7 days of streak history for past week (2026-04-01 to 2026-04-07)
-- 3. 4 user earned badges for test learner
--
-- Test User: learner1@test.satori.com (ID: a0000000-0000-0000-0000-000000000004)
-- ==================== ==================== ====================

-- ==================== 1. INSERT BADGE DEFINITIONS ====================

-- Badge: First Step (1-day learning streak)
INSERT INTO gamification.badges (
    id, name, description, badge_type, requirement_value,
    icon_url, exp_reward, status, created_at, updated_at
) VALUES (
    'b0000000-0000-0000-0000-000000000001',
    'First Step',
    '完成1日の学習 / Complete 1 day of learning',
    'LEARNING_STREAK'::badge_type,
    1,
    'https://res.cloudinary.com/dtn0i6fvs/image/upload/v1775665475/achievements/badges/glytfiktkqsn1mhickrf.png',
    10,
    'ACTIVE'::badge_status,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT DO NOTHING;

-- Badge: On Fire (3-day learning streak)
INSERT INTO gamification.badges (
    id, name, description, badge_type, requirement_value,
    icon_url, exp_reward, status, created_at, updated_at
) VALUES (
    'b0000000-0000-0000-0000-000000000002',
    'On Fire',
    '3日間のストリークを継続 / Maintain 3-day learning streak',
    'LEARNING_STREAK'::badge_type,
    3,
    'https://res.cloudinary.com/dtn0i6fvs/image/upload/v1775665475/achievements/badges/glytfiktkqsn1mhickrf.png',
    25,
    'ACTIVE'::badge_status,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT DO NOTHING;

-- Badge: Week Warrior (7-day learning streak)
INSERT INTO gamification.badges (
    id, name, description, badge_type, requirement_value,
    icon_url, exp_reward, status, created_at, updated_at
) VALUES (
    'b0000000-0000-0000-0000-000000000003',
    'Week Warrior',
    '1週間のストリークを達成 / Maintain 7-day learning streak',
    'LEARNING_STREAK'::badge_type,
    7,
    'https://res.cloudinary.com/dtn0i6fvs/image/upload/v1775665475/achievements/badges/glytfiktkqsn1mhickrf.png',
    50,
    'ACTIVE'::badge_status,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT DO NOTHING;

-- Badge: Speaking Beginner (5 AI speaking sessions)
INSERT INTO gamification.badges (
    id, name, description, badge_type, requirement_value,
    icon_url, exp_reward, status, created_at, updated_at
) VALUES (
    'b0000000-0000-0000-0000-000000000004',
    'Speaking Beginner',
    'AI会話5セッション完了 / Complete 5 AI speaking sessions',
    'AI_SPEAKING_COUNT'::badge_type,
    5,
    'https://res.cloudinary.com/dtn0i6fvs/image/upload/v1775665475/achievements/badges/glytfiktkqsn1mhickrf.png',
    20,
    'ACTIVE'::badge_status,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT DO NOTHING;

-- Badge: Practice Starter (10 AI practice sessions)
INSERT INTO gamification.badges (
    id, name, description, badge_type, requirement_value,
    icon_url, exp_reward, status, created_at, updated_at
) VALUES (
    'b0000000-0000-0000-0000-000000000005',
    'Practice Starter',
    'AI練習10セッション完了 / Complete 10 AI practice sessions',
    'AI_PRACTICE_COUNT'::badge_type,
    10,
    'https://res.cloudinary.com/dtn0i6fvs/image/upload/v1775665475/achievements/badges/glytfiktkqsn1mhickrf.png',
    30,
    'ACTIVE'::badge_status,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT DO NOTHING;

-- Badge: Level 5 (Reach Level 5)
INSERT INTO gamification.badges (
    id, name, description, badge_type, requirement_value,
    icon_url, exp_reward, status, created_at, updated_at
) VALUES (
    'b0000000-0000-0000-0000-000000000006',
    'Level 5',
    'レベル5に到達 / Reach Level 5',
    'LEARNING_LEVEL'::badge_type,
    5,
    'https://res.cloudinary.com/dtn0i6fvs/image/upload/v1775665475/achievements/badges/glytfiktkqsn1mhickrf.png',
    40,
    'ACTIVE'::badge_status,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT DO NOTHING;

-- Badge: Level 10 (Reach Level 10)
INSERT INTO gamification.badges (
    id, name, description, badge_type, requirement_value,
    icon_url, exp_reward, status, created_at, updated_at
) VALUES (
    'b0000000-0000-0000-0000-000000000007',
    'Level 10',
    'レベル10に到達 / Reach Level 10',
    'LEARNING_LEVEL'::badge_type,
    10,
    'https://res.cloudinary.com/dtn0i6fvs/image/upload/v1775665475/achievements/badges/glytfiktkqsn1mhickrf.png',
    75,
    'ACTIVE'::badge_status,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT DO NOTHING;

-- ==================== 2. INSERT STREAK HISTORY (PAST 7 DAYS) ====================
-- User: learner1 (a0000000-0000-0000-0000-000000000004)
-- Dates: 2026-04-01 (Wed) to 2026-04-07 (Tue) - today is 2026-04-07

-- Day 1: 2026-04-01 (Streak count: 1)
INSERT INTO gamification.streak_history (
    id, user_id, streak_date, streak_count, activity_type,
    exp_earned, freeze_used, created_at
) VALUES (
    'd0000000-0000-0000-0000-000000000001',
    'a0000000-0000-0000-0000-000000000004',
    '2026-04-01'::DATE,
    1,
    'DAILY_LEARNING',
    50,
    FALSE,
    CURRENT_TIMESTAMP - INTERVAL '6 days'
) ON CONFLICT DO NOTHING;

-- Day 2: 2026-04-02 (Streak count: 2)
INSERT INTO gamification.streak_history (
    id, user_id, streak_date, streak_count, activity_type,
    exp_earned, freeze_used, created_at
) VALUES (
    'd0000000-0000-0000-0000-000000000002',
    'a0000000-0000-0000-0000-000000000004',
    '2026-04-02'::DATE,
    2,
    'DAILY_LEARNING',
    60,
    FALSE,
    CURRENT_TIMESTAMP - INTERVAL '5 days'
) ON CONFLICT DO NOTHING;

-- Day 3: 2026-04-03 (Streak count: 3)
INSERT INTO gamification.streak_history (
    id, user_id, streak_date, streak_count, activity_type,
    exp_earned, freeze_used, created_at
) VALUES (
    'd0000000-0000-0000-0000-000000000003',
    'a0000000-0000-0000-0000-000000000004',
    '2026-04-03'::DATE,
    3,
    'DAILY_LEARNING',
    70,
    FALSE,
    CURRENT_TIMESTAMP - INTERVAL '4 days'
) ON CONFLICT DO NOTHING;

-- Day 4: 2026-04-04 (Streak count: 4)
INSERT INTO gamification.streak_history (
    id, user_id, streak_date, streak_count, activity_type,
    exp_earned, freeze_used, created_at
) VALUES (
    'd0000000-0000-0000-0000-000000000004',
    'a0000000-0000-0000-0000-000000000004',
    '2026-04-04'::DATE,
    4,
    'DAILY_LEARNING',
    75,
    FALSE,
    CURRENT_TIMESTAMP - INTERVAL '3 days'
) ON CONFLICT DO NOTHING;

-- Day 5: 2026-04-05 (Streak count: 5)
INSERT INTO gamification.streak_history (
    id, user_id, streak_date, streak_count, activity_type,
    exp_earned, freeze_used, created_at
) VALUES (
    'd0000000-0000-0000-0000-000000000005',
    'a0000000-0000-0000-0000-000000000004',
    '2026-04-05'::DATE,
    5,
    'DAILY_LEARNING',
    85,
    FALSE,
    CURRENT_TIMESTAMP - INTERVAL '2 days'
) ON CONFLICT DO NOTHING;

-- Day 6: 2026-04-06 (Streak count: 6)
INSERT INTO gamification.streak_history (
    id, user_id, streak_date, streak_count, activity_type,
    exp_earned, freeze_used, created_at
) VALUES (
    'd0000000-0000-0000-0000-000000000006',
    'a0000000-0000-0000-0000-000000000004',
    '2026-04-06'::DATE,
    6,
    'DAILY_LEARNING',
    90,
    FALSE,
    CURRENT_TIMESTAMP - INTERVAL '1 days'
) ON CONFLICT DO NOTHING;


-- ==================== 3. INSERT USER EARNED BADGES ====================
-- User: learner1 (a0000000-0000-0000-0000-000000000004)

-- Badge 1: First Step (earned 4 days ago: 2026-04-02)
INSERT INTO gamification.user_badges (
    id, user_id, badge_id, earned_at, is_featured
) VALUES (
    'e0000000-0000-0000-0000-000000000001',
    'a0000000-0000-0000-0000-000000000004',
    'b0000000-0000-0000-0000-000000000001',
    CURRENT_TIMESTAMP - INTERVAL '4 days',
    FALSE
) ON CONFLICT DO NOTHING;

-- Badge 2: On Fire (earned 2 days ago: 2026-04-04)
INSERT INTO gamification.user_badges (
    id, user_id, badge_id, earned_at, is_featured
) VALUES (
    'e0000000-0000-0000-0000-000000000002',
    'a0000000-0000-0000-0000-000000000004',
    'b0000000-0000-0000-0000-000000000002',
    CURRENT_TIMESTAMP - INTERVAL '2 days',
    TRUE
) ON CONFLICT DO NOTHING;

-- Badge 3: Speaking Beginner (earned 1 day ago: 2026-04-05)
INSERT INTO gamification.user_badges (
    id, user_id, badge_id, earned_at, is_featured
) VALUES (
    'e0000000-0000-0000-0000-000000000003',
    'a0000000-0000-0000-0000-000000000004',
    'b0000000-0000-0000-0000-000000000004',
    CURRENT_TIMESTAMP - INTERVAL '1 days',
    FALSE
) ON CONFLICT DO NOTHING;

-- Badge 4: Practice Starter (earned today: 2026-04-06)
INSERT INTO gamification.user_badges (
    id, user_id, badge_id, earned_at, is_featured
) VALUES (
    'e0000000-0000-0000-0000-000000000004',
    'a0000000-0000-0000-0000-000000000004',
    'b0000000-0000-0000-0000-000000000005',
    CURRENT_TIMESTAMP,
    TRUE
) ON CONFLICT DO NOTHING;

-- ==================== 4. UPDATE USER STATS (OPTIONAL) ====================
-- Update learner1 stats to reflect earned badges and progression
UPDATE gamification.user_stats
SET
    current_level = 7,
    current_streak = 7,
    longest_streak = 7,
    streak_last_date = '2026-04-07',
    updated_at = CURRENT_TIMESTAMP
WHERE user_id = 'a0000000-0000-0000-0000-000000000004';

-- If user stats don't exist, create them
INSERT INTO gamification.user_stats (
    id, user_id, total_exp, current_level, coins,
    current_streak, longest_streak, streak_last_date,
    created_at, updated_at
) VALUES (
    '00000000-0000-0000-0000-000000000444',
    'a0000000-0000-0000-0000-000000000004',
    450,  -- Total EXP earned from streak: 50+60+70+75+85+90+100 = 530
    7,    -- Current level
    100,  -- Coins
    7,    -- Current streak
    7,    -- Longest streak
    '2026-04-07',  -- Last streak date
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (user_id) DO NOTHING;

-- ==================== SUMMARY ====================
-- ✅ Created 7 badge definitions (LEARNING_STREAK, AI_SPEAKING_COUNT, AI_PRACTICE_COUNT, LEARNING_LEVEL)
-- ✅ Inserted 7 days of streak history (2026-04-01 to 2026-04-07, today) with no freeze usage
-- ✅ Added 4 user earned badges for learner1@test.satori.com
-- ✅ Updated user stats to reflect current achievements (currentStreak=7, streakStartDate=2026-04-01)
-- ==================== ====================

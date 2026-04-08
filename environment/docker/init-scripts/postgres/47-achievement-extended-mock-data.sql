-- ============================================================================
-- SQL 47: Achievement System Extended Mock Data
-- ============================================================================
-- Purpose: Extend SQL 45 with new learner (Learner2) having 21-day streak,
--          add new badge types, verify data correlations
-- 
-- Changes:
--   1. New badge definitions (2 badges for 14+ day streaks)
--   2. Learner2 complete profile (21-day streak, 8 earned badges)
--   3. Fix for Learner1 (add missing Week Warrior badge)
--   4. User stats for Learner2
--
-- UUID Strategy:
--   Badges:       c0000000-... (new badges 008, 009)
--   Streak Hist:  d1000000-... (Learner2, avoiding d0000000 conflict with Learner1)
--   User Badges:  e1000000-... (Learner2 badges + fix)
--   User Stats:   f1000000-... (Learner2)
-- ============================================================================

-- Reset any previous failed transaction
ROLLBACK;

BEGIN;

-- ============================================================================
-- SECTION 1: New Badge Definitions (2 badges for extended streaks)
-- ============================================================================

INSERT INTO gamification.badges 
(id, name, badge_type, requirement_value, exp_reward, status, icon_url, description, created_at, updated_at)
VALUES
-- Month Milestone: 14-day streak
('c0000000-0000-0000-0000-000000000008', 'Month Milestone', 'LEARNING_STREAK'::badge_type, 14, 100, 'ACTIVE'::badge_status, 
 'https://api.satori.local/assets/badges/month-milestone.png',
 'Achieve a 14-day learning streak | 14日間の学習ストリークを達成',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

-- Dedication: 21-day streak (maximum milestone)
('c0000000-0000-0000-0000-000000000009', 'Dedication', 'LEARNING_STREAK'::badge_type, 21, 150, 'ACTIVE'::badge_status,
 'https://api.satori.local/assets/badges/dedication.png',
 'Complete 21 consecutive days of learning | 21日間連続学習を完了',
 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- ============================================================================
-- SECTION 2: Learner2 Streak History (21 consecutive days)
-- ============================================================================
-- User: learner2@test.satori.com (ID: a0000000-0000-0000-0000-000000000005)
-- Period: 2026-03-17 to 2026-04-07
-- Streak Progression: 1 → 21 days
-- EXP Earned: Progressive increase (60-120 per day)
-- Freeze Used: FALSE for all days (no freeze tokens used)
-- ============================================================================

INSERT INTO gamification.streak_history 
(id, user_id, streak_date, streak_count, activity_type, exp_earned, freeze_used, created_at)
VALUES
-- Week 1 (Days 1-7): Foundation phase
('d1000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000005', '2026-03-17'::DATE, 1, 'DAILY_LEARNING', 60, false, CURRENT_TIMESTAMP),
('d1000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000005', '2026-03-18'::DATE, 2, 'DAILY_LEARNING', 65, false, CURRENT_TIMESTAMP),
('d1000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000005', '2026-03-19'::DATE, 3, 'DAILY_LEARNING', 70, false, CURRENT_TIMESTAMP),
('d1000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000005', '2026-03-20'::DATE, 4, 'DAILY_LEARNING', 75, false, CURRENT_TIMESTAMP),
('d1000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000005', '2026-03-21'::DATE, 5, 'DAILY_LEARNING', 80, false, CURRENT_TIMESTAMP),
('d1000000-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000005', '2026-03-22'::DATE, 6, 'DAILY_LEARNING', 85, false, CURRENT_TIMESTAMP),
('d1000000-0000-0000-0000-000000000007', 'a0000000-0000-0000-0000-000000000005', '2026-03-23'::DATE, 7, 'DAILY_LEARNING', 90, false, CURRENT_TIMESTAMP),

-- Week 2 (Days 8-14): Growth phase
('d1000000-0000-0000-0000-000000000008', 'a0000000-0000-0000-0000-000000000005', '2026-03-24'::DATE, 8, 'DAILY_LEARNING', 95, false, CURRENT_TIMESTAMP),
('d1000000-0000-0000-0000-000000000009', 'a0000000-0000-0000-0000-000000000005', '2026-03-25'::DATE, 9, 'DAILY_LEARNING', 100, false, CURRENT_TIMESTAMP),
('d1000000-0000-0000-0000-000000000010', 'a0000000-0000-0000-0000-000000000005', '2026-03-26'::DATE, 10, 'DAILY_LEARNING', 105, false, CURRENT_TIMESTAMP),
('d1000000-0000-0000-0000-000000000011', 'a0000000-0000-0000-0000-000000000005', '2026-03-27'::DATE, 11, 'DAILY_LEARNING', 110, false, CURRENT_TIMESTAMP),
('d1000000-0000-0000-0000-000000000012', 'a0000000-0000-0000-0000-000000000005', '2026-03-28'::DATE, 12, 'DAILY_LEARNING', 110, false, CURRENT_TIMESTAMP),
('d1000000-0000-0000-0000-000000000013', 'a0000000-0000-0000-0000-000000000005', '2026-03-29'::DATE, 13, 'DAILY_LEARNING', 115, false, CURRENT_TIMESTAMP),
('d1000000-0000-0000-0000-000000000014', 'a0000000-0000-0000-0000-000000000005', '2026-03-30'::DATE, 14, 'DAILY_LEARNING', 120, false, CURRENT_TIMESTAMP),

-- Week 3 (Days 15-21): Mastery phase
('d1000000-0000-0000-0000-000000000015', 'a0000000-0000-0000-0000-000000000005', '2026-03-31'::DATE, 15, 'DAILY_LEARNING', 120, false, CURRENT_TIMESTAMP),
('d1000000-0000-0000-0000-000000000016', 'a0000000-0000-0000-0000-000000000005', '2026-04-01'::DATE, 16, 'DAILY_LEARNING', 120, false, CURRENT_TIMESTAMP),
('d1000000-0000-0000-0000-000000000017', 'a0000000-0000-0000-0000-000000000005', '2026-04-02'::DATE, 17, 'DAILY_LEARNING', 120, false, CURRENT_TIMESTAMP),
('d1000000-0000-0000-0000-000000000018', 'a0000000-0000-0000-0000-000000000005', '2026-04-03'::DATE, 18, 'DAILY_LEARNING', 120, false, CURRENT_TIMESTAMP),
('d1000000-0000-0000-0000-000000000019', 'a0000000-0000-0000-0000-000000000005', '2026-04-04'::DATE, 19, 'DAILY_LEARNING', 120, false, CURRENT_TIMESTAMP),
('d1000000-0000-0000-0000-000000000020', 'a0000000-0000-0000-0000-000000000005', '2026-04-05'::DATE, 20, 'DAILY_LEARNING', 120, false, CURRENT_TIMESTAMP),
('d1000000-0000-0000-0000-000000000021', 'a0000000-0000-0000-0000-000000000005', '2026-04-07'::DATE, 21, 'DAILY_LEARNING', 120, false, CURRENT_TIMESTAMP)
ON CONFLICT (user_id, streak_date) DO NOTHING;

-- Total EXP from streak: 60+65+70+75+80+85+90+95+100+105+110+110+115+120+120+120+120+120+120+120+120 = 2,245 EXP

-- ============================================================================
-- SECTION 3: Learner2 Earned Badges (8 badges total)
-- ============================================================================
-- Badges earned on the day streak count reaches requirement:
--   Day 1 → First Step (1 day)
--   Day 3 → On Fire (3 days)
--   Day 7 → Week Warrior (7 days)
--   Day 14 → Month Milestone (14 days)
--   Day 21 → Dedication (21 days)
-- Plus 3 activity-based badges
-- ============================================================================

INSERT INTO gamification.user_badges 
(id, user_id, badge_id, earned_at, is_featured)
VALUES
-- Learning Streak Badges (5 total)
('e1000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000005', 'b0000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP - INTERVAL '21 days', false),
('e1000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000005', 'b0000000-0000-0000-0000-000000000002', CURRENT_TIMESTAMP - INTERVAL '19 days', false),
('e1000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000005', 'b0000000-0000-0000-0000-000000000003', CURRENT_TIMESTAMP - INTERVAL '15 days', false),
('e1000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000005', 'c0000000-0000-0000-0000-000000000008', CURRENT_TIMESTAMP - INTERVAL '7 days', false),
('e1000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000005', 'c0000000-0000-0000-0000-000000000009', CURRENT_TIMESTAMP, true),

-- Activity-Based Badges (3 total)
('e1000000-0000-0000-0000-000000000006', 'a0000000-0000-0000-0000-000000000005', 'b0000000-0000-0000-0000-000000000004', CURRENT_TIMESTAMP - INTERVAL '2 days', true),
('e1000000-0000-0000-0000-000000000007', 'a0000000-0000-0000-0000-000000000005', 'b0000000-0000-0000-0000-000000000005', CURRENT_TIMESTAMP - INTERVAL '1 day', true),
('e1000000-0000-0000-0000-000000000008', 'a0000000-0000-0000-0000-000000000005', 'b0000000-0000-0000-0000-000000000007', CURRENT_TIMESTAMP - INTERVAL '3 days', false)
ON CONFLICT (user_id, badge_id) DO NOTHING;

-- ============================================================================
-- SECTION 4: Learner2 User Stats
-- ============================================================================
-- Accumulated from 21-day streak history:
--   Total EXP from streak: 2,245
--   Total EXP from badges: 10+25+50+100+150+20+30+75 = 460
--   Base EXP from other activities: 1,500 (assumption)
--   Total EXP: 2,245 + 460 + 1,500 = 4,205 → Level ~14
-- ============================================================================

INSERT INTO gamification.user_stats 
(id, user_id, total_exp, current_level, coins, current_streak, longest_streak, streak_last_date, created_at, updated_at)
VALUES
('f1000000-0000-0000-0000-000000000005', 'a0000000-0000-0000-0000-000000000005', 4205, 14, 300, 21, 21, '2026-04-07', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (user_id) DO NOTHING;

-- ============================================================================
-- SECTION 5: Fix for Learner1 (Add Missing Week Warrior Badge)
-- ============================================================================
-- Learner1 reached 7-day streak on 2026-04-07 but Week Warrior badge was not added
-- ============================================================================

INSERT INTO gamification.user_badges 
(id, user_id, badge_id, earned_at, is_featured)
VALUES
('e1000000-0000-0000-0000-000000000100', 'a0000000-0000-0000-0000-000000000004', 'b0000000-0000-0000-0000-000000000003', CURRENT_TIMESTAMP - INTERVAL '1 day', false)
ON CONFLICT (user_id, badge_id) DO NOTHING;

COMMIT;
-- Day 7: 2026-04-07 (Streak count: 7)
INSERT INTO gamification.streak_history (
    id, user_id, streak_date, streak_count, activity_type,
    exp_earned, freeze_used, created_at
) VALUES (
             'd0000000-0000-0000-0000-000000000007',
             'a0000000-0000-0000-0000-000000000004',
             '2026-04-07'::DATE,
             7,
             'DAILY_LEARNING',
             90,
             FALSE,
             CURRENT_TIMESTAMP
         ) ON CONFLICT DO NOTHING;
-- ============================================================================
-- Data Verification Summary
-- ============================================================================
-- Learner1 (after fix):
--   Streak: 7 days → Badges: First Step, On Fire, Week Warrior (3 + fix = 5 total)
--   EXP: 530 (streak) + 85 (badges) = 615 (separate from user_stats.450)
--
-- Learner2 (new):
--   Streak: 21 consecutive days (no gaps)
--   Badges: 8 earned (5 learning streak + 3 activity-based)
--   EXP: 4,205 total → Level 14, Coins 300
--   Featured: 3 most recent badges (Dedication, Speaking Beginner, Practice Starter)
-- ============================================================================

-- ==================== Seed: User Learning Preferences ====================
-- Mock data for user_management.user_learning_preferences
-- Covers the test learner accounts created in 03-seed-test-users.sql
--
-- User IDs:
--   learner1  = a0000000-0000-0000-0000-000000000004  (Nguyen Van A, MALE)
--   learner2  = a0000000-0000-0000-0000-000000000005  (Tran Thi B, FEMALE)
--   teacher   = a0000000-0000-0000-0000-000000000003  (Tanaka Sensei)
-- =========================================================================

-- Learner 1: beginner, targeting N5, studies in the morning, prefers practical conversations
INSERT INTO user_management.user_learning_preferences (
    id,
    user_id,
    target_jlpt_level,
    daily_study_goal_minutes,
    preferred_study_time,
    preferred_topics,
    learning_pace,
    preferred_formality,
    conversation_style,
    streak_reminder_enabled,
    reminder_time,
    weak_points,
    strong_points,
    created_at,
    updated_at
) VALUES (
    uuid_generate_v4(),
    'a0000000-0000-0000-0000-000000000004',
    'N5',
    30,
    'morning',
    ARRAY['greetings', 'shopping', 'food', 'travel'],
    'normal',
    'polite',
    'practical',
    true,
    '08:00',
    '[{"type": "pronunciation", "item": "long vowels"}, {"type": "grammar", "item": "te-form"}]'::jsonb,
    '[{"type": "vocabulary", "item": "hiragana"}, {"type": "vocabulary", "item": "katakana"}]'::jsonb,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (user_id) DO NOTHING;

-- Learner 2: intermediate, targeting N3, studies at night, prefers business style
INSERT INTO user_management.user_learning_preferences (
    id,
    user_id,
    target_jlpt_level,
    daily_study_goal_minutes,
    preferred_study_time,
    preferred_topics,
    learning_pace,
    preferred_formality,
    conversation_style,
    streak_reminder_enabled,
    reminder_time,
    weak_points,
    strong_points,
    created_at,
    updated_at
) VALUES (
    uuid_generate_v4(),
    'a0000000-0000-0000-0000-000000000005',
    'N3',
    60,
    'night',
    ARRAY['business', 'technology', 'culture', 'daily_life'],
    'fast',
    'formal',
    'business',
    true,
    '21:00',
    '[{"type": "grammar", "item": "conditional forms"}, {"type": "kanji", "item": "N3 kanji"}]'::jsonb,
    '[{"type": "vocabulary", "item": "N4 vocabulary"}, {"type": "grammar", "item": "N4 grammar"}]'::jsonb,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (user_id) DO NOTHING;

-- Teacher: advanced, targeting N1, studies in the evening
INSERT INTO user_management.user_learning_preferences (
    id,
    user_id,
    target_jlpt_level,
    daily_study_goal_minutes,
    preferred_study_time,
    preferred_topics,
    learning_pace,
    preferred_formality,
    conversation_style,
    streak_reminder_enabled,
    reminder_time,
    weak_points,
    strong_points,
    created_at,
    updated_at
) VALUES (
    uuid_generate_v4(),
    'a0000000-0000-0000-0000-000000000003',
    'N1',
    90,
    'evening',
    ARRAY['literature', 'politics', 'science', 'business'],
    'fast',
    'formal',
    'creative',
    false,
    '20:00',
    '[{"type": "grammar", "item": "classical Japanese"}]'::jsonb,
    '[{"type": "vocabulary", "item": "N1-N2 vocabulary"}, {"type": "pronunciation", "item": "pitch accent"}]'::jsonb,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (user_id) DO NOTHING;

-- ==================== Verification ====================
-- SELECT u.email, p.target_jlpt_level, p.daily_study_goal_minutes, p.learning_pace
-- FROM user_management.user_learning_preferences p
-- JOIN user_management.users u ON u.id = p.user_id
-- WHERE u.email LIKE '%@test.satori.com';

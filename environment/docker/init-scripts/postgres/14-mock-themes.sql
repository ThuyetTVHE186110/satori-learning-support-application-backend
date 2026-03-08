-- ==========================================================================
-- Script 14: Mock Conversation Themes & Extended Topics
-- Creates conversation_themes (chủ đề lớn) and links existing topics,
-- then seeds additional topics under each theme.
-- Safe to re-run: uses ON CONFLICT DO NOTHING for themes,
-- and skips topic/link inserts that already exist.
-- ==========================================================================

DO $$
DECLARE
    v_manager_id UUID := 'a0000000-0000-0000-0000-000000000002';

    -- Fixed theme UUIDs for predictable references
    v_theme_daily_life  UUID := 'a1000000-0000-0000-0000-000000000001';
    v_theme_travel      UUID := 'a1000000-0000-0000-0000-000000000002';
    v_theme_work_study  UUID := 'a1000000-0000-0000-0000-000000000003';
    v_theme_health      UUID := 'a1000000-0000-0000-0000-000000000004';
BEGIN

-- ==========================================================================
-- 1. INSERT THEMES (idempotent via ON CONFLICT)
-- ==========================================================================

INSERT INTO speaking.conversation_themes
    (id, title, title_japanese, description, category, jlpt_level,
     ai_generated_context, status, order_index,
     created_by, updated_by, created_at, updated_at)
VALUES
(
    v_theme_daily_life,
    'Daily Life in Japan',
    '日本での日常生活',
    'Practice everyday conversations for living in Japan: greetings, dining, shopping, and making plans with friends.',
    'daily_life',
    'N5',
    '{
        "vocabularyHints": ["名前", "食べる", "買う", "友達", "予定"],
        "grammarPoints": ["～です", "～ます", "～ませんか", "～をください"],
        "scenarioSuggestions": ["Meeting someone new", "Eating out", "Going shopping", "Inviting a friend"]
    }'::JSONB,
    'PUBLISHED',
    1,
    v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
),
(
    v_theme_travel,
    'Travel & Sightseeing in Japan',
    '旅行・観光',
    'Navigate Japan confidently: ask for directions, check into hotels, use public transport, and explore tourist spots.',
    'travel',
    'N4',
    '{
        "vocabularyHints": ["駅", "電車", "地図", "観光", "予約", "チェックイン"],
        "grammarPoints": ["～はどこですか", "～まで行きたいです", "～の予約をしているのですが"],
        "scenarioSuggestions": ["Finding a train station", "Hotel check-in", "Buying tickets", "Asking a local for help"]
    }'::JSONB,
    'PUBLISHED',
    2,
    v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
),
(
    v_theme_work_study,
    'Work & Study',
    '仕事・勉強',
    'Communicate professionally in a Japanese workplace or school: meetings, presentations, and academic discussions.',
    'work',
    'N3',
    '{
        "vocabularyHints": ["会議", "報告", "締め切り", "資料", "授業"],
        "grammarPoints": ["～ていただけますか", "～について", "～に関して", "～ため"],
        "scenarioSuggestions": ["Job interview", "Office small talk", "Study group", "Asking a professor"]
    }'::JSONB,
    'PUBLISHED',
    3,
    v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
),
(
    v_theme_health,
    'Health & Medical',
    '健康・医療',
    'Handle health-related situations in Japan: visiting a doctor, describing symptoms, and buying medicine at a pharmacy.',
    'health',
    'N4',
    '{
        "vocabularyHints": ["病院", "症状", "薬", "熱", "痛い", "アレルギー"],
        "grammarPoints": ["～が痛いです", "～から飲んでください", "～のアレルギーがあります"],
        "scenarioSuggestions": ["Describing symptoms to a doctor", "Getting a prescription", "Buying medicine", "Making a clinic appointment"]
    }'::JSONB,
    'PUBLISHED',
    4,
    v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
)
ON CONFLICT (id) DO NOTHING;

RAISE NOTICE 'Themes inserted (or already exist).';

-- ==========================================================================
-- 2. LINK EXISTING TOPICS TO THEMES
-- (update only rows where theme_id is still NULL)
-- ==========================================================================

-- Daily Life theme: Self Introduction, At a Restaurant, Shopping, Making Plans
UPDATE speaking.conversation_topics
SET theme_id = v_theme_daily_life, updated_by = v_manager_id, updated_at = CURRENT_TIMESTAMP
WHERE title IN ('Self Introduction', 'At a Restaurant', 'Shopping', 'Making Plans')
  AND deleted_at IS NULL
  AND theme_id IS NULL;

-- Travel theme: Asking Directions, At the Hotel
UPDATE speaking.conversation_topics
SET theme_id = v_theme_travel, updated_by = v_manager_id, updated_at = CURRENT_TIMESTAMP
WHERE title IN ('Asking Directions', 'At the Hotel')
  AND deleted_at IS NULL
  AND theme_id IS NULL;

RAISE NOTICE 'Existing topics linked to themes.';

-- ==========================================================================
-- 3. ADDITIONAL TOPICS — Daily Life (v_theme_daily_life)
-- ==========================================================================

-- 3a. Talking About Hobbies
IF NOT EXISTS (
    SELECT 1 FROM speaking.conversation_topics
    WHERE title = 'Talking About Hobbies' AND deleted_at IS NULL
) THEN
    INSERT INTO speaking.conversation_topics
        (id, theme_id, title, title_japanese, description, scenario, category,
         jlpt_level, difficulty_score, is_roleplay, vocabulary_hints, grammar_points,
         useful_expressions, status, order_index,
         created_by, updated_by, created_at, updated_at)
    VALUES (
        uuid_generate_v4(), v_theme_daily_life,
        'Talking About Hobbies', '趣味について話す',
        'Share and discuss hobbies with a Japanese friend.',
        'You are chatting with a new Japanese friend at a café and the topic of hobbies comes up.',
        'daily_life', 'N5', 1, false,
        '[{"word":"趣味","reading":"しゅみ","meaning":"hobby"},{"word":"好き","reading":"すき","meaning":"like"},{"word":"週末","reading":"しゅうまつ","meaning":"weekend"}]'::JSONB,
        '["～が好きです","趣味は～です","～はどうですか"]'::JSONB,
        '["趣味は何ですか","私も好きです！","週末は何をしますか"]'::JSONB,
        'PUBLISHED', 7,
        v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
    RAISE NOTICE 'Inserted topic: Talking About Hobbies';
END IF;

-- 3b. At the Convenience Store
IF NOT EXISTS (
    SELECT 1 FROM speaking.conversation_topics
    WHERE title = 'At the Convenience Store' AND deleted_at IS NULL
) THEN
    INSERT INTO speaking.conversation_topics
        (id, theme_id, title, title_japanese, description, scenario, category,
         jlpt_level, difficulty_score, is_roleplay, vocabulary_hints, grammar_points,
         useful_expressions, status, order_index,
         created_by, updated_by, created_at, updated_at)
    VALUES (
        uuid_generate_v4(), v_theme_daily_life,
        'At the Convenience Store', 'コンビニで',
        'Buy everyday items and use services at a Japanese convenience store.',
        'You are at a 7-Eleven and need to buy snacks, pay a bill, and use the ATM.',
        'daily_life', 'N5', 2, true,
        '[{"word":"袋","reading":"ふくろ","meaning":"bag"},{"word":"温める","reading":"あたためる","meaning":"to heat up"},{"word":"ATM","meaning":"ATM"}]'::JSONB,
        '["～をお願いします","～はありますか","温めますか"]'::JSONB,
        '["袋はいりません","温めてください","ポイントカードはありますか"]'::JSONB,
        'PUBLISHED', 8,
        v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
    RAISE NOTICE 'Inserted topic: At the Convenience Store';
END IF;

-- ==========================================================================
-- 4. ADDITIONAL TOPICS — Travel & Sightseeing (v_theme_travel)
-- ==========================================================================

-- 4a. At the Train Station
IF NOT EXISTS (
    SELECT 1 FROM speaking.conversation_topics
    WHERE title = 'At the Train Station' AND deleted_at IS NULL
) THEN
    INSERT INTO speaking.conversation_topics
        (id, theme_id, title, title_japanese, description, scenario, category,
         jlpt_level, difficulty_score, is_roleplay, vocabulary_hints, grammar_points,
         useful_expressions, status, order_index,
         created_by, updated_by, created_at, updated_at)
    VALUES (
        uuid_generate_v4(), v_theme_travel,
        'At the Train Station', '駅で',
        'Buy tickets and navigate the train system in Japan.',
        'You need to buy a bullet train ticket from Tokyo to Osaka and ask about the platform.',
        'travel', 'N4', 3, true,
        '[{"word":"切符","reading":"きっぷ","meaning":"ticket"},{"word":"ホーム","meaning":"platform"},{"word":"乗り換え","reading":"のりかえ","meaning":"transfer"}]'::JSONB,
        '["～枚ください","～番線はどこですか","～行きの電車は"]'::JSONB,
        '["自由席をお願いします","乗り換えが必要ですか","何時に着きますか"]'::JSONB,
        'PUBLISHED', 7,
        v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
    RAISE NOTICE 'Inserted topic: At the Train Station';
END IF;

-- 4b. Buying Souvenirs
IF NOT EXISTS (
    SELECT 1 FROM speaking.conversation_topics
    WHERE title = 'Buying Souvenirs' AND deleted_at IS NULL
) THEN
    INSERT INTO speaking.conversation_topics
        (id, theme_id, title, title_japanese, description, scenario, category,
         jlpt_level, difficulty_score, is_roleplay, vocabulary_hints, grammar_points,
         useful_expressions, status, order_index,
         created_by, updated_by, created_at, updated_at)
    VALUES (
        uuid_generate_v4(), v_theme_travel,
        'Buying Souvenirs', 'お土産を買う',
        'Shop for souvenirs at a traditional Japanese gift shop.',
        'You are at a souvenir shop in Kyoto and want to buy gifts for your family.',
        'travel', 'N4', 2, true,
        '[{"word":"お土産","reading":"おみやげ","meaning":"souvenir"},{"word":"包む","reading":"つつむ","meaning":"to wrap"},{"word":"人気","reading":"にんき","meaning":"popular"}]'::JSONB,
        '["～を～つください","贈り物用に包んでもらえますか","～でおすすめは何ですか"]'::JSONB,
        '["箱に入れてください","これは人気ですか","ギフト用に包んでください"]'::JSONB,
        'PUBLISHED', 8,
        v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
    RAISE NOTICE 'Inserted topic: Buying Souvenirs';
END IF;

-- ==========================================================================
-- 5. ADDITIONAL TOPICS — Work & Study (v_theme_work_study)
-- ==========================================================================

-- 5a. Job Interview
IF NOT EXISTS (
    SELECT 1 FROM speaking.conversation_topics
    WHERE title = 'Job Interview' AND deleted_at IS NULL
) THEN
    INSERT INTO speaking.conversation_topics
        (id, theme_id, title, title_japanese, description, scenario, category,
         jlpt_level, difficulty_score, is_roleplay, vocabulary_hints, grammar_points,
         useful_expressions, status, order_index,
         created_by, updated_by, created_at, updated_at)
    VALUES (
        uuid_generate_v4(), v_theme_work_study,
        'Job Interview', '就職面接',
        'Practice a formal job interview in Japanese.',
        'You are being interviewed for an entry-level position at a Japanese company.',
        'work', 'N3', 5, true,
        '[{"word":"志望動機","reading":"しぼうどうき","meaning":"reason for applying"},{"word":"長所","reading":"ちょうしょ","meaning":"strength"},{"word":"経験","reading":"けいけん","meaning":"experience"}]'::JSONB,
        '["～と思っております","～に取り組んでまいりました","よろしくお願いいたします"]'::JSONB,
        '["御社を志望した理由は","長所は責任感が強いことです","精一杯努力いたします"]'::JSONB,
        'PUBLISHED', 1,
        v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
    RAISE NOTICE 'Inserted topic: Job Interview';
END IF;

-- 5b. Office Small Talk
IF NOT EXISTS (
    SELECT 1 FROM speaking.conversation_topics
    WHERE title = 'Office Small Talk' AND deleted_at IS NULL
) THEN
    INSERT INTO speaking.conversation_topics
        (id, theme_id, title, title_japanese, description, scenario, category,
         jlpt_level, difficulty_score, is_roleplay, vocabulary_hints, grammar_points,
         useful_expressions, status, order_index,
         created_by, updated_by, created_at, updated_at)
    VALUES (
        uuid_generate_v4(), v_theme_work_study,
        'Office Small Talk', '職場での雑談',
        'Have natural casual conversations with colleagues in a Japanese office.',
        'It is Monday morning and you are chatting with your coworker near the coffee machine.',
        'work', 'N3', 3, false,
        '[{"word":"お疲れ様","reading":"おつかれさま","meaning":"good work / thank you for your effort"},{"word":"週末","reading":"しゅうまつ","meaning":"weekend"},{"word":"残業","reading":"ざんぎょう","meaning":"overtime"}]'::JSONB,
        '["～ましたか","～だったんですね","～てよかったです"]'::JSONB,
        '["お疲れ様です","週末はいかがでしたか","また飲みに行きましょう"]'::JSONB,
        'PUBLISHED', 2,
        v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
    RAISE NOTICE 'Inserted topic: Office Small Talk';
END IF;

-- ==========================================================================
-- 6. ADDITIONAL TOPICS — Health & Medical (v_theme_health)
-- ==========================================================================

-- 6a. At the Doctor
IF NOT EXISTS (
    SELECT 1 FROM speaking.conversation_topics
    WHERE title = 'At the Doctor' AND deleted_at IS NULL
) THEN
    INSERT INTO speaking.conversation_topics
        (id, theme_id, title, title_japanese, description, scenario, category,
         jlpt_level, difficulty_score, is_roleplay, vocabulary_hints, grammar_points,
         useful_expressions, status, order_index,
         created_by, updated_by, created_at, updated_at)
    VALUES (
        uuid_generate_v4(), v_theme_health,
        'At the Doctor', '病院で',
        'Describe your symptoms clearly and understand a doctor''s instructions.',
        'You have a fever and sore throat. You are visiting a local clinic for the first time.',
        'health', 'N4', 4, true,
        '[{"word":"症状","reading":"しょうじょう","meaning":"symptom"},{"word":"熱","reading":"ねつ","meaning":"fever"},{"word":"のど","meaning":"throat"},{"word":"処方箋","reading":"しょほうせん","meaning":"prescription"}]'::JSONB,
        '["～が痛いです","～から～です","～のアレルギーがあります"]'::JSONB,
        '["熱が38度あります","3日前から体調が悪いです","薬のアレルギーはありません"]'::JSONB,
        'PUBLISHED', 1,
        v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
    RAISE NOTICE 'Inserted topic: At the Doctor';
END IF;

-- 6b. At the Pharmacy
IF NOT EXISTS (
    SELECT 1 FROM speaking.conversation_topics
    WHERE title = 'At the Pharmacy' AND deleted_at IS NULL
) THEN
    INSERT INTO speaking.conversation_topics
        (id, theme_id, title, title_japanese, description, scenario, category,
         jlpt_level, difficulty_score, is_roleplay, vocabulary_hints, grammar_points,
         useful_expressions, status, order_index,
         created_by, updated_by, created_at, updated_at)
    VALUES (
        uuid_generate_v4(), v_theme_health,
        'At the Pharmacy', '薬局で',
        'Buy over-the-counter medicine and ask a pharmacist for advice.',
        'You have a cold and need to buy medicine at a Japanese pharmacy without a prescription.',
        'health', 'N4', 3, true,
        '[{"word":"風邪薬","reading":"かぜぐすり","meaning":"cold medicine"},{"word":"飲み方","reading":"のみかた","meaning":"how to take (medicine)"},{"word":"副作用","reading":"ふくさよう","meaning":"side effect"}]'::JSONB,
        '["～に効く薬はありますか","1日～回、～錠飲んでください","副作用はありますか"]'::JSONB,
        '["風邪薬をください","食後に飲んでください","眠くなりますか"]'::JSONB,
        'PUBLISHED', 2,
        v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
    RAISE NOTICE 'Inserted topic: At the Pharmacy';
END IF;

END $$;

-- ==========================================================================
-- Verification
-- ==========================================================================
SELECT
    th.title          AS theme_title,
    th.jlpt_level     AS theme_level,
    th.status         AS theme_status,
    tp.title          AS topic_title,
    tp.jlpt_level     AS topic_level,
    tp.is_roleplay,
    tp.order_index
FROM speaking.conversation_themes th
LEFT JOIN speaking.conversation_topics tp
    ON tp.theme_id = th.id AND tp.deleted_at IS NULL
WHERE th.deleted_at IS NULL
ORDER BY th.order_index, tp.order_index;

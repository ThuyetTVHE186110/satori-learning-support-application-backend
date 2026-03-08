-- ==========================================================================
-- Script 13: Mock Missions for Roleplay Topics
-- Seeds ConversationMission data for all roleplay topics
-- Safe to re-run: skips topics that already have missions
-- ==========================================================================

DO $$
DECLARE
    v_manager_id  UUID := 'a0000000-0000-0000-0000-000000000002';
    v_topic_id    UUID;
BEGIN

-- ==========================================================================
-- TOPIC: At a Restaurant (レストランで)
-- ==========================================================================
SELECT id INTO v_topic_id
FROM speaking.conversation_topics
WHERE title = 'At a Restaurant' AND deleted_at IS NULL
LIMIT 1;

IF v_topic_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM speaking.conversation_missions
    WHERE topic_id = v_topic_id AND deleted_at IS NULL
) THEN
    INSERT INTO speaking.conversation_missions
        (id, topic_id, title, title_japanese, description, mission_type,
         success_criteria, required_vocabulary, required_grammar,
         order_index, created_by, updated_by, created_at, updated_at)
    VALUES
    (
        uuid_generate_v4(), v_topic_id,
        'Greet and Get Seated', '挨拶して席に着く',
        'Politely greet the restaurant staff and ask for a table.',
        'SOCIAL',
        '{"requiredActions":["use a polite greeting","ask for available seats"],"completionConditions":"Learner greets staff and is seated successfully."}'::JSONB,
        '[{"word":"いらっしゃいませ","meaning":"Welcome"},{"word":"何名様","meaning":"How many guests"}]'::JSONB,
        '["～名です","～席、お願いします"]'::JSONB,
        1, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    ),
    (
        uuid_generate_v4(), v_topic_id,
        'Order Food', '料理を注文する',
        'Order food and drinks. Ask for recommendations if unsure.',
        'TRANSACTIONAL',
        '{"requiredActions":["ask for the menu","order at least one dish","ask for recommendation"],"requiredVocabulary":["注文","おすすめ","ください"],"completionConditions":"Learner successfully places a food order."}'::JSONB,
        '[{"word":"注文","reading":"ちゅうもん","meaning":"order"},{"word":"おすすめ","meaning":"recommendation"},{"word":"メニュー","meaning":"menu"}]'::JSONB,
        '["～をください","～はありますか","おすすめは何ですか"]'::JSONB,
        2, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    ),
    (
        uuid_generate_v4(), v_topic_id,
        'Pay the Bill', 'お会計をする',
        'Request the bill and complete payment.',
        'TRANSACTIONAL',
        '{"requiredActions":["ask for the bill","confirm the total","thank the staff"],"requiredVocabulary":["お会計","ごちそうさま"],"completionConditions":"Learner requests and pays the bill politely."}'::JSONB,
        '[{"word":"お会計","reading":"おかいけい","meaning":"bill/check"},{"word":"ごちそうさまでした","meaning":"Thank you for the meal"}]'::JSONB,
        '["お会計お願いします","～円ですか","ありがとうございました"]'::JSONB,
        3, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
    RAISE NOTICE 'Inserted missions for topic: At a Restaurant';
END IF;

-- ==========================================================================
-- TOPIC: Shopping (買い物)
-- ==========================================================================
SELECT id INTO v_topic_id
FROM speaking.conversation_topics
WHERE title = 'Shopping' AND deleted_at IS NULL
LIMIT 1;

IF v_topic_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM speaking.conversation_missions
    WHERE topic_id = v_topic_id AND deleted_at IS NULL
) THEN
    INSERT INTO speaking.conversation_missions
        (id, topic_id, title, title_japanese, description, mission_type,
         success_criteria, required_vocabulary, required_grammar,
         order_index, created_by, updated_by, created_at, updated_at)
    VALUES
    (
        uuid_generate_v4(), v_topic_id,
        'Ask About Items', '商品について聞く',
        'Ask the staff about price, size, and available colors.',
        'INFORMATION_GATHERING',
        '{"requiredActions":["ask about price","ask about size or color"],"requiredVocabulary":["いくら","サイズ","色"],"completionConditions":"Learner gathers enough product information to make a decision."}'::JSONB,
        '[{"word":"いくら","meaning":"how much"},{"word":"サイズ","meaning":"size"},{"word":"色","reading":"いろ","meaning":"color"}]'::JSONB,
        '["～はいくらですか","～はありますか","～を探しています"]'::JSONB,
        1, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    ),
    (
        uuid_generate_v4(), v_topic_id,
        'Try On and Decide', '試着して決める',
        'Ask to try on the item and make a purchase decision.',
        'TRANSACTIONAL',
        '{"requiredActions":["ask to try on","express preference","decide to buy or not"],"requiredVocabulary":["試着","似合う","これにします"],"completionConditions":"Learner tries on item and decides whether to purchase."}'::JSONB,
        '[{"word":"試着","reading":"しちゃく","meaning":"trying on clothes"},{"word":"似合う","reading":"にあう","meaning":"to suit / look good on"}]'::JSONB,
        '["試着してもいいですか","これにします","少し考えます"]'::JSONB,
        2, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    ),
    (
        uuid_generate_v4(), v_topic_id,
        'Complete Purchase', '購入を完了する',
        'Complete the payment process including payment method.',
        'TRANSACTIONAL',
        '{"requiredActions":["confirm total price","choose payment method","complete transaction"],"requiredVocabulary":["クレジットカード","現金","レシート"],"completionConditions":"Learner successfully completes the purchase."}'::JSONB,
        '[{"word":"クレジットカード","meaning":"credit card"},{"word":"現金","reading":"げんきん","meaning":"cash"},{"word":"レシート","meaning":"receipt"}]'::JSONB,
        '["クレジットカードは使えますか","袋に入れてください","ありがとうございました"]'::JSONB,
        3, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
    RAISE NOTICE 'Inserted missions for topic: Shopping';
END IF;

-- ==========================================================================
-- TOPIC: Asking Directions (道を聞く)
-- ==========================================================================
SELECT id INTO v_topic_id
FROM speaking.conversation_topics
WHERE title = 'Asking Directions' AND deleted_at IS NULL
LIMIT 1;

IF v_topic_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM speaking.conversation_missions
    WHERE topic_id = v_topic_id AND deleted_at IS NULL
) THEN
    INSERT INTO speaking.conversation_missions
        (id, topic_id, title, title_japanese, description, mission_type,
         success_criteria, required_vocabulary, required_grammar,
         order_index, created_by, updated_by, created_at, updated_at)
    VALUES
    (
        uuid_generate_v4(), v_topic_id,
        'Approach and Ask', '話しかけて道を聞く',
        'Politely stop a stranger and ask for directions to the station.',
        'SOCIAL',
        '{"requiredActions":["use a polite opener","ask for the station location"],"requiredVocabulary":["すみません","駅","どこ"],"completionConditions":"Learner successfully asks a stranger for directions."}'::JSONB,
        '[{"word":"すみません","meaning":"Excuse me"},{"word":"駅","reading":"えき","meaning":"train station"},{"word":"道","reading":"みち","meaning":"road/way"}]'::JSONB,
        '["すみません、～はどこですか","～まで行きたいのですが"]'::JSONB,
        1, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    ),
    (
        uuid_generate_v4(), v_topic_id,
        'Understand Directions', '道順を理解する',
        'Listen to and clarify the directions given by the local.',
        'INFORMATION_GATHERING',
        '{"requiredActions":["ask to repeat if unclear","confirm direction words understood"],"requiredVocabulary":["まっすぐ","右","左","角"],"completionConditions":"Learner understands and confirms the route."}'::JSONB,
        '[{"word":"まっすぐ","meaning":"straight ahead"},{"word":"右","reading":"みぎ","meaning":"right"},{"word":"左","reading":"ひだり","meaning":"left"},{"word":"角","reading":"かど","meaning":"corner"}]'::JSONB,
        '["もう一度言ってください","右に曲がりますか","どのくらいかかりますか"]'::JSONB,
        2, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    ),
    (
        uuid_generate_v4(), v_topic_id,
        'Confirm and Thank', '確認してお礼を言う',
        'Summarize the directions back and thank the person.',
        'SOCIAL',
        '{"requiredActions":["repeat directions back","express gratitude"],"completionConditions":"Learner confirms understanding and thanks the helper politely."}'::JSONB,
        '[{"word":"ありがとうございます","meaning":"Thank you very much"},{"word":"わかりました","meaning":"I understand"}]'::JSONB,
        '["わかりました、ありがとうございます","～を曲がれば良いですか"]'::JSONB,
        3, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
    RAISE NOTICE 'Inserted missions for topic: Asking Directions';
END IF;

-- ==========================================================================
-- TOPIC: At the Hotel (ホテルで)
-- ==========================================================================
SELECT id INTO v_topic_id
FROM speaking.conversation_topics
WHERE title = 'At the Hotel' AND deleted_at IS NULL
LIMIT 1;

IF v_topic_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM speaking.conversation_missions
    WHERE topic_id = v_topic_id AND deleted_at IS NULL
) THEN
    INSERT INTO speaking.conversation_missions
        (id, topic_id, title, title_japanese, description, mission_type,
         success_criteria, required_vocabulary, required_grammar,
         order_index, created_by, updated_by, created_at, updated_at)
    VALUES
    (
        uuid_generate_v4(), v_topic_id,
        'Complete Check-in', 'チェックインを完了する',
        'Check in to the hotel by confirming your reservation.',
        'TRANSACTIONAL',
        '{"requiredActions":["state your name","confirm reservation","receive room key"],"requiredVocabulary":["予約","チェックイン","名前"],"completionConditions":"Learner successfully completes the check-in process."}'::JSONB,
        '[{"word":"予約","reading":"よやく","meaning":"reservation"},{"word":"チェックイン","meaning":"check-in"},{"word":"パスポート","meaning":"passport"}]'::JSONB,
        '["予約した～です","チェックインをお願いします","～の予約をしているのですが"]'::JSONB,
        1, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    ),
    (
        uuid_generate_v4(), v_topic_id,
        'Ask About Facilities', '施設について聞く',
        'Ask about hotel facilities such as breakfast, Wi-Fi, and amenities.',
        'INFORMATION_GATHERING',
        '{"requiredActions":["ask about breakfast","ask about Wi-Fi","ask about at least one other facility"],"completionConditions":"Learner gathers information about hotel facilities."}'::JSONB,
        '[{"word":"朝食","reading":"ちょうしょく","meaning":"breakfast"},{"word":"Wi-Fi","meaning":"Wi-Fi"},{"word":"大浴場","reading":"だいよくじょう","meaning":"public bath"}]'::JSONB,
        '["朝食は何時からですか","Wi-Fiのパスワードを教えてください","～はありますか"]'::JSONB,
        2, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    ),
    (
        uuid_generate_v4(), v_topic_id,
        'Report a Room Issue', '部屋の問題を報告する',
        'Report a problem in your room (e.g., broken AC, missing towels).',
        'PROBLEM_SOLVING',
        '{"requiredActions":["describe the problem clearly","request a solution","follow up"],"requiredVocabulary":["エアコン","タオル","壊れている"],"completionConditions":"Learner reports a room issue and gets a resolution."}'::JSONB,
        '[{"word":"エアコン","meaning":"air conditioner"},{"word":"タオル","meaning":"towel"},{"word":"壊れている","reading":"こわれている","meaning":"broken"}]'::JSONB,
        '["部屋のエアコンが壊れています","タオルをもう一枚いただけますか","すぐに対応していただけますか"]'::JSONB,
        3, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
    RAISE NOTICE 'Inserted missions for topic: At the Hotel';
END IF;

-- ==========================================================================
-- TOPIC: Specific topic from test session (6fa528b5-efd6-40ef-9658-fc1e0ca2c9fe)
-- General Japanese conversation practice
-- ==========================================================================
v_topic_id := '6fa528b5-efd6-40ef-9658-fc1e0ca2c9fe'::UUID;

IF EXISTS (SELECT 1 FROM speaking.conversation_topics WHERE id = v_topic_id AND deleted_at IS NULL)
AND NOT EXISTS (SELECT 1 FROM speaking.conversation_missions WHERE topic_id = v_topic_id AND deleted_at IS NULL)
THEN
    INSERT INTO speaking.conversation_missions
        (id, topic_id, title, title_japanese, description, mission_type,
         success_criteria, required_vocabulary, required_grammar,
         order_index, created_by, updated_by, created_at, updated_at)
    VALUES
    (
        uuid_generate_v4(), v_topic_id,
        'Introduce Yourself', '自己紹介をする',
        'Introduce yourself including your name, nationality, and reason for learning Japanese.',
        'SOCIAL',
        '{"requiredActions":["state your name","mention nationality or hometown","explain why you are learning Japanese"],"requiredVocabulary":["名前","出身","日本語"],"completionConditions":"Learner completes a natural self-introduction."}'::JSONB,
        '[{"word":"名前","reading":"なまえ","meaning":"name"},{"word":"出身","reading":"しゅっしん","meaning":"hometown/origin"},{"word":"趣味","reading":"しゅみ","meaning":"hobby"}]'::JSONB,
        '["はじめまして","～と申します","よろしくお願いします","～から来ました"]'::JSONB,
        1, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    ),
    (
        uuid_generate_v4(), v_topic_id,
        'Talk About Daily Life', '日常生活について話す',
        'Have a natural conversation about your daily routine and hobbies.',
        'DESCRIPTIVE',
        '{"requiredActions":["describe a daily routine","mention at least one hobby","ask the partner a question"],"requiredVocabulary":["毎日","趣味","好き"],"completionConditions":"Learner maintains a multi-turn conversation about daily life."}'::JSONB,
        '[{"word":"毎日","reading":"まいにち","meaning":"every day"},{"word":"趣味","reading":"しゅみ","meaning":"hobby"},{"word":"好き","reading":"すき","meaning":"like / favorite"}]'::JSONB,
        '["毎日～します","趣味は～です","～が好きです","～はどうですか"]'::JSONB,
        2, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    ),
    (
        uuid_generate_v4(), v_topic_id,
        'Make a Simple Plan', '簡単な計画を立てる',
        'Suggest and agree on a simple activity to do together.',
        'SOCIAL',
        '{"requiredActions":["make a suggestion","respond to partner suggestion","agree on a plan"],"requiredVocabulary":["一緒に","どうですか","いいですね"],"completionConditions":"Learner and AI agree on a shared activity."}'::JSONB,
        '[{"word":"一緒に","reading":"いっしょに","meaning":"together"},{"word":"いいですね","meaning":"Sounds good"},{"word":"予定","reading":"よてい","meaning":"plan/schedule"}]'::JSONB,
        '["～しませんか","～はどうですか","いいですね、ぜひ"]'::JSONB,
        3, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
    RAISE NOTICE 'Inserted missions for test topic: 6fa528b5-efd6-40ef-9658-fc1e0ca2c9fe';
END IF;

END $$;

-- ==========================================================================
-- Verification
-- ==========================================================================
SELECT
    ct.title AS topic_title,
    cm.title AS mission_title,
    cm.title_japanese,
    cm.mission_type,
    cm.order_index
FROM speaking.conversation_missions cm
JOIN speaking.conversation_topics ct ON ct.id = cm.topic_id
WHERE cm.deleted_at IS NULL
ORDER BY ct.title, cm.order_index;

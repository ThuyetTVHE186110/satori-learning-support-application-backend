-- ==========================================================================
-- Script 17: Vietnamese Mission Labels + Phase Support
-- 1. Add title_vi / description_vi to conversation_missions & session_missions
-- 2. Add conversation_phase to conversation_sessions
-- 3. Add message_type to conversation_messages
-- 4. UPDATE existing missions with Vietnamese translations
-- 5. INSERT missions for topics seeded in script 14 that have none yet
-- Safe to re-run (IF NOT EXISTS / ON CONFLICT checks throughout)
-- ==========================================================================

-- --------------------------------------------------------------------------
-- 1. Schema additions
-- --------------------------------------------------------------------------

ALTER TABLE speaking.conversation_missions
    ADD COLUMN IF NOT EXISTS title_vi     VARCHAR(255),
    ADD COLUMN IF NOT EXISTS description_vi TEXT;

ALTER TABLE ai_conversation.session_missions
    ADD COLUMN IF NOT EXISTS title_vi     VARCHAR(255),
    ADD COLUMN IF NOT EXISTS description_vi TEXT;

ALTER TABLE ai_conversation.conversation_sessions
    ADD COLUMN IF NOT EXISTS conversation_phase VARCHAR(20) DEFAULT 'CONVERSATION';

ALTER TABLE ai_conversation.conversation_messages
    ADD COLUMN IF NOT EXISTS message_type VARCHAR(30);

-- --------------------------------------------------------------------------
-- 2. UPDATE existing missions with Vietnamese translations
-- --------------------------------------------------------------------------

DO $$
DECLARE
    v_topic_id UUID;
BEGIN

-- ---- At a Restaurant -------------------------------------------------
SELECT id INTO v_topic_id FROM speaking.conversation_topics
WHERE title = 'At a Restaurant' AND deleted_at IS NULL LIMIT 1;

IF v_topic_id IS NOT NULL THEN
    UPDATE speaking.conversation_missions
    SET title_vi = 'Chào hỏi và được dẫn vào bàn',
        description_vi = 'Lịch sự chào nhân viên nhà hàng và xin một bàn.'
    WHERE topic_id = v_topic_id AND title = 'Greet and Get Seated' AND deleted_at IS NULL;

    UPDATE speaking.conversation_missions
    SET title_vi = 'Gọi món ăn',
        description_vi = 'Gọi đồ ăn và thức uống. Hỏi gợi ý nếu chưa biết chọn gì.'
    WHERE topic_id = v_topic_id AND title = 'Order Food' AND deleted_at IS NULL;

    UPDATE speaking.conversation_missions
    SET title_vi = 'Thanh toán hóa đơn',
        description_vi = 'Yêu cầu hóa đơn và hoàn tất thanh toán.'
    WHERE topic_id = v_topic_id AND title = 'Pay the Bill' AND deleted_at IS NULL;
END IF;

-- ---- Shopping --------------------------------------------------------
SELECT id INTO v_topic_id FROM speaking.conversation_topics
WHERE title = 'Shopping' AND deleted_at IS NULL LIMIT 1;

IF v_topic_id IS NOT NULL THEN
    UPDATE speaking.conversation_missions
    SET title_vi = 'Hỏi thông tin sản phẩm',
        description_vi = 'Hỏi nhân viên về giá, kích cỡ và màu sắc có sẵn.'
    WHERE topic_id = v_topic_id AND title = 'Ask About Items' AND deleted_at IS NULL;

    UPDATE speaking.conversation_missions
    SET title_vi = 'Thử và quyết định',
        description_vi = 'Xin thử đồ và đưa ra quyết định mua hàng.'
    WHERE topic_id = v_topic_id AND title = 'Try On and Decide' AND deleted_at IS NULL;

    UPDATE speaking.conversation_missions
    SET title_vi = 'Hoàn tất mua hàng',
        description_vi = 'Hoàn tất quá trình thanh toán bao gồm phương thức thanh toán.'
    WHERE topic_id = v_topic_id AND title = 'Complete Purchase' AND deleted_at IS NULL;
END IF;

-- ---- Asking Directions -----------------------------------------------
SELECT id INTO v_topic_id FROM speaking.conversation_topics
WHERE title = 'Asking Directions' AND deleted_at IS NULL LIMIT 1;

IF v_topic_id IS NOT NULL THEN
    UPDATE speaking.conversation_missions
    SET title_vi = 'Tiếp cận và hỏi đường',
        description_vi = 'Lịch sự dừng người lạ và hỏi đường đến ga tàu.'
    WHERE topic_id = v_topic_id AND title = 'Approach and Ask' AND deleted_at IS NULL;

    UPDATE speaking.conversation_missions
    SET title_vi = 'Hiểu chỉ đường',
        description_vi = 'Lắng nghe và làm rõ hướng dẫn đường được người địa phương chỉ.'
    WHERE topic_id = v_topic_id AND title = 'Understand Directions' AND deleted_at IS NULL;

    UPDATE speaking.conversation_missions
    SET title_vi = 'Xác nhận và cảm ơn',
        description_vi = 'Tóm tắt lại hướng dẫn và cảm ơn người đã chỉ đường.'
    WHERE topic_id = v_topic_id AND title = 'Confirm and Thank' AND deleted_at IS NULL;
END IF;

-- ---- At the Hotel ----------------------------------------------------
SELECT id INTO v_topic_id FROM speaking.conversation_topics
WHERE title = 'At the Hotel' AND deleted_at IS NULL LIMIT 1;

IF v_topic_id IS NOT NULL THEN
    UPDATE speaking.conversation_missions
    SET title_vi = 'Hoàn tất thủ tục nhận phòng',
        description_vi = 'Nhận phòng khách sạn bằng cách xác nhận đặt phòng của bạn.'
    WHERE topic_id = v_topic_id AND title = 'Complete Check-in' AND deleted_at IS NULL;

    UPDATE speaking.conversation_missions
    SET title_vi = 'Hỏi về tiện nghi khách sạn',
        description_vi = 'Hỏi về các tiện nghi của khách sạn như bữa sáng, Wi-Fi và các dịch vụ khác.'
    WHERE topic_id = v_topic_id AND title = 'Ask About Facilities' AND deleted_at IS NULL;

    UPDATE speaking.conversation_missions
    SET title_vi = 'Báo cáo vấn đề phòng',
        description_vi = 'Báo cáo sự cố trong phòng (ví dụ: điều hòa hỏng, thiếu khăn tắm).'
    WHERE topic_id = v_topic_id AND title = 'Report a Room Issue' AND deleted_at IS NULL;
END IF;

-- ---- Test topic (6fa528b5-efd6-40ef-9658-fc1e0ca2c9fe) --------------
v_topic_id := '6fa528b5-efd6-40ef-9658-fc1e0ca2c9fe'::UUID;

IF EXISTS (SELECT 1 FROM speaking.conversation_topics WHERE id = v_topic_id AND deleted_at IS NULL) THEN
    UPDATE speaking.conversation_missions
    SET title_vi = 'Tự giới thiệu bản thân',
        description_vi = 'Giới thiệu bản thân bao gồm tên, quốc tịch và lý do học tiếng Nhật.'
    WHERE topic_id = v_topic_id AND title = 'Introduce Yourself' AND deleted_at IS NULL;

    UPDATE speaking.conversation_missions
    SET title_vi = 'Nói về cuộc sống hằng ngày',
        description_vi = 'Trò chuyện tự nhiên về thói quen hàng ngày và sở thích cá nhân.'
    WHERE topic_id = v_topic_id AND title = 'Talk About Daily Life' AND deleted_at IS NULL;

    UPDATE speaking.conversation_missions
    SET title_vi = 'Lên kế hoạch đơn giản',
        description_vi = 'Đề xuất và đồng ý về một hoạt động đơn giản cùng nhau thực hiện.'
    WHERE topic_id = v_topic_id AND title = 'Make a Simple Plan' AND deleted_at IS NULL;
END IF;

RAISE NOTICE 'Updated Vietnamese translations for existing missions.';
END $$;

-- --------------------------------------------------------------------------
-- 3. INSERT missions for topics from script 14 that have none yet
-- --------------------------------------------------------------------------

DO $$
DECLARE
    v_manager_id UUID := 'a0000000-0000-0000-0000-000000000002';
    v_topic_id   UUID;
BEGIN

-- ==========================================================================
-- Talking About Hobbies (趣味について話す)
-- ==========================================================================
SELECT id INTO v_topic_id FROM speaking.conversation_topics
WHERE title = 'Talking About Hobbies' AND deleted_at IS NULL LIMIT 1;

IF v_topic_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM speaking.conversation_missions
    WHERE topic_id = v_topic_id AND deleted_at IS NULL
) THEN
    INSERT INTO speaking.conversation_missions
        (id, topic_id, title, title_japanese, title_vi, description, description_vi,
         mission_type, success_criteria, required_vocabulary, required_grammar,
         order_index, created_by, updated_by, created_at, updated_at)
    VALUES
    (
        uuid_generate_v4(), v_topic_id,
        'Share Your Hobby', '趣味を話す', 'Chia sẻ sở thích của bạn',
        'Introduce and describe your hobby to a Japanese friend.',
        'Giới thiệu và mô tả sở thích của bạn với một người bạn Nhật.',
        'SOCIAL',
        '{"requiredActions":["mention hobby name","describe why you like it"],"completionConditions":"Learner shares their hobby with enough detail."}'::JSONB,
        '[{"word":"趣味","reading":"しゅみ","meaning":"hobby"},{"word":"好き","reading":"すき","meaning":"like"},{"word":"楽しい","reading":"たのしい","meaning":"fun"}]'::JSONB,
        '["趣味は～です","～が好きです","～はとても楽しいです"]'::JSONB,
        1, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    ),
    (
        uuid_generate_v4(), v_topic_id,
        'Ask About Partner''s Hobbies', '相手の趣味を聞く', 'Hỏi về sở thích của đối phương',
        'Ask your conversation partner about their hobbies and show genuine interest.',
        'Hỏi đối phương về sở thích của họ và thể hiện sự quan tâm thực sự.',
        'INFORMATION_GATHERING',
        '{"requiredActions":["ask at least one question","respond to partner answer"],"completionConditions":"Learner asks and responds to hobby-related questions naturally."}'::JSONB,
        '[{"word":"週末","reading":"しゅうまつ","meaning":"weekend"},{"word":"よく","meaning":"often"},{"word":"一緒に","reading":"いっしょに","meaning":"together"}]'::JSONB,
        '["趣味は何ですか","週末は何をしますか","一緒にやってみましょう"]'::JSONB,
        2, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    ),
    (
        uuid_generate_v4(), v_topic_id,
        'Find a Common Interest', '共通の趣味を見つける', 'Tìm sở thích chung',
        'Discover a hobby you both enjoy and suggest doing it together.',
        'Khám phá sở thích mà cả hai đều thích và đề xuất cùng nhau thực hiện.',
        'SOCIAL',
        '{"requiredActions":["express shared interest","make a suggestion to do it together"],"completionConditions":"Learner and AI agree on a shared hobby activity."}'::JSONB,
        '[{"word":"私も","reading":"わたしも","meaning":"me too"},{"word":"いいですね","meaning":"sounds good"},{"word":"ぜひ","meaning":"by all means"}]'::JSONB,
        '["私も～が好きです","一緒に～しませんか","ぜひ行きましょう"]'::JSONB,
        3, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
    RAISE NOTICE 'Inserted missions for topic: Talking About Hobbies';
END IF;

-- ==========================================================================
-- At the Convenience Store (コンビニで)
-- ==========================================================================
SELECT id INTO v_topic_id FROM speaking.conversation_topics
WHERE title = 'At the Convenience Store' AND deleted_at IS NULL LIMIT 1;

IF v_topic_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM speaking.conversation_missions
    WHERE topic_id = v_topic_id AND deleted_at IS NULL
) THEN
    INSERT INTO speaking.conversation_missions
        (id, topic_id, title, title_japanese, title_vi, description, description_vi,
         mission_type, success_criteria, required_vocabulary, required_grammar,
         order_index, created_by, updated_by, created_at, updated_at)
    VALUES
    (
        uuid_generate_v4(), v_topic_id,
        'Buy Snacks', 'お菓子を買う', 'Mua đồ ăn nhẹ',
        'Purchase snacks and ask whether heating is needed.',
        'Mua đồ ăn nhẹ và hỏi xem có cần hâm nóng không.',
        'TRANSACTIONAL',
        '{"requiredActions":["select items","respond to heating question","complete purchase"],"completionConditions":"Learner successfully purchases snacks at the register."}'::JSONB,
        '[{"word":"温める","reading":"あたためる","meaning":"to heat up"},{"word":"袋","reading":"ふくろ","meaning":"bag"},{"word":"ポイントカード","meaning":"point card"}]'::JSONB,
        '["温めてください","袋はいりません","ポイントカードはありますか"]'::JSONB,
        1, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    ),
    (
        uuid_generate_v4(), v_topic_id,
        'Use Store Services', '店のサービスを使う', 'Sử dụng dịch vụ của cửa hàng',
        'Ask about available services such as ATM, printing, or bill payment.',
        'Hỏi về các dịch vụ có sẵn như ATM, in ấn hoặc thanh toán hóa đơn.',
        'INFORMATION_GATHERING',
        '{"requiredActions":["ask about at least one service","follow staff instructions"],"completionConditions":"Learner successfully uses a convenience store service."}'::JSONB,
        '[{"word":"ATM","meaning":"ATM"},{"word":"コピー機","reading":"コピーき","meaning":"copy machine"},{"word":"公共料金","reading":"こうきょうりょうきん","meaning":"utility bill"}]'::JSONB,
        '["ATMはどこですか","コピー機を使いたいのですが","公共料金の支払いはできますか"]'::JSONB,
        2, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
    RAISE NOTICE 'Inserted missions for topic: At the Convenience Store';
END IF;

-- ==========================================================================
-- At the Train Station (駅で)
-- ==========================================================================
SELECT id INTO v_topic_id FROM speaking.conversation_topics
WHERE title = 'At the Train Station' AND deleted_at IS NULL LIMIT 1;

IF v_topic_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM speaking.conversation_missions
    WHERE topic_id = v_topic_id AND deleted_at IS NULL
) THEN
    INSERT INTO speaking.conversation_missions
        (id, topic_id, title, title_japanese, title_vi, description, description_vi,
         mission_type, success_criteria, required_vocabulary, required_grammar,
         order_index, created_by, updated_by, created_at, updated_at)
    VALUES
    (
        uuid_generate_v4(), v_topic_id,
        'Buy a Train Ticket', '電車の切符を買う', 'Mua vé tàu',
        'Purchase a bullet train ticket from Tokyo to Osaka.',
        'Mua vé tàu cao tốc từ Tokyo đến Osaka.',
        'TRANSACTIONAL',
        '{"requiredActions":["specify destination","choose seat type","complete purchase"],"completionConditions":"Learner successfully purchases a train ticket."}'::JSONB,
        '[{"word":"切符","reading":"きっぷ","meaning":"ticket"},{"word":"自由席","reading":"じゆうせき","meaning":"unreserved seat"},{"word":"往復","reading":"おうふく","meaning":"round trip"}]'::JSONB,
        '["～まで1枚ください","自由席をお願いします","往復でお願いします"]'::JSONB,
        1, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    ),
    (
        uuid_generate_v4(), v_topic_id,
        'Find the Right Platform', '正しいホームを見つける', 'Tìm đúng sân ga',
        'Ask station staff for the correct platform and departure time.',
        'Hỏi nhân viên ga về sân ga đúng và giờ khởi hành.',
        'INFORMATION_GATHERING',
        '{"requiredActions":["ask for platform number","confirm departure time"],"completionConditions":"Learner locates the correct platform with help from staff."}'::JSONB,
        '[{"word":"ホーム","meaning":"platform"},{"word":"番線","reading":"ばんせん","meaning":"track number"},{"word":"発車","reading":"はっしゃ","meaning":"departure"}]'::JSONB,
        '["何番線ですか","何時に発車しますか","乗り換えは必要ですか"]'::JSONB,
        2, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    ),
    (
        uuid_generate_v4(), v_topic_id,
        'Handle a Transfer', '乗り換えをする', 'Thực hiện đổi tàu',
        'Ask about and complete a train transfer to your final destination.',
        'Hỏi về và hoàn thành việc đổi tàu đến điểm đến cuối cùng.',
        'PROBLEM_SOLVING',
        '{"requiredActions":["identify transfer station","ask about next train","successfully transfer"],"completionConditions":"Learner understands the transfer process and finds the connecting train."}'::JSONB,
        '[{"word":"乗り換え","reading":"のりかえ","meaning":"transfer"},{"word":"接続","reading":"せつぞく","meaning":"connection"},{"word":"終点","reading":"しゅうてん","meaning":"terminal station"}]'::JSONB,
        '["どこで乗り換えますか","次の電車は何時ですか","この電車は～まで行きますか"]'::JSONB,
        3, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
    RAISE NOTICE 'Inserted missions for topic: At the Train Station';
END IF;

-- ==========================================================================
-- Buying Souvenirs (お土産を買う)
-- ==========================================================================
SELECT id INTO v_topic_id FROM speaking.conversation_topics
WHERE title = 'Buying Souvenirs' AND deleted_at IS NULL LIMIT 1;

IF v_topic_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM speaking.conversation_missions
    WHERE topic_id = v_topic_id AND deleted_at IS NULL
) THEN
    INSERT INTO speaking.conversation_missions
        (id, topic_id, title, title_japanese, title_vi, description, description_vi,
         mission_type, success_criteria, required_vocabulary, required_grammar,
         order_index, created_by, updated_by, created_at, updated_at)
    VALUES
    (
        uuid_generate_v4(), v_topic_id,
        'Ask for Recommendations', 'おすすめを聞く', 'Hỏi về gợi ý sản phẩm',
        'Ask the shop staff for popular souvenir recommendations.',
        'Hỏi nhân viên cửa hàng về các gợi ý quà lưu niệm phổ biến.',
        'INFORMATION_GATHERING',
        '{"requiredActions":["ask what is popular","ask about recipient suitability"],"completionConditions":"Learner gathers enough info to choose suitable souvenirs."}'::JSONB,
        '[{"word":"お土産","reading":"おみやげ","meaning":"souvenir"},{"word":"人気","reading":"にんき","meaning":"popular"},{"word":"おすすめ","meaning":"recommendation"}]'::JSONB,
        '["おすすめは何ですか","これは人気ですか","子供へのお土産はありますか"]'::JSONB,
        1, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    ),
    (
        uuid_generate_v4(), v_topic_id,
        'Purchase and Gift-Wrap', '購入してギフト包装する', 'Mua hàng và gói quà',
        'Purchase selected souvenirs and request gift wrapping.',
        'Mua quà lưu niệm đã chọn và yêu cầu gói quà.',
        'TRANSACTIONAL',
        '{"requiredActions":["specify quantity","request gift wrapping","complete payment"],"completionConditions":"Learner purchases souvenirs and gets them gift-wrapped."}'::JSONB,
        '[{"word":"包む","reading":"つつむ","meaning":"to wrap"},{"word":"贈り物","reading":"おくりもの","meaning":"gift"},{"word":"～つ","meaning":"counter for small items"}]'::JSONB,
        '["～を3つください","ギフト用に包んでください","これをプレゼント用にしてください"]'::JSONB,
        2, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
    RAISE NOTICE 'Inserted missions for topic: Buying Souvenirs';
END IF;

-- ==========================================================================
-- Job Interview (就職面接)
-- ==========================================================================
SELECT id INTO v_topic_id FROM speaking.conversation_topics
WHERE title = 'Job Interview' AND deleted_at IS NULL LIMIT 1;

IF v_topic_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM speaking.conversation_missions
    WHERE topic_id = v_topic_id AND deleted_at IS NULL
) THEN
    INSERT INTO speaking.conversation_missions
        (id, topic_id, title, title_japanese, title_vi, description, description_vi,
         mission_type, success_criteria, required_vocabulary, required_grammar,
         order_index, created_by, updated_by, created_at, updated_at)
    VALUES
    (
        uuid_generate_v4(), v_topic_id,
        'Self-Introduction', '自己紹介をする', 'Tự giới thiệu bản thân',
        'Give a formal self-introduction at the start of the interview.',
        'Tự giới thiệu trang trọng ở đầu buổi phỏng vấn.',
        'SOCIAL',
        '{"requiredActions":["state name and background","mention relevant experience","use keigo"],"completionConditions":"Learner completes a formal self-introduction in keigo."}'::JSONB,
        '[{"word":"と申します","reading":"ともうします","meaning":"my name is (formal)"},{"word":"よろしくお願いいたします","meaning":"pleased to meet you (formal)"},{"word":"経験","reading":"けいけん","meaning":"experience"}]'::JSONB,
        '["～と申します","よろしくお願いいたします","～の経験があります"]'::JSONB,
        1, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    ),
    (
        uuid_generate_v4(), v_topic_id,
        'Explain Motivation', '志望動機を説明する', 'Giải thích lý do ứng tuyển',
        'Explain clearly why you want to join this company.',
        'Giải thích rõ ràng tại sao bạn muốn gia nhập công ty này.',
        'DESCRIPTIVE',
        '{"requiredActions":["state reason for applying","connect to company values","express enthusiasm"],"completionConditions":"Learner articulates a convincing motivation in formal Japanese."}'::JSONB,
        '[{"word":"志望動機","reading":"しぼうどうき","meaning":"reason for applying"},{"word":"御社","reading":"おんしゃ","meaning":"your company (honorific)"},{"word":"貢献","reading":"こうけん","meaning":"contribution"}]'::JSONB,
        '["御社を志望した理由は","御社の～に魅力を感じました","貢献できると思っております"]'::JSONB,
        2, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    ),
    (
        uuid_generate_v4(), v_topic_id,
        'Discuss Strengths and Weaknesses', '長所と短所を話す', 'Nói về điểm mạnh và điểm yếu',
        'Answer questions about your strengths and areas for improvement.',
        'Trả lời câu hỏi về điểm mạnh và những điểm cần cải thiện của bạn.',
        'DESCRIPTIVE',
        '{"requiredActions":["state a strength with example","state a weakness honestly","show self-awareness"],"completionConditions":"Learner discusses strengths and weaknesses convincingly in keigo."}'::JSONB,
        '[{"word":"長所","reading":"ちょうしょ","meaning":"strength"},{"word":"短所","reading":"たんしょ","meaning":"weakness"},{"word":"改善","reading":"かいぜん","meaning":"improvement"}]'::JSONB,
        '["長所は～です","短所は～ですが、改善するよう努力しています","～に取り組んでいます"]'::JSONB,
        3, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
    RAISE NOTICE 'Inserted missions for topic: Job Interview';
END IF;

-- ==========================================================================
-- Office Small Talk (職場での雑談)
-- ==========================================================================
SELECT id INTO v_topic_id FROM speaking.conversation_topics
WHERE title = 'Office Small Talk' AND deleted_at IS NULL LIMIT 1;

IF v_topic_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM speaking.conversation_missions
    WHERE topic_id = v_topic_id AND deleted_at IS NULL
) THEN
    INSERT INTO speaking.conversation_missions
        (id, topic_id, title, title_japanese, title_vi, description, description_vi,
         mission_type, success_criteria, required_vocabulary, required_grammar,
         order_index, created_by, updated_by, created_at, updated_at)
    VALUES
    (
        uuid_generate_v4(), v_topic_id,
        'Chat About the Weekend', '週末の話をする', 'Trò chuyện về cuối tuần',
        'Engage in natural small talk about what you did over the weekend.',
        'Tham gia vào cuộc trò chuyện tự nhiên về những gì bạn đã làm trong cuối tuần.',
        'SOCIAL',
        '{"requiredActions":["describe weekend activity","ask partner about their weekend","react naturally"],"completionConditions":"Learner maintains a natural multi-turn small talk exchange."}'::JSONB,
        '[{"word":"週末","reading":"しゅうまつ","meaning":"weekend"},{"word":"お疲れ様","reading":"おつかれさま","meaning":"good work"},{"word":"どうでしたか","meaning":"how was it"}]'::JSONB,
        '["週末はいかがでしたか","～に行きました","楽しかったです"]'::JSONB,
        1, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    ),
    (
        uuid_generate_v4(), v_topic_id,
        'Suggest After-Work Plans', '飲み会を提案する', 'Đề xuất kế hoạch sau giờ làm',
        'Invite a colleague to join you for drinks or dinner after work.',
        'Mời đồng nghiệp cùng đi uống hoặc ăn tối sau giờ làm.',
        'SOCIAL',
        '{"requiredActions":["make an invitation","respond to colleague","agree on details"],"completionConditions":"Learner successfully invites a colleague and agrees on after-work plans."}'::JSONB,
        '[{"word":"飲み会","reading":"のみかい","meaning":"drinking party"},{"word":"一緒に","reading":"いっしょに","meaning":"together"},{"word":"どうですか","meaning":"how about it"}]'::JSONB,
        '["今日の仕事の後、飲みに行きませんか","一緒に食事でもどうですか","お疲れ様です、ご一緒しましょう"]'::JSONB,
        2, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
    RAISE NOTICE 'Inserted missions for topic: Office Small Talk';
END IF;

-- ==========================================================================
-- At the Doctor (病院で)
-- ==========================================================================
SELECT id INTO v_topic_id FROM speaking.conversation_topics
WHERE title = 'At the Doctor' AND deleted_at IS NULL LIMIT 1;

IF v_topic_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM speaking.conversation_missions
    WHERE topic_id = v_topic_id AND deleted_at IS NULL
) THEN
    INSERT INTO speaking.conversation_missions
        (id, topic_id, title, title_japanese, title_vi, description, description_vi,
         mission_type, success_criteria, required_vocabulary, required_grammar,
         order_index, created_by, updated_by, created_at, updated_at)
    VALUES
    (
        uuid_generate_v4(), v_topic_id,
        'Describe Symptoms', '症状を説明する', 'Mô tả triệu chứng',
        'Clearly describe your symptoms to the doctor.',
        'Mô tả rõ ràng các triệu chứng của bạn cho bác sĩ.',
        'DESCRIPTIVE',
        '{"requiredActions":["state main symptom","indicate when it started","mention severity"],"completionConditions":"Learner describes symptoms clearly enough for the doctor to understand."}'::JSONB,
        '[{"word":"症状","reading":"しょうじょう","meaning":"symptom"},{"word":"熱","reading":"ねつ","meaning":"fever"},{"word":"のど","meaning":"throat"},{"word":"痛い","reading":"いたい","meaning":"painful"}]'::JSONB,
        '["～が痛いです","熱が～度あります","～日前から～です"]'::JSONB,
        1, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    ),
    (
        uuid_generate_v4(), v_topic_id,
        'Answer Medical Questions', '医師の質問に答える', 'Trả lời câu hỏi của bác sĩ',
        'Answer the doctor''s questions about allergies, medications, and medical history.',
        'Trả lời câu hỏi của bác sĩ về dị ứng, thuốc đang dùng và tiền sử bệnh.',
        'INFORMATION_GATHERING',
        '{"requiredActions":["answer allergy question","mention any current medication","describe past conditions if any"],"completionConditions":"Learner answers all doctor questions accurately."}'::JSONB,
        '[{"word":"アレルギー","meaning":"allergy"},{"word":"薬","reading":"くすり","meaning":"medicine"},{"word":"持病","reading":"じびょう","meaning":"chronic illness"}]'::JSONB,
        '["～のアレルギーがあります","現在～を飲んでいます","特に持病はありません"]'::JSONB,
        2, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    ),
    (
        uuid_generate_v4(), v_topic_id,
        'Understand the Diagnosis', '診断を理解する', 'Hiểu chẩn đoán của bác sĩ',
        'Understand the doctor''s diagnosis and instructions for medication.',
        'Hiểu chẩn đoán của bác sĩ và hướng dẫn dùng thuốc.',
        'PROBLEM_SOLVING',
        '{"requiredActions":["confirm diagnosis understood","ask about medication","confirm follow-up if needed"],"completionConditions":"Learner confirms understanding of diagnosis and prescription."}'::JSONB,
        '[{"word":"処方箋","reading":"しょほうせん","meaning":"prescription"},{"word":"飲み方","reading":"のみかた","meaning":"how to take"},{"word":"安静","reading":"あんせい","meaning":"rest"}]'::JSONB,
        '["どのくらい飲めばいいですか","いつ頃よくなりますか","また来る必要がありますか"]'::JSONB,
        3, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
    RAISE NOTICE 'Inserted missions for topic: At the Doctor';
END IF;

-- ==========================================================================
-- At the Pharmacy (薬局で)
-- ==========================================================================
SELECT id INTO v_topic_id FROM speaking.conversation_topics
WHERE title = 'At the Pharmacy' AND deleted_at IS NULL LIMIT 1;

IF v_topic_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM speaking.conversation_missions
    WHERE topic_id = v_topic_id AND deleted_at IS NULL
) THEN
    INSERT INTO speaking.conversation_missions
        (id, topic_id, title, title_japanese, title_vi, description, description_vi,
         mission_type, success_criteria, required_vocabulary, required_grammar,
         order_index, created_by, updated_by, created_at, updated_at)
    VALUES
    (
        uuid_generate_v4(), v_topic_id,
        'Ask for Medicine', '薬を求める', 'Yêu cầu mua thuốc',
        'Ask the pharmacist for over-the-counter cold medicine.',
        'Hỏi dược sĩ về thuốc cảm không cần đơn.',
        'TRANSACTIONAL',
        '{"requiredActions":["describe symptom","ask for appropriate medicine"],"completionConditions":"Learner obtains the right medicine for their symptoms."}'::JSONB,
        '[{"word":"風邪薬","reading":"かぜぐすり","meaning":"cold medicine"},{"word":"鼻水","reading":"はなみず","meaning":"runny nose"},{"word":"咳","reading":"せき","meaning":"cough"}]'::JSONB,
        '["風邪薬をください","鼻水と咳があります","処方箋なしで買えますか"]'::JSONB,
        1, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    ),
    (
        uuid_generate_v4(), v_topic_id,
        'Understand Dosage Instructions', '服用方法を理解する', 'Hiểu hướng dẫn sử dụng thuốc',
        'Understand and confirm the correct dosage and timing instructions.',
        'Hiểu và xác nhận hướng dẫn liều lượng và thời điểm uống thuốc.',
        'INFORMATION_GATHERING',
        '{"requiredActions":["confirm dosage","ask about timing","ask about side effects"],"completionConditions":"Learner correctly understands dosage and timing from the pharmacist."}'::JSONB,
        '[{"word":"飲み方","reading":"のみかた","meaning":"how to take"},{"word":"食後","reading":"しょくご","meaning":"after meals"},{"word":"副作用","reading":"ふくさよう","meaning":"side effect"}]'::JSONB,
        '["1日何回飲みますか","食後に飲んだほうがいいですか","副作用はありますか"]'::JSONB,
        2, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    ),
    (
        uuid_generate_v4(), v_topic_id,
        'Check for Interactions', '飲み合わせを確認する', 'Kiểm tra tương tác thuốc',
        'Ask the pharmacist if the medicine is safe to take with other medications.',
        'Hỏi dược sĩ xem thuốc có an toàn khi dùng chung với các loại thuốc khác không.',
        'PROBLEM_SOLVING',
        '{"requiredActions":["mention current medication","ask about interaction","receive safe advice"],"completionConditions":"Learner confirms medicine safety with pharmacist."}'::JSONB,
        '[{"word":"飲み合わせ","reading":"のみあわせ","meaning":"drug interaction"},{"word":"一緒に","reading":"いっしょに","meaning":"together"},{"word":"大丈夫","reading":"だいじょうぶ","meaning":"okay/safe"}]'::JSONB,
        '["他の薬と一緒に飲んでも大丈夫ですか","アルコールは避けたほうがいいですか","妊娠中でも飲めますか"]'::JSONB,
        3, v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
    RAISE NOTICE 'Inserted missions for topic: At the Pharmacy';
END IF;

RAISE NOTICE 'Script 17 complete: missions for new topics inserted.';
END $$;

-- --------------------------------------------------------------------------
-- Verification
-- --------------------------------------------------------------------------
SELECT
    ct.title           AS topic_title,
    cm.title           AS mission_title,
    cm.title_vi        AS mission_title_vi,
    cm.mission_type,
    cm.order_index
FROM speaking.conversation_missions cm
JOIN speaking.conversation_topics ct ON ct.id = cm.topic_id
WHERE cm.deleted_at IS NULL
ORDER BY ct.title, cm.order_index;

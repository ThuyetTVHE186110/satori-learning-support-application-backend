-- ==========================================================================
-- Script 20: Cập nhật tiêu đề & mô tả tiếng Việt cho Conversation Themes/Topics
--            + Bổ sung thêm topics mới bằng tiếng Việt
--
-- Chiến lược:
--   1. UPDATE 4 themes hiện có (từ script 14) → thêm title tiếng Việt + description_vi
--   2. UPDATE topics hiện có → thêm title tiếng Việt + description_vi
--   3. INSERT thêm topics mới cho mỗi theme
--
-- An toàn khi chạy lại: các INSERT dùng IF NOT EXISTS theo title tiếng Việt
-- ==========================================================================

DO $$
DECLARE
    v_manager_id        UUID := 'a0000000-0000-0000-0000-000000000002';

    -- UUIDs của 4 themes đã có từ script 14
    v_theme_daily_life  UUID := 'a1000000-0000-0000-0000-000000000001';
    v_theme_travel      UUID := 'a1000000-0000-0000-0000-000000000002';
    v_theme_work_study  UUID := 'a1000000-0000-0000-0000-000000000003';
    v_theme_health      UUID := 'a1000000-0000-0000-0000-000000000004';
BEGIN

-- ==========================================================================
-- 1. CẬP NHẬT THEMES — tiêu đề tiếng Việt + description_vi
-- ==========================================================================

UPDATE speaking.conversation_themes
SET
    title            = 'Cuộc Sống Hàng Ngày tại Nhật Bản',
    description_vi   = 'Luyện tập các hội thoại thường ngày khi sống tại Nhật Bản: chào hỏi, ăn uống, mua sắm và hẹn gặp bạn bè.',
    updated_by       = v_manager_id,
    updated_at       = CURRENT_TIMESTAMP
WHERE id = v_theme_daily_life;

UPDATE speaking.conversation_themes
SET
    title            = 'Du Lịch và Tham Quan tại Nhật Bản',
    description_vi   = 'Tự tin di chuyển khắp Nhật Bản: hỏi đường, nhận phòng khách sạn, đi tàu điện và khám phá các điểm du lịch nổi tiếng.',
    updated_by       = v_manager_id,
    updated_at       = CURRENT_TIMESTAMP
WHERE id = v_theme_travel;

UPDATE speaking.conversation_themes
SET
    title            = 'Công Việc và Học Tập',
    description_vi   = 'Giao tiếp chuyên nghiệp trong môi trường làm việc hoặc học tập tại Nhật Bản: họp nhóm, thuyết trình và trao đổi học thuật.',
    updated_by       = v_manager_id,
    updated_at       = CURRENT_TIMESTAMP
WHERE id = v_theme_work_study;

UPDATE speaking.conversation_themes
SET
    title            = 'Sức Khỏe và Y Tế',
    description_vi   = 'Xử lý các tình huống liên quan đến sức khỏe tại Nhật Bản: khám bác sĩ, mô tả triệu chứng và mua thuốc tại hiệu thuốc.',
    updated_by       = v_manager_id,
    updated_at       = CURRENT_TIMESTAMP
WHERE id = v_theme_health;

RAISE NOTICE 'Đã cập nhật 4 themes với tiêu đề và mô tả tiếng Việt.';

-- ==========================================================================
-- 2. CẬP NHẬT TOPICS HIỆN CÓ — tiêu đề tiếng Việt + description_vi
--    (Nhận diện qua title tiếng Anh từ script 14)
-- ==========================================================================

-- Daily Life topics
UPDATE speaking.conversation_topics
SET title = 'Tự Giới Thiệu',
    description_vi = 'Học cách giới thiệu bản thân bằng tiếng Nhật: tên, quốc tịch, nghề nghiệp và sở thích. Bài luyện tập quan trọng nhất cho người mới bắt đầu.',
    updated_by = v_manager_id, updated_at = CURRENT_TIMESTAMP
WHERE title = 'Self Introduction' AND deleted_at IS NULL;

UPDATE speaking.conversation_topics
SET title = 'Tại Nhà Hàng',
    description_vi = 'Thực hành gọi món, hỏi về thực đơn và thanh toán tại nhà hàng Nhật Bản. Học cách ứng xử lịch sự khi dùng bữa.',
    updated_by = v_manager_id, updated_at = CURRENT_TIMESTAMP
WHERE title = 'At a Restaurant' AND deleted_at IS NULL;

UPDATE speaking.conversation_topics
SET title = 'Đi Mua Sắm',
    description_vi = 'Luyện tập hỏi giá, tìm sản phẩm và thanh toán tại cửa hàng. Học các cụm từ cần thiết khi mua sắm ở Nhật Bản.',
    updated_by = v_manager_id, updated_at = CURRENT_TIMESTAMP
WHERE title = 'Shopping' AND deleted_at IS NULL;

UPDATE speaking.conversation_topics
SET title = 'Lên Kế Hoạch Với Bạn Bè',
    description_vi = 'Học cách đề xuất, đồng ý hoặc từ chối các kế hoạch cuối tuần bằng tiếng Nhật. Thực hành hội thoại tự nhiên với bạn bè.',
    updated_by = v_manager_id, updated_at = CURRENT_TIMESTAMP
WHERE title = 'Making Plans' AND deleted_at IS NULL;

UPDATE speaking.conversation_topics
SET title = 'Nói Về Sở Thích',
    description_vi = 'Chia sẻ và trao đổi về sở thích, hoạt động giải trí và cuộc sống hàng ngày với bạn bè người Nhật.',
    updated_by = v_manager_id, updated_at = CURRENT_TIMESTAMP
WHERE title = 'Talking About Hobbies' AND deleted_at IS NULL;

UPDATE speaking.conversation_topics
SET title = 'Tại Cửa Hàng Tiện Lợi',
    description_vi = 'Thực hành mua đồ dùng hàng ngày và sử dụng các dịch vụ tại cửa hàng tiện lợi Nhật Bản như 7-Eleven hay Lawson.',
    updated_by = v_manager_id, updated_at = CURRENT_TIMESTAMP
WHERE title = 'At the Convenience Store' AND deleted_at IS NULL;

-- Travel topics
UPDATE speaking.conversation_topics
SET title = 'Hỏi Đường',
    description_vi = 'Học cách hỏi và chỉ đường bằng tiếng Nhật. Thực hành các câu hỏi và hướng dẫn phổ biến khi đi lại trong thành phố.',
    updated_by = v_manager_id, updated_at = CURRENT_TIMESTAMP
WHERE title = 'Asking Directions' AND deleted_at IS NULL;

UPDATE speaking.conversation_topics
SET title = 'Tại Khách Sạn',
    description_vi = 'Thực hành nhận phòng, hỏi về tiện nghi và giải quyết vấn đề tại khách sạn Nhật Bản. Học nghi thức lịch sự cần thiết.',
    updated_by = v_manager_id, updated_at = CURRENT_TIMESTAMP
WHERE title = 'At the Hotel' AND deleted_at IS NULL;

UPDATE speaking.conversation_topics
SET title = 'Tại Ga Tàu',
    description_vi = 'Mua vé, hỏi về giờ tàu và điều hướng hệ thống tàu điện phức tạp của Nhật Bản. Bao gồm cả tàu Shinkansen.',
    updated_by = v_manager_id, updated_at = CURRENT_TIMESTAMP
WHERE title = 'At the Train Station' AND deleted_at IS NULL;

UPDATE speaking.conversation_topics
SET title = 'Mua Đồ Lưu Niệm',
    description_vi = 'Thực hành mua quà lưu niệm, hỏi về sản phẩm đặc trưng và yêu cầu đóng gói quà tặng tại các cửa hàng ở Nhật.',
    updated_by = v_manager_id, updated_at = CURRENT_TIMESTAMP
WHERE title = 'Buying Souvenirs' AND deleted_at IS NULL;

-- Work & Study topics
UPDATE speaking.conversation_topics
SET title = 'Phỏng Vấn Xin Việc',
    description_vi = 'Luyện tập phỏng vấn xin việc bằng tiếng Nhật trang trọng: giới thiệu bản thân, trả lời câu hỏi và thể hiện động lực ứng tuyển.',
    updated_by = v_manager_id, updated_at = CURRENT_TIMESTAMP
WHERE title = 'Job Interview' AND deleted_at IS NULL;

UPDATE speaking.conversation_topics
SET title = 'Trò Chuyện Với Đồng Nghiệp',
    description_vi = 'Học cách tạo mối quan hệ tốt với đồng nghiệp người Nhật qua các cuộc trò chuyện thân thiện trong giờ nghỉ hoặc sau giờ làm.',
    updated_by = v_manager_id, updated_at = CURRENT_TIMESTAMP
WHERE title = 'Office Small Talk' AND deleted_at IS NULL;

-- Health topics
UPDATE speaking.conversation_topics
SET title = 'Khám Bác Sĩ',
    description_vi = 'Mô tả triệu chứng bệnh, trả lời câu hỏi của bác sĩ và hiểu hướng dẫn điều trị bằng tiếng Nhật. Học từ vựng y tế cơ bản.',
    updated_by = v_manager_id, updated_at = CURRENT_TIMESTAMP
WHERE title = 'At the Doctor' AND deleted_at IS NULL;

UPDATE speaking.conversation_topics
SET title = 'Tại Hiệu Thuốc',
    description_vi = 'Hỏi mua thuốc không cần kê đơn, hỏi về liều lượng và tác dụng phụ tại hiệu thuốc Nhật Bản. Học từ vựng về thuốc và bệnh thông thường.',
    updated_by = v_manager_id, updated_at = CURRENT_TIMESTAMP
WHERE title = 'At the Pharmacy' AND deleted_at IS NULL;

RAISE NOTICE 'Đã cập nhật các topics hiện có với tiêu đề và mô tả tiếng Việt.';

-- ==========================================================================
-- 3. BỔ SUNG TOPICS MỚI — Daily Life (v_theme_daily_life)
-- ==========================================================================

IF NOT EXISTS (
    SELECT 1 FROM speaking.conversation_topics WHERE title = 'Thuê Nhà và Căn Hộ' AND deleted_at IS NULL
) THEN
    INSERT INTO speaking.conversation_topics
        (id, theme_id, title, title_japanese, description, description_vi, scenario, category,
         jlpt_level, difficulty_score, is_roleplay, vocabulary_hints, grammar_points,
         useful_expressions, status, order_index, created_by, updated_by, created_at, updated_at)
    VALUES (
        uuid_generate_v4(), v_theme_daily_life,
        'Thuê Nhà và Căn Hộ', '家を借りる',
        'Inquire about renting an apartment and communicate with a landlord or real estate agent in Japanese.',
        'Học cách hỏi về việc thuê nhà, trao đổi với chủ nhà hoặc môi giới bất động sản và hiểu các điều khoản hợp đồng thuê nhà tại Nhật.',
        'Bạn đang tìm thuê một căn hộ 1K gần trường đại học. Nhân viên môi giới giải thích các điều khoản hợp đồng và yêu cầu của chủ nhà.',
        'daily_life', 'N4', 4, true,
        '[{"word":"家賃","reading":"やちん","meaning":"tiền thuê nhà"},{"word":"敷金","reading":"しきん","meaning":"tiền đặt cọc"},{"word":"礼金","reading":"れいきん","meaning":"tiền lễ"},{"word":"間取り","reading":"まどり","meaning":"sơ đồ phòng"},{"word":"契約","reading":"けいやく","meaning":"hợp đồng"}]'::JSONB,
        '["～はいくらですか","～が含まれていますか","～は可能ですか","いつから入居できますか"]'::JSONB,
        '["ペット可能ですか","光熱費は別途ですか","最寄り駅から何分ですか","契約期間はどのくらいですか"]'::JSONB,
        'PUBLISHED', 9,
        v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
    RAISE NOTICE 'Đã thêm topic: Thuê Nhà và Căn Hộ';
END IF;

IF NOT EXISTS (
    SELECT 1 FROM speaking.conversation_topics WHERE title = 'Hỏi Thăm và Nói Về Thời Tiết' AND deleted_at IS NULL
) THEN
    INSERT INTO speaking.conversation_topics
        (id, theme_id, title, title_japanese, description, description_vi, scenario, category,
         jlpt_level, difficulty_score, is_roleplay, vocabulary_hints, grammar_points,
         useful_expressions, status, order_index, created_by, updated_by, created_at, updated_at)
    VALUES (
        uuid_generate_v4(), v_theme_daily_life,
        'Hỏi Thăm và Nói Về Thời Tiết', '天気について話す',
        'Talk about the weather and daily greetings — a staple of Japanese small talk.',
        'Nói về thời tiết là chủ đề hội thoại phổ biến nhất ở Nhật Bản. Học cách mở đầu cuộc trò chuyện tự nhiên với người Nhật qua chủ đề quen thuộc này.',
        'Buổi sáng bạn gặp hàng xóm ở hành lang chung cư. Hai người nói chuyện về thời tiết hôm nay và dự báo thời tiết tuần tới.',
        'daily_life', 'N5', 1, true,
        '[{"word":"天気","reading":"てんき","meaning":"thời tiết"},{"word":"暑い","reading":"あつい","meaning":"nóng"},{"word":"寒い","reading":"さむい","meaning":"lạnh"},{"word":"雨","reading":"あめ","meaning":"mưa"},{"word":"晴れ","reading":"はれ","meaning":"nắng"}]'::JSONB,
        '["今日は～ですね","～になりそうです","～でしたね","～でしょうか"]'::JSONB,
        '["今日はいい天気ですね","傘を持っていきます","寒くなってきましたね","週末は晴れるといいですね"]'::JSONB,
        'PUBLISHED', 10,
        v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
    RAISE NOTICE 'Đã thêm topic: Hỏi Thăm và Nói Về Thời Tiết';
END IF;

IF NOT EXISTS (
    SELECT 1 FROM speaking.conversation_topics WHERE title = 'Giao Nhận Bưu Kiện và Dịch Vụ Giao Hàng' AND deleted_at IS NULL
) THEN
    INSERT INTO speaking.conversation_topics
        (id, theme_id, title, title_japanese, description, description_vi, scenario, category,
         jlpt_level, difficulty_score, is_roleplay, vocabulary_hints, grammar_points,
         useful_expressions, status, order_index, created_by, updated_by, created_at, updated_at)
    VALUES (
        uuid_generate_v4(), v_theme_daily_life,
        'Giao Nhận Bưu Kiện và Dịch Vụ Giao Hàng', '荷物の受け取り・配達',
        'Interact with delivery staff and manage parcel deliveries in Japan.',
        'Học cách giao tiếp với nhân viên giao hàng, đặt lịch giao lại và gửi bưu kiện tại bưu điện hoặc cửa hàng tiện lợi ở Nhật Bản.',
        'Nhân viên giao hàng đến khi bạn không có nhà. Bạn gọi điện để đặt lại lịch giao hàng vào buổi tối hôm sau.',
        'daily_life', 'N4', 3, true,
        '[{"word":"荷物","reading":"にもつ","meaning":"hành lý / bưu kiện"},{"word":"不在票","reading":"ふざいひょう","meaning":"phiếu báo vắng nhà"},{"word":"再配達","reading":"さいはいたつ","meaning":"giao lại"},{"word":"受け取り","reading":"うけとり","meaning":"nhận hàng"},{"word":"伝票番号","reading":"でんぴょうばんごう","meaning":"số đơn hàng"}]'::JSONB,
        '["～を再配達してもらえますか","～時に届けてもらえますか","不在票の番号は～です"]'::JSONB,
        '["明日の夜7時以降にお願いします","コンビニ受け取りに変更できますか","追跡番号で確認しました"]'::JSONB,
        'PUBLISHED', 11,
        v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
    RAISE NOTICE 'Đã thêm topic: Giao Nhận Bưu Kiện và Dịch Vụ Giao Hàng';
END IF;

-- ==========================================================================
-- 4. BỔ SUNG TOPICS MỚI — Du Lịch (v_theme_travel)
-- ==========================================================================

IF NOT EXISTS (
    SELECT 1 FROM speaking.conversation_topics WHERE title = 'Đặt Bàn Nhà Hàng' AND deleted_at IS NULL
) THEN
    INSERT INTO speaking.conversation_topics
        (id, theme_id, title, title_japanese, description, description_vi, scenario, category,
         jlpt_level, difficulty_score, is_roleplay, vocabulary_hints, grammar_points,
         useful_expressions, status, order_index, created_by, updated_by, created_at, updated_at)
    VALUES (
        uuid_generate_v4(), v_theme_travel,
        'Đặt Bàn Nhà Hàng', 'レストランの予約',
        'Make and confirm a restaurant reservation over the phone or in person.',
        'Học cách gọi điện đặt bàn, nêu yêu cầu đặc biệt và xác nhận hoặc hủy đặt chỗ tại nhà hàng Nhật Bản.',
        'Bạn muốn đặt bàn cho 4 người tại một nhà hàng ramen nổi tiếng vào tối thứ Sáu. Bạn gọi điện để đặt chỗ và hỏi về thực đơn.',
        'travel', 'N4', 3, true,
        '[{"word":"予約","reading":"よやく","meaning":"đặt trước / đặt chỗ"},{"word":"席","reading":"せき","meaning":"chỗ ngồi"},{"word":"人数","reading":"にんずう","meaning":"số người"},{"word":"禁煙","reading":"きんえん","meaning":"cấm hút thuốc"},{"word":"個室","reading":"こしつ","meaning":"phòng riêng"}]'::JSONB,
        '["～名で予約したいのですが","～時に席を取れますか","禁煙席をお願いします"]'::JSONB,
        '["4名で金曜日の夜7時にお願いします","アレルギーがあります","個室はありますか"]'::JSONB,
        'PUBLISHED', 9,
        v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
    RAISE NOTICE 'Đã thêm topic: Đặt Bàn Nhà Hàng';
END IF;

IF NOT EXISTS (
    SELECT 1 FROM speaking.conversation_topics WHERE title = 'Tham Quan Di Tích Lịch Sử' AND deleted_at IS NULL
) THEN
    INSERT INTO speaking.conversation_topics
        (id, theme_id, title, title_japanese, description, description_vi, scenario, category,
         jlpt_level, difficulty_score, is_roleplay, vocabulary_hints, grammar_points,
         useful_expressions, status, order_index, created_by, updated_by, created_at, updated_at)
    VALUES (
        uuid_generate_v4(), v_theme_travel,
        'Tham Quan Di Tích Lịch Sử', '歴史的な観光地を訪れる',
        'Learn vocabulary and phrases for visiting temples, shrines, and historical sites in Japan.',
        'Khám phá các địa danh lịch sử nổi tiếng của Nhật Bản. Học cách hỏi về lịch sử, quy tắc tham quan và mua vé vào cửa.',
        'Bạn đang tham quan chùa Kinkaku-ji ở Kyoto. Bạn hỏi hướng dẫn viên về lịch sử ngôi chùa và những điều cần lưu ý khi tham quan.',
        'travel', 'N4', 3, true,
        '[{"word":"神社","reading":"じんじゃ","meaning":"đền thờ Shinto"},{"word":"寺","reading":"てら","meaning":"chùa"},{"word":"入場料","reading":"にゅうじょうりょう","meaning":"phí vào cửa"},{"word":"拝観","reading":"はいかん","meaning":"tham quan (chùa)"},{"word":"御朱印","reading":"ごしゅいん","meaning":"dấu ấn đền chùa"}]'::JSONB,
        '["いつ建てられましたか","～の意味は何ですか","写真を撮ってもいいですか"]'::JSONB,
        '["この場所の歴史を教えてもらえますか","営業時間は何時までですか","御朱印はもらえますか"]'::JSONB,
        'PUBLISHED', 10,
        v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
    RAISE NOTICE 'Đã thêm topic: Tham Quan Di Tích Lịch Sử';
END IF;

IF NOT EXISTS (
    SELECT 1 FROM speaking.conversation_topics WHERE title = 'Đổi Tiền và Sử Dụng ATM' AND deleted_at IS NULL
) THEN
    INSERT INTO speaking.conversation_topics
        (id, theme_id, title, title_japanese, description, description_vi, scenario, category,
         jlpt_level, difficulty_score, is_roleplay, vocabulary_hints, grammar_points,
         useful_expressions, status, order_index, created_by, updated_by, created_at, updated_at)
    VALUES (
        uuid_generate_v4(), v_theme_travel,
        'Đổi Tiền và Sử Dụng ATM', '両替とATMの使い方',
        'Handle currency exchange and use ATMs during your trip to Japan.',
        'Học cách đổi tiền tại quầy ngân hàng hoặc sân bay, hỏi về tỷ giá và sử dụng ATM tại Nhật Bản. Quan trọng khi du lịch vì nhiều nơi chỉ nhận tiền mặt.',
        'Bạn vừa đến sân bay Narita và cần đổi tiền Việt Nam sang Yên Nhật. Bạn đến quầy đổi tiền và hỏi về tỷ giá cùng phí giao dịch.',
        'travel', 'N3', 3, true,
        '[{"word":"両替","reading":"りょうがえ","meaning":"đổi tiền"},{"word":"為替レート","reading":"かわせレート","meaning":"tỷ giá hối đoái"},{"word":"手数料","reading":"てすうりょう","meaning":"phí giao dịch"},{"word":"小銭","reading":"こぜに","meaning":"tiền lẻ"},{"word":"千円札","reading":"せんえんさつ","meaning":"tờ 1000 yên"}]'::JSONB,
        '["～円に両替したいのですが","レートはいくらですか","手数料はかかりますか"]'::JSONB,
        '["1万円札を小銭に崩せますか","今日のレートはいくらですか","ATMはどこにありますか"]'::JSONB,
        'PUBLISHED', 11,
        v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
    RAISE NOTICE 'Đã thêm topic: Đổi Tiền và Sử Dụng ATM';
END IF;

-- ==========================================================================
-- 5. BỔ SUNG TOPICS MỚI — Công Việc và Học Tập (v_theme_work_study)
-- ==========================================================================

IF NOT EXISTS (
    SELECT 1 FROM speaking.conversation_topics WHERE title = 'Họp Nhóm và Thuyết Trình' AND deleted_at IS NULL
) THEN
    INSERT INTO speaking.conversation_topics
        (id, theme_id, title, title_japanese, description, description_vi, scenario, category,
         jlpt_level, difficulty_score, is_roleplay, vocabulary_hints, grammar_points,
         useful_expressions, status, order_index, created_by, updated_by, created_at, updated_at)
    VALUES (
        uuid_generate_v4(), v_theme_work_study,
        'Họp Nhóm và Thuyết Trình', 'グループミーティングとプレゼン',
        'Participate effectively in a Japanese business meeting and deliver a short presentation.',
        'Học cách tham gia họp nhóm, đưa ra ý kiến, đồng ý/không đồng ý một cách lịch sự và thuyết trình ngắn gọn bằng tiếng Nhật trang trọng.',
        'Bạn tham gia một cuộc họp nhóm về dự án mới. Trưởng nhóm hỏi ý kiến từng người và bạn cần trình bày đề xuất của mình.',
        'work', 'N3', 5, true,
        '[{"word":"議題","reading":"ぎだい","meaning":"chủ đề cuộc họp"},{"word":"提案","reading":"ていあん","meaning":"đề xuất"},{"word":"意見","reading":"いけん","meaning":"ý kiến"},{"word":"資料","reading":"しりょう","meaning":"tài liệu"},{"word":"締め切り","reading":"しめきり","meaning":"hạn chót"}]'::JSONB,
        '["～についてご意見はいかがでしょうか","ご提案があるのですが","～点について確認させてください"]'::JSONB,
        '["ご説明いただきありがとうございます","その点については検討が必要かと思います","スケジュールを共有していただけますか"]'::JSONB,
        'PUBLISHED', 3,
        v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
    RAISE NOTICE 'Đã thêm topic: Họp Nhóm và Thuyết Trình';
END IF;

IF NOT EXISTS (
    SELECT 1 FROM speaking.conversation_topics WHERE title = 'Hỏi Thầy Cô và Học Nhóm' AND deleted_at IS NULL
) THEN
    INSERT INTO speaking.conversation_topics
        (id, theme_id, title, title_japanese, description, description_vi, scenario, category,
         jlpt_level, difficulty_score, is_roleplay, vocabulary_hints, grammar_points,
         useful_expressions, status, order_index, created_by, updated_by, created_at, updated_at)
    VALUES (
        uuid_generate_v4(), v_theme_work_study,
        'Hỏi Thầy Cô và Học Nhóm', '先生への質問・グループ学習',
        'Ask professors questions and collaborate with classmates in a Japanese academic setting.',
        'Học cách đặt câu hỏi với giáo sư, nhờ giải thích bài giảng và phối hợp với bạn học trong môi trường đại học Nhật Bản.',
        'Sau buổi giảng, bạn đến gặp giáo sư để hỏi về một phần bài giảng khó hiểu và xin lời khuyên về đề tài tiểu luận.',
        'work', 'N3', 4, true,
        '[{"word":"授業","reading":"じゅぎょう","meaning":"bài giảng"},{"word":"レポート","meaning":"báo cáo / tiểu luận"},{"word":"質問","reading":"しつもん","meaning":"câu hỏi"},{"word":"研究室","reading":"けんきゅうしつ","meaning":"phòng nghiên cứu"},{"word":"単位","reading":"たんい","meaning":"tín chỉ"}]'::JSONB,
        '["～についてお聞きしてもよろしいですか","～の点がよく理解できなかったのですが","アドバイスをいただけますか"]'::JSONB,
        '["先生、少しよろしいでしょうか","レポートのテーマについて相談があります","オフィスアワーはいつですか"]'::JSONB,
        'PUBLISHED', 4,
        v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
    RAISE NOTICE 'Đã thêm topic: Hỏi Thầy Cô và Học Nhóm';
END IF;

IF NOT EXISTS (
    SELECT 1 FROM speaking.conversation_topics WHERE title = 'Giao Tiếp Qua Email và Điện Thoại' AND deleted_at IS NULL
) THEN
    INSERT INTO speaking.conversation_topics
        (id, theme_id, title, title_japanese, description, description_vi, scenario, category,
         jlpt_level, difficulty_score, is_roleplay, vocabulary_hints, grammar_points,
         useful_expressions, status, order_index, created_by, updated_by, created_at, updated_at)
    VALUES (
        uuid_generate_v4(), v_theme_work_study,
        'Giao Tiếp Qua Email và Điện Thoại', 'ビジネスメールと電話対応',
        'Write professional emails and handle business calls in Japanese.',
        'Học cách viết email công việc trang trọng và xử lý cuộc gọi điện thoại trong môi trường doanh nghiệp Nhật Bản. Tập trung vào ngôn ngữ kính ngữ cơ bản.',
        'Bạn cần gọi điện cho đối tác để xác nhận lịch họp và sau đó viết email tóm tắt nội dung cuộc trò chuyện.',
        'work', 'N3', 6, true,
        '[{"word":"担当者","reading":"たんとうしゃ","meaning":"người phụ trách"},{"word":"折り返し","reading":"おりかえし","meaning":"gọi lại"},{"word":"伝言","reading":"でんごん","meaning":"nhắn tin"},{"word":"確認","reading":"かくにん","meaning":"xác nhận"},{"word":"よろしくお願いします","meaning":"cảm ơn / trân trọng"}]'::JSONB,
        '["～の件でご連絡いたしました","～をご確認いただけますか","お時間よろしいでしょうか"]'::JSONB,
        '["お世話になっております","後ほど折り返しご連絡いたします","お電話ありがとうございます"]'::JSONB,
        'PUBLISHED', 5,
        v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
    RAISE NOTICE 'Đã thêm topic: Giao Tiếp Qua Email và Điện Thoại';
END IF;

-- ==========================================================================
-- 6. BỔ SUNG TOPICS MỚI — Sức Khỏe và Y Tế (v_theme_health)
-- ==========================================================================

IF NOT EXISTS (
    SELECT 1 FROM speaking.conversation_topics WHERE title = 'Đặt Lịch Khám và Hủy Hẹn' AND deleted_at IS NULL
) THEN
    INSERT INTO speaking.conversation_topics
        (id, theme_id, title, title_japanese, description, description_vi, scenario, category,
         jlpt_level, difficulty_score, is_roleplay, vocabulary_hints, grammar_points,
         useful_expressions, status, order_index, created_by, updated_by, created_at, updated_at)
    VALUES (
        uuid_generate_v4(), v_theme_health,
        'Đặt Lịch Khám và Hủy Hẹn', '診察の予約とキャンセル',
        'Book and manage medical appointments over the phone in Japanese.',
        'Học cách gọi điện đặt lịch khám bệnh, mô tả lý do cần khám và hủy hoặc đổi lịch hẹn tại phòng khám Nhật Bản.',
        'Bạn cần đặt lịch khám mắt vào cuối tuần. Bạn gọi điện cho phòng khám, mô tả tình trạng mắt và chọn thời gian phù hợp.',
        'health', 'N4', 3, true,
        '[{"word":"予約","reading":"よやく","meaning":"đặt lịch hẹn"},{"word":"初診","reading":"しょしん","meaning":"khám lần đầu"},{"word":"診察","reading":"しんさつ","meaning":"khám bệnh"},{"word":"保険証","reading":"ほけんしょう","meaning":"thẻ bảo hiểm y tế"},{"word":"キャンセル","meaning":"hủy"}]'::JSONB,
        '["～の予約をしたいのですが","いつが空いていますか","～科を受診したいです"]'::JSONB,
        '["初めて受診するのですが","健康保険に加入しています","予約をキャンセルしたいのですが"]'::JSONB,
        'PUBLISHED', 3,
        v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
    RAISE NOTICE 'Đã thêm topic: Đặt Lịch Khám và Hủy Hẹn';
END IF;

IF NOT EXISTS (
    SELECT 1 FROM speaking.conversation_topics WHERE title = 'Tư Vấn Sức Khỏe và Chế Độ Ăn Uống' AND deleted_at IS NULL
) THEN
    INSERT INTO speaking.conversation_topics
        (id, theme_id, title, title_japanese, description, description_vi, scenario, category,
         jlpt_level, difficulty_score, is_roleplay, vocabulary_hints, grammar_points,
         useful_expressions, status, order_index, created_by, updated_by, created_at, updated_at)
    VALUES (
        uuid_generate_v4(), v_theme_health,
        'Tư Vấn Sức Khỏe và Chế Độ Ăn Uống', '健康相談と食事のアドバイス',
        'Discuss nutrition, diet, and healthy lifestyle with a health professional in Japanese.',
        'Học cách trao đổi với chuyên gia dinh dưỡng hoặc bác sĩ về chế độ ăn uống, thói quen sinh hoạt và cách duy trì sức khỏe tốt ở Nhật Bản.',
        'Bạn gặp bác sĩ dinh dưỡng để xin tư vấn về chế độ ăn uống phù hợp. Bác sĩ hỏi về thói quen ăn uống và đề xuất thực đơn cải thiện sức khỏe.',
        'health', 'N3', 4, true,
        '[{"word":"栄養","reading":"えいよう","meaning":"dinh dưỡng"},{"word":"食生活","reading":"しょくせいかつ","meaning":"chế độ ăn uống"},{"word":"カロリー","meaning":"calo"},{"word":"バランス","meaning":"cân bằng"},{"word":"アレルギー","meaning":"dị ứng"}]'::JSONB,
        '["～を控えた方がいいですか","どんな食事がおすすめですか","～のアレルギーがあります"]'::JSONB,
        '["野菜をもっと食べるようにします","甘いものが好きなのですが","週に何回運動すればいいですか"]'::JSONB,
        'PUBLISHED', 4,
        v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
    RAISE NOTICE 'Đã thêm topic: Tư Vấn Sức Khỏe và Chế Độ Ăn Uống';
END IF;

IF NOT EXISTS (
    SELECT 1 FROM speaking.conversation_topics WHERE title = 'Cấp Cứu và Gọi Xe Cứu Thương' AND deleted_at IS NULL
) THEN
    INSERT INTO speaking.conversation_topics
        (id, theme_id, title, title_japanese, description, description_vi, scenario, category,
         jlpt_level, difficulty_score, is_roleplay, vocabulary_hints, grammar_points,
         useful_expressions, status, order_index, created_by, updated_by, created_at, updated_at)
    VALUES (
        uuid_generate_v4(), v_theme_health,
        'Cấp Cứu và Gọi Xe Cứu Thương', '救急車の呼び方・緊急時の対応',
        'Handle emergency situations and communicate with emergency services in Japanese.',
        'Học cách gọi xe cứu thương (119), mô tả tình huống khẩn cấp và hướng dẫn nhân viên y tế đến đúng địa điểm. Kỹ năng quan trọng cần biết khi sống tại Nhật.',
        'Người bạn cùng phòng ngột ngã và mất ý thức. Bạn gọi 119 để gọi xe cứu thương và mô tả tình trạng bệnh nhân cũng như địa chỉ.',
        'health', 'N4', 5, true,
        '[{"word":"救急車","reading":"きゅうきゅうしゃ","meaning":"xe cứu thương"},{"word":"意識","reading":"いしき","meaning":"ý thức"},{"word":"呼吸","reading":"こきゅう","meaning":"hơi thở"},{"word":"住所","reading":"じゅうしょ","meaning":"địa chỉ"},{"word":"症状","reading":"しょうじょう","meaning":"triệu chứng"}]'::JSONB,
        '["救急車をお願いします","意識がありません","住所は～です","今どんな状態ですか"]'::JSONB,
        '["119番に電話します","友人が倒れました","呼吸はしています","早く来てください"]'::JSONB,
        'PUBLISHED', 5,
        v_manager_id, v_manager_id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    );
    RAISE NOTICE 'Đã thêm topic: Cấp Cứu và Gọi Xe Cứu Thương';
END IF;

END $$;

-- ==========================================================================
-- Xác minh kết quả
-- ==========================================================================
SELECT
    th.title          AS chu_de_lon,
    th.description_vi AS mo_ta_viet,
    th.jlpt_level,
    th.status,
    COUNT(tp.id)      AS so_topic
FROM speaking.conversation_themes th
LEFT JOIN speaking.conversation_topics tp
    ON tp.theme_id = th.id AND tp.deleted_at IS NULL
WHERE th.deleted_at IS NULL
GROUP BY th.id, th.title, th.description_vi, th.jlpt_level, th.status
ORDER BY th.order_index;

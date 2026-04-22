Bạn là giáo viên tiếng Nhật chuyên nghiệp, tạo {count} câu hỏi luyện tập cấp độ {jlptLevel} cho học viên Việt Nam.

{contentBlock}

{distractorBlock}

{learnerProfileBlock}

{dimensionBlock}

{coverageBlock}

{antiRepeatBlock}

=== YÊU CẦU KỲ NÀY ===
Số câu: {count} | JLPT: {jlptLevel} | Loại phiên: {sessionTypeDescription}
Dạng câu hỏi: {itemTypeInstruction}

=== QUY TẮC CẤU TRÚC TỪNG DẠNG ===
{itemTypeRules}

=== CÁC TRƯỜNG JSON (mỗi phần tử) ===
- "itemType"         : tên dạng câu hỏi
- "question"         : câu hỏi / yêu cầu
- "questionJapanese" : câu tiếng Nhật nếu cần (null nếu không)
- "questionJapaneseWithFurigana" : GIỐNG y câu "questionJapanese" nhưng MỌI từ có kanji đều được bọc theo cú pháp ruby `[kanji]{reading}`. Ví dụ: [先生]{せんせい}と[話]{はな}しました。Quy tắc: (1) chú âm mọi từ kanji kể cả động từ/danh từ một kanji; (2) KHÔNG chú âm hiragana/katakana; (3) KHÔNG thêm khoảng trắng trong/ngoài `[]` và `{}`; (4) nếu không có kanji, trả về giống "questionJapanese"; (5) null nếu "questionJapanese" là null; (6) KHÔNG đưa cú pháp `[]{}` này vào "questionJapanese" — "questionJapanese" chỉ chứa tiếng Nhật thuần (dùng cho TTS).
- "options"          : xem quy tắc từng dạng
- "pairs"            : chỉ MATCHING, null cho dạng khác
- "correctAnswer"    : đáp án đúng (null nếu MATCHING)
- "hint"             : gợi ý ngắn (null nếu không có)
- "explanation"      : giải thích tại sao đúng, bằng tiếng Việt
- "refContentId"     : shortId [xxxxxxxx] của từ/ngữ pháp đang được hỏi — BẮT BUỘC, KHÔNG để null
- "refContentType"   : "VOCABULARY" hoặc "GRAMMAR"

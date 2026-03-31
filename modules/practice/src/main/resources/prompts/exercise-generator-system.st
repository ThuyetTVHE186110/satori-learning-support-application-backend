Bạn là giáo viên tiếng Nhật chuyên nghiệp. Tạo {count} câu hỏi luyện tập cấp độ {jlptLevel}.

LOẠI NỘI DUNG: {sessionTypeDescription}

NỘI DUNG BÀI HỌC (chỉ dùng từ danh sách này):
{contentSummary}
{ragContext}

DẠNG CÂU HỎI CẦN SINH:
{itemTypeInstruction}

QUY TẮC TỪNG DẠNG (bắt buộc tuân thủ):
- MULTIPLE_CHOICE: options = 4 object {text,isCorrect}, đúng 1 sai 3. Dùng từ trong "ngữ cảnh bổ sung" làm đáp án sai.
- FILL_BLANK: câu có ___ cần điền, options = []
- TRANSLATION: dịch câu Nhật→Việt hoặc Việt→Nhật, options = []
- SENTENCE_ORDER: options = danh sách từ rời (isCorrect=false với mọi từ), correctAnswer = câu hoàn chỉnh đúng thứ tự
- MATCHING: ghép từ với nghĩa, options = 4 object (1 đúng isCorrect=true, 3 sai isCorrect=false)
- TRUE_FALSE: phát biểu về từ/ngữ pháp, options = [{"text":"Đúng","isCorrect":X},{"text":"Sai","isCorrect":Y}] — chỉ 1 true

QUAN TRỌNG:
- Trường "itemType" phải khớp đúng tên dạng (MULTIPLE_CHOICE, FILL_BLANK, TRANSLATION, SENTENCE_ORDER, MATCHING, TRUE_FALSE)
- Trả về JSON array THUẦN — không markdown, không text thừa, bắt đầu bằng "["
- options PHẢI là array object {"text":"...","isCorrect":true/false}, KHÔNG được là array string

Cấu trúc mỗi phần tử:
{
  "itemType": "MULTIPLE_CHOICE",
  "question": "câu hỏi bằng tiếng Việt hoặc tiếng Nhật",
  "questionJapanese": "câu hỏi tiếng Nhật (null nếu không cần)",
  "options": [{"text": "...", "isCorrect": true}],
  "correctAnswer": "đáp án đúng dạng text",
  "hint": "gợi ý ngắn (null nếu không có)",
  "explanation": "giải thích tại sao đúng, bằng tiếng Việt",
  "refContentId": "uuid của vocab/grammar được dùng (null nếu không có)",
  "refContentType": "VOCABULARY hoặc GRAMMAR hoặc null"
}

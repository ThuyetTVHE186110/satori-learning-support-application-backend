Bạn là giáo viên tiếng Nhật chuyên nghiệp. Tạo {count} câu hỏi luyện tập cấp độ {jlptLevel}.

LOẠI NỘI DUNG: {sessionTypeDescription}

NỘI DUNG BÀI HỌC (chỉ dùng từ danh sách này):
{contentSummary}
{ragContext}

DẠNG CÂU HỎI CẦN SINH:
{itemTypeInstruction}

QUY TẮC DẠNG CÂU HỎI (bắt buộc tuân thủ):
{itemTypeRules}

QUAN TRỌNG:
- Trường "itemType" phải khớp ĐÚNG với tên dạng được yêu cầu ở trên
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

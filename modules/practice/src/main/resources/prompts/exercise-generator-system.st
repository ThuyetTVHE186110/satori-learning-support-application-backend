Bạn là giáo viên tiếng Nhật chuyên nghiệp. Tạo {count} câu hỏi luyện tập {typeDescription} cho cấp độ {jlptLevel}.

{contentSummary}
{ragContext}

YÊU CẦU LOẠI CÂU HỎI: {sessionType}
- MULTIPLE_CHOICE: 4 lựa chọn (1 đúng, 3 sai). Dùng từ trong "ngữ cảnh bổ sung" làm lựa chọn sai.
- FILL_BLANK: điền vào chỗ trống, options = []
- TRANSLATION: dịch câu, options = []
- SENTENCE_ORDER: sắp xếp từ thành câu, options = danh sách từ cần sắp xếp
- MATCHING: ghép cặp, 4 options (1 đúng, 3 sai)

Trả về JSON array THUẦN (không có markdown fence, không có text thêm, bắt đầu bằng "[").
options PHẢI là array object {"text":"...","isCorrect":true/false}, KHÔNG ĐƯỢC là array string.
Mỗi phần tử có cấu trúc:
{
  "question": "câu hỏi bằng tiếng Việt hoặc tiếng Nhật",
  "questionJapanese": "câu hỏi bằng tiếng Nhật nếu cần (null nếu không)",
  "options": [{"text": "...", "isCorrect": true/false}],
  "correctAnswer": "đáp án đúng",
  "hint": "gợi ý ngắn (null nếu không có)",
  "explanation": "giải thích bằng tiếng Việt",
  "refContentId": "uuid-của-vocab-hoặc-grammar-hoặc-null",
  "refContentType": "VOCABULARY hoặc GRAMMAR hoặc null"
}
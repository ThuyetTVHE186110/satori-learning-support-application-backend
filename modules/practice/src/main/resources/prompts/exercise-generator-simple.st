Tạo {count} câu hỏi tiếng Nhật cấp độ {jlptLevel} từ nội dung sau.

{contentSummary}

{dimensionHint}

{itemTypeInstruction}

Trả về {"items": [...]}. Cấu trúc mỗi phần tử:
- "itemType"       : tên dạng câu hỏi — PHẢI khớp đúng dạng được yêu cầu
- "question"       : câu hỏi bằng tiếng Việt hoặc tiếng Nhật
- "questionJapanese": câu tiếng Nhật nếu cần, ngược lại null
- "options"        : array object {"text":"...","isCorrect":true/false}, hoặc [] nếu không có
- "correctAnswer"  : đáp án đúng dạng string
- "hint"           : gợi ý ngắn, null nếu không có
- "explanation"    : giải thích tại sao đúng, bằng tiếng Việt
- "refContentId"   : shortId của từ/ngữ pháp đang hỏi (từ [ID:...] trong content)
- "refContentType" : "VOCABULARY" hoặc "GRAMMAR"

Tạo {count} câu hỏi tiếng Nhật cấp độ {jlptLevel} từ nội dung sau.

{contentSummary}

{dimensionHint}

{itemTypeInstruction}

RÀNG BUỘC SỐ LƯỢNG OPTIONS (BẮT BUỘC):
- MULTIPLE_CHOICE / FILL_BLANK / TRANSLATION : options = ĐÚNG 4 object, 1 đúng 3 sai.
- TRUE_FALSE                                  : options = ĐÚNG 2 [{"text":"Đúng",...},{"text":"Sai",...}].
- SENTENCE_ORDER                              : options = 4-7 token tiếng Nhật (mỗi token là trợ từ / 1 từ / 1 động từ đã chia),
                                                 KHÔNG phải cả câu, KHÔNG gộp "本は"; tất cả isCorrect=false.
- MATCHING                                    : options = []; dùng "pairs" = 4 cặp {"left":"<JP>","right":"<VN>"}.

LÀM SẠCH DỮ LIỆU: KHÔNG để ký tự "~" / "..." xuất hiện trong BẤT KỲ field nào.
Nếu vocab nguồn có "X ~" → thay bằng danh từ cụ thể (VD: "この ~" → "この本").

Trả về {"items": [...]}. Cấu trúc mỗi phần tử:
- "itemType"       : tên dạng câu hỏi — PHẢI khớp đúng dạng được yêu cầu
- "question"       : câu hỏi bằng tiếng Việt hoặc tiếng Nhật, KHÔNG rỗng
- "questionJapanese": câu tiếng Nhật thuần (kanji + kana, không brackets/furigana) nếu cần, ngược lại null. Server tự sinh furigana từ trường này.
- "options"        : xem ràng buộc số lượng bên trên; key BẮT BUỘC là "text" (KHÔNG "token"/"word")
- "pairs"          : chỉ MATCHING, null cho dạng khác
- "correctAnswer"  : đáp án đúng dạng string (null nếu MATCHING)
- "hint"           : gợi ý ngắn, null nếu không có
- "explanation"    : giải thích tại sao đúng, bằng tiếng Việt
- "refContentId"   : shortId của từ/ngữ pháp đang hỏi (từ [ID:...] trong content)
- "refContentType" : "VOCABULARY" hoặc "GRAMMAR"

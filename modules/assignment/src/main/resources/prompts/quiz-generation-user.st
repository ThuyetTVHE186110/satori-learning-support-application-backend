{context}

═══════════════════════════════════════════════════════════════
GENERATION PARAMETERS
═══════════════════════════════════════════════════════════════

Teacher's instruction: {teacherPrompt}
Question count: {questionCount}
Question types: {questionTypes}
JLPT level: {jlptLevel}

CRITICAL REMINDERS:
1. **Teach, don't just test** — Each question must have pedagogical value beyond memorization
2. **Adaptive diversity** — IF teacher specifies question type/difficulty distribution, follow it EXACTLY (override baseline requirements). OTHERWISE, use default diversity (see system prompt)
3. **Plausible distractors** — Wrong options should reflect ACTUAL student mistakes, not be obviously wrong
4. **Excellent explanations** — 3-5 sentences, explain why answer is correct AND why others are wrong
5. **Strict format compliance** — Use [Kanji]{lb}Reading{rb} for ALL kanji, base form answers (no suffixes), plain text explanations
6. **MANDATORY Vietnamese instructions** — EVERY questionText MUST START with a Vietnamese instruction line (Hãy chọn..., Điền từ..., Chọn ý nghĩa..., Hoàn thành...)
7. **PREVENT answer leakage** — NEVER put any option content/words in questionText. Check every word in options array against questionText before generating

═══════════════════════════════════════════════════════════════
BRIEF EXAMPLES (See system prompt for detailed rules)
═══════════════════════════════════════════════════════════════

IMPORTANT: All examples below show CORRECT format with Vietnamese instructions

EXAMPLE 1: Multiple-choice (CORRECT - Vietnamese instruction, no answer leakage)
{lb}"questionType": "multiple_choice", "questionText": "Hãy chọn đáp án đúng: [私]{lb}わたし{rb}は[毎日]{lb}まいにち{rb}[日本語]{lb}にほんご{rb}を[勉強]{lb}べんきょう{rb}します。[昨日]{lb}きのう{rb}は何時間[勉強]{lb}べんきょう{rb}しましたか？", "options": {lb}"type": "multiple_choice", "shuffle": true, "options": [{lb}"id": "A", "text": "1[時間]{lb}じかん{rb}", "text_reading": "いちじかん"{rb}, {lb}"id": "B", "text": "2[時間]{lb}じかん{rb}", "text_reading": "にじかん"{rb}, {lb}"id": "C", "text": "3[時間]{lb}じかん{rb}", "text_reading": "さんじかん"{rb}, {lb}"id": "D", "text": "4[時間]{lb}じかん{rb}", "text_reading": "よじかん"{rb}]{rb}, "correctAnswer": "B", "explanation": "Người nói [勉強します] mỗi ngày và hỏi hôm qua học bao nhiêu giờ. 2 giờ là thời gian hợp lý. Học sinh thường nhầm vì tất cả các đáp án đều là số giờ khác nhau. Chú ý [昨日] = ngày hôm qua (quá khứ)."{rb}
✓ Check: "Hãy chọn đáp án đúng:" begins question
✓ Check: No option words ([時間]) appear in questionText before options array!

EXAMPLE 2: Fill-blank (CORRECT - Vietnamese instruction, context clues only)
{lb}"questionType": "fill_blank", "questionText": "Điền từ thích hợp: [朝]{lb}あさ{rb}、わたしは____で[新聞]{lb}しんぶん{rb}を[読]{lb}よ{rb}みます。", "options": {lb}"type": "multiple_choice", "shuffle": true, "options": [{lb}"id": "A", "text": "[公園]{lb}こうえん{rb}", "text_reading": "こうえん"{rb}, {lb}"id": "B", "text": "[喫茶店]{lb}きっさてん{rb}", "text_reading": "きっさてん"{rb}, {lb}"id": "C", "text": "[図書館]{lb}としょかん{rb}", "text_reading": "としょかん"{rb}, {lb}"id": "D", "text": "[駅]{lb}えき{rb}", "text_reading": "えき"{rb}]{rb}, "correctAnswer": "B", "explanation": "Kết hợp 'mỗi sáng' + 'đọc báo' → quán cà phê là nơi tự nhiên nhất. Các tùy chọn khác: công viên quá lạnh sáng sớm, thư viện có quy tắc yên tĩnh không thích hợp, ga tàu quá ồn ào. Câu hỏi kiểm tra kiến thức văn hóa đời sống người Nhật."{rb}
✓ Check: "Điền từ thích hợp:" begins question
✓ Check: No location words from options appear in questionText!

EXAMPLE 3: Definition question (CORRECT - Vietnamese instruction, clear meaning)
{lb}"questionType": "multiple_choice", "questionText": "Chọn ý nghĩa đúng: [学生]{lb}がくせい{rb}の意味は何ですか？", "options": {lb}"type": "multiple_choice", "shuffle": true, "options": [{lb}"id": "A", "text": "[人]{lb}ひと{rb}が[学校]{lb}がっこう{rb}で[勉強]{lb}べんきょう{rb}する", "text_reading": "ひとががっこうでべんきょうする"{rb}, {lb}"id": "B", "text": "[人]{lb}ひと{rb}が[会社]{lb}かいしゃ{rb}で[仕事]{lb}しごと{rb}する", "text_reading": "ひとがかいしゃでしごとする"{rb}, {lb}"id": "C", "text": "[人]{lb}ひと{rb}が[病院]{lb}びょういん{rb}で[診療]{lb}しんりょう{rb}する", "text_reading": "ひとがびょういんでしんりょうする"{rb}, {lb}"id": "D", "text": "[人]{lb}ひと{rb}が[会社]{lb}かいしゃ{rb}で[経営]{lb}けいえい{rb}する", "text_reading": "ひとがかいしゃでけいえいする"{rb}]{rb}, "correctAnswer": "A", "explanation": "Từ [学生]{lb}がくせい{rb} có nghĩa là 'một người học tập tại trường'. Đáp án B là 'làm việc tại công ty', C là 'chuẩn đoán tại bệnh viện', D là 'quản lý công ty'. Các từ này đều được nhầm lẫn vì chúng cũng chỉ các vai trò xã hội khác nhau."{rb}
✓ Check: "Chọn ý nghĩa đúng:" begins question
✓ Check: Definition request is clear!

EXAMPLE 4: Context-completion (CORRECT - Vietnamese instruction, natural dialogue)
{lb}"questionType": "fill_blank", "questionText": "Hoàn thành cuộc hội thoại: A: [明日]{lb}あした{rb}、[天気]{lb}てんき{rb}はどうですか？ B: [雨]{lb}あめ{rb}が[降]{lb}ふ{rb}ると[思]{lb}おも{rb}います。A: そうですか。じゃあ、____を[持]{lb}も{rb}ってきましょう。", "options": {lb}"type": "multiple_choice", "shuffle": true, "options": [{lb}"id": "A", "text": "[傘]{lb}かさ{rb}", "text_reading": "かさ"{rb}, {lb}"id": "B", "text": "[帽子]{lb}ぼうし{rb}", "text_reading": "ぼうし"{rb}, {lb}"id": "C", "text": "[サングラス]{lb}さんぐらす{rb}", "text_reading": "さんぐらす"{rb}, {lb}"id": "D", "text": "[ジャケット]{lb}じゃけっと{rb}", "text_reading": "じゃけっと"{rb}]{rb}, "correctAnswer": "A", "explanation": "Cuộc đối thoại cho biết 'có khả năng mưa', nên nên mang ô ([傘]). Ô là lựa chọn duy nhất hợp lý cho trời mưa. Các tùy chọn khác (mũ, kính mặt trời, áo khoác) không liên quan trực tiếp đến mưa. Câu hỏi kiểm tra khả năng suy luận lôgic và từ vựng thực tế."{rb}
✓ Check: "Hoàn thành cuộc hội thoại:" begins question
✓ Check: No option words ([傘], [帽子], etc.) appear in dialogue!

═══════════════════════════════════════════════════════════════
KEY REQUIREMENTS (See system prompt for detailed rules)
═══════════════════════════════════════════════════════════════

**MANDATORY Vietnamese Instructions**:
- EVERY questionText MUST BEGIN with Vietnamese instruction matching question type:
  - Multiple-choice: "Hãy chọn đáp án đúng:", "Chọn từ/cụm từ thích hợp:", "Chọn câu trả lời phù hợp:", or "Đáp án nào đúng:"
  - Fill-blank: "Điền từ thích hợp:", "Hoàn thành câu:", "Chọn từ điền vào chỗ trống:", or "Điền từ để hoàn thành đoạn hội thoại:"
  - Definition: "Chọn ý nghĩa đúng:", "Từ _____ có ý nghĩa gì?", "Chọn định nghĩa đúng:", or "Từ nào là từ đồng nghĩa:"
  - Context: "Hoàn thành cuộc hội thoại:", "Chọn câu trả lời phù hợp:", "Điền từ để hoàn thành đoạn hội thoại:", or "Câu tiếp theo nên nói gì:"
- Example: ❌ WRONG: "わたしは..." (missing Vietnamese instruction)
- Example: ✓ CORRECT: "Hãy chọn đáp án đúng: わたしは..."

**PREVENT Answer Leakage**:
- NEVER include any option words/content in questionText
- Before generating: Cross-check every word in options array against questionText
- ❌ WRONG: questionText has "[傘]{lb}かさ{rb}" and options has "[傘]{lb}かさ{rb}" as correct answer (reveals answer!)
- ❌ WRONG: questionText mentions "2[時間]{lb}じかん{rb}" and options has "2[時間]{lb}じかん{rb}" as choice
- ✓ CORRECT: questionText uses context clues; options contain DIFFERENT content not mentioned before
- RULE: If "[学校]{lb}がっこう{rb}" appears in options, it MUST NOT appear in questionText

**Format**:
- EVERY kanji MUST have furigana: [Kanji]{lb}Reading{rb}
- Answer options are BASE FORM ONLY: NO suffixes like ですか, です, ました
- Explanations are PLAIN TEXT (no formatting, no furigana, 3-5 Vietnamese sentences)
- correctAnswer = "A" (ID only, not the full text)

**Quality & Diversity**:
- IF teacher specifies question type/difficulty distribution → follow it EXACTLY
- OTHERWISE → use baseline: Mix types, vary vocabulary, 20% Easy / 40% Medium / 40% Hard
- Contexts: Prioritize teacher's specified contexts; use others as secondary variety
- 3-5 sentence explanations explaining BOTH why correct AND why others are wrong

**Strict rules** (non-negotiable):
- NO HTML tags (absolutely no <>, <ruby>, etc.)
- Fill-blank: EXACTLY ONE ____ per question
- MC: NO ____ in question text (complete sentences only)
- ALL options must be grammatically/semantically plausible
- NO repeated vocabulary across questions in same batch
- Verify Vietnamese instruction line exists at start of EVERY questionText

{formatInstruction}

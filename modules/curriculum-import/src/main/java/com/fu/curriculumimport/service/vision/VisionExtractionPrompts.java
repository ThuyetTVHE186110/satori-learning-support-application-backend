package com.fu.curriculumimport.service.vision;

/**
 * System and user prompt templates for vision-based extraction (Tier 2).
 *
 * <p>The vision path sends rendered PDF page images directly to GPT-4o Vision
 * instead of relying on OCR text — preserving table layouts, furigana positions,
 * and multi-column formatting that text extraction typically destroys.
 *
 * <p>All members are package-private static constants.
 */
public final class VisionExtractionPrompts {

    private VisionExtractionPrompts() {}

    // ── Vocabulary ─────────────────────────────────────────────────────────────

    public static final String VOCABULARY_SYSTEM_PROMPT = """
            You are an expert Japanese vocabulary extractor for a language-learning platform.
            You receive one or more scanned pages from a Japanese textbook (Minna no Nihongo or similar).

            ═══ YOUR TASK ═══
            Visually read the page image(s) and extract ALL vocabulary items, preserving the exact layout.
            Tables often have columns: No. | Japanese word | Reading (hiragana) | Vietnamese meaning | Part of speech.
            Some pages show a country/language reference table in a special 4-column format.

            ═══ INLINE MEANING PATTERN ═══
            When you see: 行きます（đi）
            - japaneseText = "行きます"  (NO brackets, NO meaning inside)
            - meaningVi    = "đi"
            - meaningSource = "SOURCE_INLINE"

            When （） contains only kana → it is furigana, NOT a meaning:
              先生（せんせい）  → japaneseText="先生", reading="せんせい"

            ═══ COUNTRY / LANGUAGE TABLE PATTERN ═══
            4-column layout: country name | nationality (〜人) | empty | language (〜語)
            • Col 0 (country) and Col 3 (language) are SEPARATE vocabulary items — extract BOTH.
            • NEVER use the language name as the meaning of the country name.
            • If Col 1 (nationality) is non-empty, extract it as a THIRD item.

            ═══ RULES ═══
            • Extract EVERY vocabulary item visible in the image — do not skip any.
            • Every item MUST have both meaningVi (Vietnamese) AND meaningEn (English).
            • Every item MUST have a reading in hiragana.
            • Use "meaningSource": "SOURCE" when the meaning is visibly shown in the table.
            • Use "meaningSource": "SOURCE_INLINE" when meaning appears in （）after the word.
            • Use "meaningSource": "AI_INFERRED" when no meaning is visible — then infer
              an accurate meaning from the lesson context and JLPT level.
            • NEVER leave meanings blank or write "unknown" / "N/A".
            • Part of speech must be precise: noun / verb / i-adjective / na-adjective /
              adverb / particle / conjunction / interjection / counter / expression.
            • Set "exampleSentences": [] (empty array) for all items — do NOT generate examples.
              This keeps the output concise and prevents token limit issues.

            Respond ONLY with valid JSON — no markdown, no explanations.
            """;

    public static final String VOCABULARY_USER_TEMPLATE = """
            Extract ALL vocabulary from the textbook page image(s) below.

            Lesson: %s
            JLPT Level: %s

            Return this exact JSON structure (omit exampleSentences to keep output concise):
            {
              "vocabulary": [
                {
                  "japaneseText": "待合室",
                  "reading": "まちあいしつ",
                  "romaji": "machiaishitsu",
                  "meaningVi": "Phòng chờ",
                  "meaningEn": "Waiting room",
                  "partOfSpeech": "noun",
                  "jlptLevel": "%s",
                  "kanjiWriting": "待合室",
                  "exampleSentences": [],
                  "isKeyWord": true,
                  "meaningSource": "SOURCE"
                }
              ],
              "confidence": 0.92
            }
            """;

    // ── Grammar ────────────────────────────────────────────────────────────────

    public static final String GRAMMAR_SYSTEM_PROMPT = """
            Bạn là chuyên gia phân tích ngữ pháp tiếng Nhật cho nền tảng học ngôn ngữ.
            Bạn nhận ảnh chụp trang sách "Minna no Nihongo I — Bản dịch và Giải thích Ngữ pháp"
            (bản dịch tiếng Việt). Nhiệm vụ: trích xuất TỪNG ĐIỂM ngữ pháp với giải thích ĐẦY ĐỦ.

            ═══ CẤU TRÚC SÁCH MINNA NO NIHONGO ═══
            Phần IV (Giải thích ngữ pháp) có dạng:
              1) Trợ từ は — giải thích chi tiết... [chú ý]... ① câu ví dụ
              2) Câu phủ định じゃ ありません — ...
              3) Câu nghi vấn か — ...
            Mỗi mục đánh số 1), 2), 3)... là MỘT ĐIỂM NGỮ PHÁP RIÊNG BIỆT.

            ═══ QUY TẮC SAO CHÉP NGUYÊN VĂN (CRITICAL) ═══
            Trường "detailedExplanationVi" phải SAO CHÉP NGUYÊN VĂN TIẾNG VIỆT từ sách:
            • ĐỌC KỸ toàn bộ phần giải thích tiếng Việt trong ảnh cho mỗi mục ngữ pháp.
            • SAO CHÉP đầy đủ mọi câu giải thích — KHÔNG tóm tắt, KHÔNG diễn giải lại,
              KHÔNG tự sinh câu mới, KHÔNG dịch sang tiếng Anh.
            • Nếu sách giải thích thành phần phụ (ví dụ: giải thích riêng về「です」bên trong
              mục "Danh từ は Danh từ です") → SAO CHÉP phần đó và NỐI TIẾP vào detailedExplanationVi.
            • Kết quả phải chứa ĐẦY ĐỦ mọi câu giải thích trong mục đó từ sách.
            • Giữ nguyên dấu ngoặc vuông, ký hiệu ①②③, và ký hiệu [chú ý] / ※.

            ═══ QUY TẮC CHỐNG TÁCH SAI (QUAN TRỌNG NHẤT) ═══
            Mỗi mục đánh số (1, 2, 3...) trong sách = MỘT grammar point DUY NHẤT.
            TUYỆT ĐỐI KHÔNG tách thành phần phụ bên trong một mục thành grammar point riêng.

            ❌ SAI (LỖI THƯỜNG GẶP — PHẢI TRÁNH):
              Input sách: "1. Danh từ は Danh từ です" (giải thích は... giải thích です...)
              Output sai:  4 grammar points → "は", "～です", "～じゃありません", "です"
              Lý do sai:   Tách thành phần phụ (は, です) thành grammar point riêng.

            ✅ ĐÚNG:
              Input sách: "1. Danh từ は Danh từ です" (giải thích は... giải thích です...)
              Output đúng: 1 grammar point → pattern="Danh từ は Danh từ です",
                           detailedExplanationVi chứa TOÀN BỘ giải thích (cả は lẫn です).

            Quy tắc cụ thể:
            • Nếu sách viết "1. Danh từ は Danh từ です" rồi bên dưới giải thích「は」và「です」riêng
              → Đó là MỘT grammar point, detailedExplanationVi = giải thích「は」+ giải thích「です」.
            • Nếu sách viết "2. Danh từ じゃ ありません" → MỘT grammar point, KHÔNG tách.
            • Nếu sách viết "3. Câu か" → MỘT grammar point, KHÔNG tách「か」riêng.
            • Chỉ tạo grammar point MỚI khi thấy SỐ THỨ TỰ MỚI (2., 3., 4...) trong sách.

            ═══ QUY TẮC HẬU TỐ KÍNH NGỮ ═══
            Nếu ~さん, ~くん, ~ちゃん, ~じん, ~ご xuất hiện như mục đánh số trong phần Ngữ pháp
            → trích xuất thành grammar point với category = HONORIFIC.

            ═══ CÁC TRƯỜNG CẦN TRÍCH XUẤT ═══
            1. stt: Số thứ tự trong phần ngữ pháp (1, 2, 3...).
            2. grammarPoint: Tên cụ thể, ví dụ "Trợ từ は (wa)", "Câu phủ định: じゃ ありません".
            3. pattern: Mẫu cú pháp trừu tượng, ví dụ "Danh từ は Danh từ です".
            4. function: Chức năng — TRÍCH NGUYÊN VĂN từ sách (tiếng Việt).
            5. detailedExplanationVi: GIẢI THÍCH ĐẦY ĐỦ — SAO CHÉP NGUYÊN VĂN (xem quy tắc trên).
            6. usageNotes: Tất cả [chú ý] / ※ → mỗi chú ý là một phần tử mảng. KHÔNG BỎ QUA.
            7. exampleSentences: Câu ví dụ có ①②③ → trích đúng cặp {"jp", "vi", "en"}.
            8. relatedTo: Nếu có ghi "xem thêm bài X" → ghi vào đây.

            Category: VERB_FORM | PARTICLE | ADJECTIVE | CONJUGATION | SENTENCE_PATTERN |
                      HONORIFIC | CONDITIONAL | PASSIVE | CAUSATIVE | OTHER

            Chỉ trả về JSON hợp lệ — không markdown, không giải thích.
            """;

    public static final String GRAMMAR_USER_TEMPLATE = """
            Trích xuất TỪNG ĐIỂM ngữ pháp từ ảnh trang sách bên dưới.
            SAO CHÉP NGUYÊN VĂN phần giải thích tiếng Việt — KHÔNG tóm tắt, KHÔNG tự viết lại.

            Bài: %s
            Cấp JLPT: %s

            Trả về JSON:
            {
              "grammarPoints": [
                {
                  "stt": 1,
                  "grammarPoint": "Trợ từ は (wa)",
                  "pattern": "Danh từ は Danh từ です",
                  "patternReading": "だいめいし は だいめいし です",
                  "function": "Biểu thị danh từ đứng trước nó là chủ đề của câu",
                  "meaningVi": "Trợ từ chủ đề — đánh dấu chủ đề của câu",
                  "meaningEn": "Topic marker particle",
                  "shortExplanationVi": "Đặt「は」sau chủ đề muốn nói đến, rồi thêm thông tin cần thiết",
                  "detailedExplanationVi": "Trợ từ「は」biểu thị rằng danh từ đứng trước nó là chủ đề của câu. Người nói đặt「は」trước chủ đề mà mình muốn nói đến và lập thành câu bằng cách thêm vào sau「は」những thông tin cần thiết. ①わたしは マイク・ミラーです。 Tôi là Mike Miller. 「です」được đặt sau danh từ làm vị ngữ để biểu thị sự phán đoán hay khẳng định.「です」biểu thị sự lịch sự của người nói đối với người nghe.「です」thay đổi hình thức trong trường hợp của câu phủ định (xem thêm bài 2) hoặc thời quá khứ (xem thêm bài 12). ②わたしは エンジニアです。 Tôi là kỹ sư.",
                  "usageNotes": ["「は」đọc là「わ」"],
                  "relatedTo": ["xem thêm bài 2", "xem thêm bài 12"],
                  "jlptLevel": "%s",
                  "category": "PARTICLE",
                  "exampleSentences": [
                    {
                      "jp": "わたしは マイク・ミラーです。",
                      "vi": "Tôi là Mike Miller.",
                      "en": "I am Mike Miller."
                    },
                    {
                      "jp": "わたしは エンジニアです。",
                      "vi": "Tôi là kỹ sư.",
                      "en": "I am an engineer."
                    }
                  ],
                  "commonMistakes": [],
                  "isKeyPoint": true
                }
              ],
              "confidence": 0.0
            }

            LƯU Ý QUAN TRỌNG:
            • detailedExplanationVi phải COPY NGUYÊN VĂN từ ảnh — bao gồm TẤT CẢ câu giải thích,
              ví dụ minh họa, và chú ý trong mỗi mục. Ví dụ trên là mẫu thực tế từ sách.
            • usageNotes: mỗi [chú ý] / ※ trong ảnh → một phần tử mảng. KHÔNG bỏ qua.
            • Nếu thấy "xem thêm bài X" → ghi vào relatedTo.
            """;
}

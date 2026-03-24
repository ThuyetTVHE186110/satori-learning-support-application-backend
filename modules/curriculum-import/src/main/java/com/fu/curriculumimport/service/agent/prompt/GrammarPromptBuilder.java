package com.fu.curriculumimport.service.agent.prompt;

import com.fu.common.ai.PromptFragment;
import com.fu.curriculumimport.service.embedding.RagContext;
import com.fu.curriculumimport.service.ingestion.DocumentChunk;
import com.fu.curriculumimport.service.ingestion.MnNSectionSplitter;
import com.fu.curriculumimport.service.ingestion.OcrTextPreprocessor;

/**
 * Builds system and user prompts for {@code GrammarExtractionAgent}.
 *
 * <p>Extracted from the agent so the class containing LLM-call logic stays concise.
 * All members are package-private static — no Spring dependency, fully testable without
 * application context.
 */
public final class GrammarPromptBuilder {

    private GrammarPromptBuilder() {}

    // ── System prompt ─────────────────────────────────────────────────────────

    public static final String SYSTEM_PROMPT =
            """
            Bạn là chuyên gia phân tích ngữ pháp tiếng Nhật cho nền tảng học ngôn ngữ.
            Nguồn tài liệu chính: sách "Minna no Nihongo I — Bản dịch và Giải thích Ngữ pháp (N5)"
            (bản dịch tiếng Việt). Nhiệm vụ: trích xuất TỪNG ĐIỂM ngữ pháp từ nội dung giáo trình.

            ═══ CẤU TRÚC SÁCH MINNA NO NIHONGO ═══
            Phần IV (Giải thích ngữ pháp) có dạng:
              1) Trợ từ は — giải thích...  [chú ý] ...  ® câu ví dụ
              2) Câu phủ định じゃ ありません — ...
              3) Câu nghi vấn か — ...
            Mỗi mục đánh số 1), 2), 3)... là MỘT ĐIỂM NGỮ PHÁP RIÊNG BIỆT.

            ═══ QUY TẮC CHỐNG TÁCH SAI (QUAN TRỌNG NHẤT — ĐỌC KỸ) ═══
            Mỗi mục đánh số (1, 2, 3...) trong sách = MỘT grammar point DUY NHẤT.
            TUYỆT ĐỐI KHÔNG tách thành phần phụ bên trong một mục thành grammar point riêng.

            ❌ SAI (LỖI THƯỜNG GẶP — PHẢI TRÁNH):
              Input: "## 1. Danh từ は Danh từ です" + giải thích「は」... + giải thích「です」...
              Output sai:  4 grammar points → "は", "～です", "～じゃありません", "です"
              Lý do sai:   Tách thành phần phụ (は, です) thành grammar point riêng.

            ✅ ĐÚNG:
              Input: "## 1. Danh từ は Danh từ です" + giải thích「は」... + giải thích「です」...
              Output đúng: 1 grammar point → pattern="Danh từ は Danh từ です",
                           detailedExplanationVi chứa TOÀN BỘ giải thích (cả は lẫn です gộp lại).

            Quy tắc cụ thể:
              • Nếu sách viết "1. Danh từ は Danh từ です" rồi bên dưới giải thích「は」và「です」riêng
                → Đó là MỘT grammar point, detailedExplanationVi = giải thích「は」+ giải thích「です」.
              • Nếu sách viết "2. Danh từ じゃ ありません" → MỘT grammar point, KHÔNG tách.
              • Nếu sách viết "3. Câu か" → MỘT grammar point, KHÔNG tách「か」riêng.
              • Chỉ tạo grammar point MỚI khi thấy SỐ THỨ TỰ MỚI (2., 3., 4...) trong sách.
              • Nếu pattern X xuất hiện BÊN TRONG giải thích của Y mà KHÔNG có số thứ tự riêng
                → X là giải thích PHỤ, đưa vào detailedExplanationVi của Y.
              • detailedExplanationVi = [giải thích chính] + [giải thích phụ nguyên văn từ sách]

            ═══ QUY TẮC PATTERN — CHÍNH XÁC NHƯ TIÊU ĐỀ (CRITICAL) ═══
            Trường "pattern" PHẢI KHỚP CHÍNH XÁC với tiêu đề mục trong sách.
            KHÔNG mở rộng, KHÔNG rút gọn, KHÔNG đổi tên.

            ❌ SAI — mở rộng tiêu đề:
              Tiêu đề: "3. Câu か" → LLM viết "Danh từ は Danh từ ですか"
              (SAI! đây là cụ thể hóa, tiêu đề dùng "Câu か" thì pattern = "Câu か")

            ❌ SAI — rút gọn tiêu đề:
              Tiêu đề: "3. Danh từ は Danh từ ですか" → LLM viết "Câu か"
              (SAI! tiêu đề dùng dạng đầy đủ thì pattern phải giữ nguyên dạng đầy đủ)

            ✅ ĐÚNG — sao chép NGUYÊN VĂN tiêu đề:
              Tiêu đề: "3. Câu か" → pattern = "Câu か"
              Tiêu đề: "3. Danh từ は Danh từ ですか" → pattern = "Danh từ は Danh từ ですか"
              Tiêu đề: "4. Danh từ も" → pattern = "Danh từ も"

            QUY TẮC:
              • pattern = tiêu đề mục (bỏ số thứ tự "1.", "2."... ở đầu).
              • KHÔNG thay "Danh từ は Danh từ ですか" bằng "Câu か" hay ngược lại.
              • KHÔNG thêm phần mới vào sau pattern (ví dụ: "Danh từ も" → KHÔNG thêm "です").
              • KHÔNG bỏ bớt thành phần (ví dụ: "Danh từ は Danh từ ですか" → KHÔNG bỏ "Danh từ は Danh từ").

            ═══ QUY TẮC TRÍCH XUẤT ═══
            1. grammarPoint: Tên điểm ngữ pháp cụ thể, ví dụ:
               "Trợ từ は (wa)", "Câu phủ định: Danh từ じゃ ありません", "Câu nghi vấn か"
            2. pattern: Mẫu cú pháp CHÍNH XÁC NHƯ TRONG SÁCH, ví dụ:
               "Danh từ は Danh từ です"  "Danh từ は Danh từ じゃ ありません"  "Câu か"  "Danh từ も"
            3. function: Chức năng ngữ pháp — TRÍCH NGUYÊN VĂN từ sách (tiếng Việt).
               Ví dụ: "Biểu thị danh từ đứng trước nó là chủ đề của câu"
            4. detailedExplanationVi: ĐÂY LÀ TRƯỜNG QUAN TRỌNG NHẤT.
               BẮT BUỘC SAO CHÉP NGUYÊN VĂN 100% TEXT TIẾNG VIỆT TỪ SÁCH.
               KHÔNG ĐƯỢC tóm tắt, diễn giải lại, viết lại bằng từ ngữ khác, hay tự sinh câu mới.
               KHÔNG ĐƯỢC viết dạng "Cấu trúc này được sử dụng để..." — đó là DIỄN GIẢI, KHÔNG phải sao chép.

               ❌ SAI (diễn giải — PHẢI TRÁNH):
                 "Cấu trúc này được sử dụng để giới thiệu bản thân hay một ai đó bằng cách đặt
                  danh từ chủ thể trước trợ từ 'は' và theo sau là danh từ khác với 'です'."
               ✅ ĐÚNG (sao chép nguyên văn từ sách):
                 "Trợ từ [は] biểu thị rằng danh từ đứng trước nó là chủ đề của câu. Người nói
                  đặt [は] trước chủ đề mà mình muốn nói đến và lập thành câu bằng cách thêm
                  vào sau [は] những thông tin cần thiết. [です] được dùng sau danh từ để biểu thị
                  sự phán đoán hay khẳng định ở thể lịch sự."

               QUY TẮC CỤ THỂ:
               • Copy-paste CHÍNH XÁC các câu tiếng Việt như chúng xuất hiện trong input.
               • NẾU mục có giải thích thành phần phụ (ví dụ: giải thích riêng về [です], [じゃ],
                 [ありません]) → NỐI TIẾP tất cả vào detailedExplanationVi, giữ nguyên từng câu.
               • Kết quả phải chứa ĐẦY ĐỦ mọi câu giải thích trong mục đó từ sách.
               • Giữ nguyên dấu ngoặc vuông [は], [です], dấu「」, ký hiệu ①②③, v.v.
            5. usageNotes: Tất cả [chú ý] hoặc ※ trong sách → PHẢI đưa vào đây.
               Mỗi chú ý là một phần tử trong mảng. KHÔNG BỎ QUA.
            6. examples: Câu ví dụ có ký hiệu ® hoặc ○ hoặc ①②③ trong sách → trích đúng cặp Nhật-Việt.
               Mỗi cặp là một object {"jp": "...", "vi": "...", "en": "..."}.
            7. relatedTo: Nếu điểm ngữ pháp có ghi "xem thêm bài X" → ghi vào đây.
            8. stt: Số thứ tự của điểm trong phần ngữ pháp bài đó (1, 2, 3...).

            ═══ QUY TẮC HẬU TỐ KÍNH NGỮ TRONG PHẦN NGỮ PHÁP ═══
            Sách Minna no Nihongo giải thích hậu tố kính ngữ (như ~さん, ~くん, ~ちゃん, ~じん, ~ご)
            trong phần Ngữ pháp (Phần IV) dưới dạng mục đánh số — chúng là GRAMMAR POINT, không phải
            chỉ là từ vựng.
            QUY TẮC:
              • Nếu ~さん (hay bất kỳ hậu tố nào) xuất hiện như một mục đánh số trong phần Ngữ pháp
                → PHẢI trích xuất thành grammar point với category = HONORIFIC.
              • Trường "grammarPoint" dùng tên như "Hậu tố kính ngữ ~さん", "Cách xưng hô ~くん".
              • Trường "pattern" ghi chính xác pattern, ví dụ: "Tên người + さん".
              • KHÔNG bỏ qua các mục này dù chúng trông giống từ vựng.

            ═══ NGHĨA TIẾNG VIỆT ═══
            Đây là sách dịch tiếng Việt — giải thích tiếng Việt luôn có trong nguồn.
            Giữ nguyên 100% phần tiếng Việt như trong sách, kể cả dấu ngoặc và chú thích.
            Đồng thời cung cấp bản dịch/giải thích bằng tiếng Anh (meaningEn).

            Chỉ trả về JSON hợp lệ — không markdown, không giải thích.
            """ + "\n" + PromptFragment.JSON_ONLY;

    /** Suffix appended to the system prompt on re-extraction attempts. */
    public static final String REEXTRACTION_SYSTEM_SUFFIX =
            "\nIMPORTANT: Fix the issues noted in the feedback.";

    // ── Extraction prompt template ─────────────────────────────────────────────

    private static final String EXTRACTION_TEMPLATE = """
            Trích xuất TỪNG ĐIỂM ngữ pháp từ nội dung giáo trình tiếng Nhật sau (trang %d-%d).
            Cấp JLPT mục tiêu: %s

            ─── NỘI DUNG ────────────────────────────────────────────────────────
            %s
            ─────────────────────────────────────────────────────────────────────
            %s

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
                  "shortExplanationVi": "Đặt [は] sau chủ đề muốn nói đến, rồi thêm thông tin cần thiết",
                  "detailedExplanationVi": "Trợ từ [は] biểu thị rằng danh từ đứng trước nó là chủ đề của câu. Người nói đặt [は] trước chủ đề mà mình muốn nói đến và lập thành câu bằng cách thêm vào sau [は] những thông tin cần thiết. [です] được dùng sau danh từ để biểu thị sự phán đoán hay khẳng định ở thể lịch sự.",
                  "usageNotes": ["[は] đọc là [わ]"],
                  "relatedTo": [],
                  "jlptLevel": "%s",
                  "category": "PARTICLE",
                  "exampleSentences": [
                    {
                      "jp": "わたしは マイク・ミラーです。",
                      "vi": "Tôi là Mike Miller.",
                      "en": "I am Mike Miller."
                    }
                  ],
                  "commonMistakes": [],
                  "isKeyPoint": true
                },
                {
                  "stt": 2,
                  "grammarPoint": "Câu phủ định: Danh từ じゃ ありません",
                  "pattern": "Danh từ は Danh từ じゃ ありません",
                  "patternReading": "だいめいし は だいめいし じゃ ありません",
                  "function": "Thể phủ định của [です], dùng trong giao tiếp hằng ngày",
                  "meaningVi": "Không phải là...",
                  "meaningEn": "Negative copula (informal speech)",
                  "shortExplanationVi": "[じゃ ありません] là thể phủ định của [です] trong hội thoại",
                  "detailedExplanationVi": "[じゃ ありません] là thể phủ định của [です], và được dùng trong giao tiếp hằng ngày. [では ありません] được dùng trong văn viết hoặc các bài phát biểu trang trọng.",
                  "usageNotes": ["[じゃ] đọc là [ちゃ]"],
                  "relatedTo": [],
                  "jlptLevel": "%s",
                  "category": "SENTENCE_PATTERN",
                  "exampleSentences": [
                    {
                      "jp": "サントスさんは 学生じゃ ありません。",
                      "vi": "Anh Santos không phải là sinh viên.",
                      "en": "Mr. Santos is not a student."
                    }
                  ],
                  "commonMistakes": [],
                  "isKeyPoint": true
                },
                {
                  "stt": 4,
                  "grammarPoint": "Hậu tố kính ngữ ~さん",
                  "pattern": "Tên người + さん",
                  "patternReading": "〜さん",
                  "function": "Thêm vào sau tên người để thể hiện sự tôn trọng",
                  "meaningVi": "hậu tố kính ngữ thêm sau tên",
                  "meaningEn": "honorific suffix added after a person's name",
                  "shortExplanationVi": "Thêm [さん] sau tên để gọi người khác một cách lịch sự",
                  "detailedExplanationVi": "[さん] là hậu tố kính ngữ thêm vào sau tên của người khác để thể hiện sự tôn trọng. Không dùng [さん] khi nói về bản thân.",
                  "usageNotes": ["Không dùng để tự gọi bản thân", "[さん] có thể thêm vào sau tên hoặc họ"],
                  "relatedTo": [],
                  "jlptLevel": "%s",
                  "category": "HONORIFIC",
                  "exampleSentences": [
                    {
                      "jp": "ミラーさんは アメリカ人です。",
                      "vi": "Anh Miller là người Mỹ.",
                      "en": "Mr. Miller is American."
                    }
                  ],
                  "commonMistakes": [],
                  "isKeyPoint": true
                }
              ],
              "confidence": 0.0
            }

            Danh mục ngữ pháp: VERB_FORM, PARTICLE, ADJECTIVE, CONJUGATION,
            SENTENCE_PATTERN, HONORIFIC, CONDITIONAL, PASSIVE, CAUSATIVE, OTHER
            """;

    // ── Single-section extraction template (per-heading extraction) ────────────

    /**
     * System prompt for single-section extraction.
     * Simpler than the bulk prompt because anti-split is structurally enforced
     * (one heading = one LLM call = one grammar point).
     */
    public static final String SINGLE_SECTION_SYSTEM_PROMPT = """
            Bạn là chuyên gia phân tích ngữ pháp tiếng Nhật cho nền tảng học ngôn ngữ.
            Nguồn: sách "Minna no Nihongo I — Bản dịch và Giải thích Ngữ pháp" (bản tiếng Việt).

            Bạn sẽ nhận MỘT MỤC ngữ pháp duy nhất (đã được tách sẵn theo số thứ tự).
            Nhiệm vụ: trích xuất CHÍNH XÁC MỘT grammar point từ nội dung này.

            ═══ QUY TẮC PATTERN — CHÍNH XÁC NHƯ TIÊU ĐỀ (CRITICAL) ═══
            Trường "pattern" PHẢI KHỚP CHÍNH XÁC với tiêu đề mục được cung cấp.
            KHÔNG mở rộng, KHÔNG rút gọn, KHÔNG đổi tên.
            • pattern = tiêu đề mục (bỏ số thứ tự ở đầu), sao chép NGUYÊN VĂN.
            • Tiêu đề "Câu か" → pattern = "Câu か", KHÔNG viết "Danh từ は Danh từ ですか".
            • Tiêu đề "Danh từ は Danh từ ですか" → pattern = "Danh từ は Danh từ ですか", KHÔNG rút gọn thành "Câu か".
            • Tiêu đề "Danh từ も" → pattern = "Danh từ も", KHÔNG thêm phần sau.
            • KHÔNG tạo dạng mở rộng hay rút gọn của tiêu đề.

            ═══ QUY TẮC SAO CHÉP NGUYÊN VĂN (CRITICAL — ĐỌC RẤT KỸ) ═══
            • detailedExplanationVi: SAO CHÉP NGUYÊN VĂN 100% giải thích tiếng Việt từ input.
              KHÔNG tóm tắt, KHÔNG diễn giải lại, KHÔNG viết lại bằng từ ngữ khác.
              KHÔNG viết dạng "Cấu trúc này được sử dụng để..." — đó là DIỄN GIẢI.
              PHẢI copy-paste CHÍNH XÁC các câu tiếng Việt như chúng xuất hiện trong NỘI DUNG MỤC.
            • Nếu mục có giải thích thành phần phụ (ví dụ: giải thích「は」rồi「です」)
              → NỐI TIẾP tất cả vào detailedExplanationVi, vì đây là MỘT mục duy nhất.
            • usageNotes: mỗi [chú ý] / ※ → một phần tử mảng. KHÔNG BỎ QUA.
            • exampleSentences: câu ví dụ ①②③ → trích đúng cặp {"jp", "vi", "en"}.
            • relatedTo: "xem thêm bài X" → ghi vào đây.
            • Giữ nguyên dấu ngoặc vuông [は], [です], dấu「」, ký hiệu, và chú thích.

            ═══ CÁC TRƯỜNG BẮT BUỘC — KHÔNG ĐƯỢC ĐỂ NULL ═══
            • meaningVi: nghĩa tiếng Việt ngắn gọn (ví dụ: "Trợ từ chủ đề — đánh dấu chủ đề của câu")
            • meaningEn: nghĩa tiếng Anh ngắn gọn (ví dụ: "Topic marker particle")
            • pattern: mẫu cú pháp trừu tượng
            • detailedExplanationVi: giải thích nguyên văn từ sách

            Chỉ trả về JSON hợp lệ — không markdown, không giải thích.
            """;

    private static final String SINGLE_SECTION_TEMPLATE = """
            Trích xuất CHÍNH XÁC MỘT điểm ngữ pháp từ mục sau.
            Đây là mục số %d trong phần ngữ pháp bài "%s". Cấp JLPT: %s

            ─── NỘI DUNG MỤC ───────────────────────────────────────────────────
            %s
            ─────────────────────────────────────────────────────────────────────
            %s

            Trả về JSON (CHỈ MỘT grammar point — KHÔNG tách thành nhiều):
            {
              "grammarPoints": [
                {
                  "stt": %d,
                  "grammarPoint": "Trợ từ は (wa)",
                  "pattern": "Danh từ は Danh từ です",
                  "patternReading": "だいめいし は だいめいし です",
                  "function": "Biểu thị danh từ đứng trước nó là chủ đề của câu",
                  "meaningVi": "Trợ từ chủ đề — đánh dấu chủ đề của câu (BẮT BUỘC, KHÔNG ĐƯỢC null)",
                  "meaningEn": "Topic marker particle (REQUIRED, must NOT be null)",
                  "shortExplanationVi": "Đặt「は」sau chủ đề muốn nói đến, rồi thêm thông tin cần thiết",
                  "detailedExplanationVi": "...(SAO CHÉP NGUYÊN VĂN toàn bộ giải thích tiếng Việt trong mục này, kể cả phần giải thích thành phần phụ)...",
                  "usageNotes": ["...(mỗi [chú ý] là 1 phần tử)..."],
                  "relatedTo": [],
                  "jlptLevel": "%s",
                  "category": "SENTENCE_PATTERN",
                  "exampleSentences": [{"jp": "...", "vi": "...", "en": "..."}],
                  "commonMistakes": [],
                  "isKeyPoint": true
                }
              ],
              "confidence": 0.0
            }

            ⚠ CÁC TRƯỜNG BẮT BUỘC (KHÔNG ĐƯỢC để null hoặc trống):
            - meaningVi: nghĩa tiếng Việt ngắn gọn (1-2 câu)
            - meaningEn: nghĩa tiếng Anh ngắn gọn (1-2 câu)
            - detailedExplanationVi: SAO CHÉP NGUYÊN VĂN từ sách
            - pattern: mẫu cú pháp trừu tượng
            """;

    /**
     * Build user prompt for extracting a SINGLE grammar section.
     *
     * @param section            the markdown section content
     * @param stt                sequence number (1, 2, 3...)
     * @param lessonTitle        lesson title for context
     * @param jlptLevel          JLPT level (e.g. "N5")
     * @param fullPageContext    optional full lesson text for cross-reference
     * @param alreadyExtracted  formatted list of patterns already extracted from earlier
     *                           sections in the same chunk (for dedup); empty string if none
     */
    public static String buildSingleSectionPrompt(String section, int stt,
                                                    String lessonTitle, String jlptLevel,
                                                    String fullPageContext,
                                                    String alreadyExtracted) {
        String contextBlock = "";
        if (fullPageContext != null && !fullPageContext.isBlank() && !fullPageContext.equals(section)) {
            String trimmed = OcrTextPreprocessor.truncateToTokenBudget(
                    fullPageContext, OcrTextPreprocessor.MAX_PAGE_CONTEXT_TOKENS);
            contextBlock = String.format(FULL_PAGE_CONTEXT_TEMPLATE, trimmed);
        }

        String dedupBlock = (alreadyExtracted != null) ? alreadyExtracted : "";

        return String.format(SINGLE_SECTION_TEMPLATE,
                stt, lessonTitle != null ? lessonTitle : "unknown", jlptLevel,
                section,
                contextBlock + dedupBlock,
                stt,
                jlptLevel);
    }

    // ── Context block templates ────────────────────────────────────────────────

    private static final String RAG_CONTEXT_TEMPLATE = """

            EXISTING GRAMMAR POINTS (avoid duplicating these):
            %s
            """;

    private static final String FULL_PAGE_CONTEXT_TEMPLATE = """

            ─── FULL LESSON PAGE CONTEXT (adjacent sections on the same page) ───────────
            %s
            ─────────────────────────────────────────────────────────────────────────────
            Tip: Use this to find example sentences or explanations that clarify the
            grammar patterns in the CONTENT block above. Do NOT re-extract vocabulary
            from this context block — only grammar points.
            """;

    // ── Public builder API ────────────────────────────────────────────────────

    /**
     * Build the user prompt for a grammar extraction call.
     *
     * @param chunk           document chunk to extract from
     * @param jlptLevel       target JLPT level string (e.g. "N5")
     * @param ragSection      pre-formatted RAG context block (may be empty)
     * @param feedbackSection validation feedback for re-extraction (may be empty)
     * @return user prompt string ready to pass to the LLM
     */
    public static String buildExtractionPrompt(DocumentChunk chunk, String jlptLevel,
                                               String ragSection, String feedbackSection) {
        String safeContent = OcrTextPreprocessor.truncateToTokenBudget(
                chunk.getContent(), OcrTextPreprocessor.MAX_CONTENT_TOKENS);

        String sectionHint = buildSectionHint(chunk);
        String fullPageSection = buildFullPageContextSection(chunk);
        String contextBlocks = sectionHint + fullPageSection + ragSection + feedbackSection;

        return String.format(EXTRACTION_TEMPLATE,
                chunk.getPageStart(), chunk.getPageEnd(),
                jlptLevel,
                safeContent,
                contextBlocks,
                jlptLevel,   // example 1 jlptLevel
                jlptLevel,   // example 2 jlptLevel
                jlptLevel);  // example 3 jlptLevel
    }

    /**
     * Format a RAG context block for grammar.
     * Returns an empty string when no relevant context is available.
     */
    public static String formatRagContext(RagContext ragContext) {
        if (ragContext == null || !ragContext.hasContext()) return "";
        StringBuilder sb = new StringBuilder();
        for (RagContext.SimilarItem item : ragContext.getSimilarItems()) {
            if ("GRAMMAR".equals(item.getContentType())) {
                sb.append("- ").append(item.getTextContent()).append("\n");
            }
        }
        return sb.isEmpty() ? "" : String.format(RAG_CONTEXT_TEMPLATE, sb);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Inject the full lesson page context when the chunk is a section sub-chunk.
     * Allows the grammar agent to see example sentences from adjacent sections.
     */
    private static String buildFullPageContextSection(DocumentChunk chunk) {
        String fullCtx = chunk.getFullPageContext();
        String content = chunk.getContent();
        if (fullCtx == null || fullCtx.isBlank() || fullCtx.equals(content)) return "";
        String trimmed = OcrTextPreprocessor.truncateToTokenBudget(
                fullCtx, OcrTextPreprocessor.MAX_PAGE_CONTEXT_TOKENS);
        return String.format(FULL_PAGE_CONTEXT_TEMPLATE, trimmed);
    }

    /**
     * Build a structural section hint from the chunk's MnN section type.
     * Guides the LLM on what role this section plays in the lesson.
     */
    private static String buildSectionHint(DocumentChunk chunk) {
        String sectionType = chunk.getSectionType();
        if (sectionType == null || MnNSectionSplitter.UNKNOWN.equals(sectionType)) return "";
        return switch (sectionType) {
            case MnNSectionSplitter.GRAMMAR_PATTERN ->
                    "\n⚠ LOẠI PHẦN: MẪU CÂU (文型 / Mẫu câu — Phần II)\n" +
                    "Phần này liệt kê các mẫu câu cốt lõi của bài. " +
                    "Mỗi mẫu câu thường được đánh số (1. ～は ～です, 2. ～じゃ ありません...). " +
                    "Mỗi mục là một điểm ngữ pháp riêng — trích TOÀN BỘ với cấu trúc, nghĩa và ví dụ. " +
                    "Trường 'grammarPoint' nên dùng tên cụ thể như 'Mẫu câu N: Danh từ は Danh từ です'.\n";
            case MnNSectionSplitter.GRAMMAR_EXPLANATION ->
                    "\n⚠ LOẠI PHẦN: GIẢI THÍCH NGỮ PHÁP (文法 / Ngữ pháp — Phần IV)\n" +
                    "Phần này giải thích chi tiết từng điểm ngữ pháp, được đánh số 1., 2., 3.... " +
                    "Mỗi số là MỘT điểm ngữ pháp riêng biệt — KHÔNG tách thành phần phụ ra. " +
                    "Ví dụ: mục '1. Danh từ は Danh từ です' chứa giải thích「は」và「です」" +
                    "→ đó vẫn là MỘT grammar point duy nhất, gộp hết vào detailedExplanationVi. " +
                    "Ký hiệu [chú ý] hoặc ※ → đưa vào mảng 'usageNotes', KHÔNG BỎ QUA. " +
                    "Câu ví dụ có ký hiệu ①②③ hoặc ® → trích đúng cặp Nhật-Việt vào 'exampleSentences'. " +
                    "Giải thích phải SAO CHÉP NGUYÊN VĂN tiếng Việt từ sách, KHÔNG tóm tắt, KHÔNG tự viết.\n";
            case MnNSectionSplitter.EXAMPLE ->
                    "\n⚠ LOẠI PHẦN: CÂU VÍ DỤ (例文 / Ví dụ — Phần II)\n" +
                    "Phần này minh họa mẫu ngữ pháp qua câu ví dụ (dạng hỏi-đáp). " +
                    "Xác định mẫu ngữ pháp mà mỗi ví dụ minh họa và đưa vào 'exampleSentences' " +
                    "của điểm ngữ pháp tương ứng.\n";
            default -> "";
        };
    }
}

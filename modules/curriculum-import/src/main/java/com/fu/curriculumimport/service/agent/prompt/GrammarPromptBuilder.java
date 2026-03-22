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

            ═══ QUY TẮC CHỐNG TÁCH SAI (CRITICAL) ═══
            KHÔNG được tách các thành phần phụ của một mẫu câu thành grammar point riêng.

            Ví dụ SAI — phải tránh:
              • Mẫu "Danh từ は Danh từ です" → KHÔNG tách "〜です" ra thành grammar point riêng.
              • Mẫu "Danh từ じゃ ありません" → KHÔNG tách "〜じゃ ありません" hay "ありません" riêng.
              • Mẫu "〜ますか" → KHÔNG tách "〜か" riêng.

            Cách nhận biết ĐÚNG:
              ✔ Grammar point = một mục được đánh số 1), 2), 3)... trong phần ngữ pháp.
              ✔ Nếu sách giải thích "です là..." BÊN TRONG phần giải thích của "Danh từ は Danh từ です",
                thì đó là giải thích PHỤ, KHÔNG phải grammar point mới.
              ✔ Phần giải thích phụ đó phải được đưa vào trường detailedExplanationVi của grammar point CHA.

            Quy tắc cụ thể:
              • Nếu một pattern X xuất hiện như một phần trong pattern lớn hơn Y đã có trong cùng bài,
                và X không có số thứ tự độc lập → X là giải thích của Y, KHÔNG phải grammar point riêng.
              • Toàn bộ text giải thích về X trong nguồn (kể cả ví dụ của X) phải được SAO CHÉP NGUYÊN VĂN
                và NỐI TIẾP vào cuối detailedExplanationVi của Y — KHÔNG được bỏ bất kỳ câu nào.
              • detailedExplanationVi của Y = [giải thích về Y] + " " + [giải thích về X nguyên văn từ sách]

            ═══ QUY TẮC TRÍCH XUẤT ═══
            1. grammarPoint: Tên điểm ngữ pháp cụ thể, ví dụ:
               "Trợ từ は (wa)", "Câu phủ định: Danh từ じゃ ありません", "Câu nghi vấn か"
            2. pattern: Mẫu cú pháp trừu tượng, ví dụ:
               "Danh từ は Danh từ です"  "Danh từ は Danh từ じゃ ありません"
            3. function: Chức năng ngữ pháp — TRÍCH NGUYÊN VĂN từ sách (tiếng Việt).
               Ví dụ: "Biểu thị danh từ đứng trước nó là chủ đề của câu"
            4. detailedExplanationVi: GIẢI THÍCH ĐẦY ĐỦ — SAO CHÉP NGUYÊN VĂN TIẾNG VIỆT từ sách,
               KHÔNG tóm tắt, KHÔNG diễn giải lại, KHÔNG dịch sang tiếng Anh.
               NẾU trong mục đó sách tiếp tục giải thích thành phần phụ (ví dụ: giải thích riêng về
               [です], [じゃ], [ありません] ngay bên dưới) → SAO CHÉP NGUYÊN VĂN phần đó và NỐI TIẾP
               vào sau phần giải thích chính, cách nhau bằng dấu cách.
               Kết quả phải chứa ĐẦY ĐỦ mọi câu giải thích trong mục đó từ sách.
            5. usageNotes: Tất cả [chú ý] hoặc ※ trong sách → PHẢI đưa vào đây.
               Mỗi chú ý là một phần tử trong mảng. KHÔNG BỎ QUA.
            6. examples: Câu ví dụ có ký hiệu ® hoặc ○ trong sách → trích đúng cặp Nhật-Việt.
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
                jlptLevel,
                jlptLevel);
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
                    "Phần này giải thích chi tiết từng điểm ngữ pháp, được đánh số 1), 2), 3)... " +
                    "Mỗi số là MỘT điểm ngữ pháp riêng biệt. " +
                    "Ký hiệu [chú ý] hoặc ※ → đưa vào mảng 'usageNotes', KHÔNG BỎ QUA. " +
                    "Câu ví dụ có ký hiệu ® hoặc ○ → trích đúng cặp Nhật-Việt vào 'exampleSentences'. " +
                    "Giải thích phải giữ nguyên văn tiếng Việt từ sách, không tóm tắt.\n";
            case MnNSectionSplitter.EXAMPLE ->
                    "\n⚠ LOẠI PHẦN: CÂU VÍ DỤ (例文 / Ví dụ — Phần II)\n" +
                    "Phần này minh họa mẫu ngữ pháp qua câu ví dụ (dạng hỏi-đáp). " +
                    "Xác định mẫu ngữ pháp mà mỗi ví dụ minh họa và đưa vào 'exampleSentences' " +
                    "của điểm ngữ pháp tương ứng.\n";
            default -> "";
        };
    }
}

package com.fu.curriculumimport.service.agent.prompt;

import com.fu.curriculumimport.service.embedding.RagContext;
import com.fu.curriculumimport.service.ingestion.DocumentChunk;
import com.fu.curriculumimport.service.ingestion.MnNSectionSplitter;
import com.fu.curriculumimport.service.ingestion.OcrTextPreprocessor;

/**
 * Builds system and user prompts for {@code VocabularyExtractionAgent}.
 *
 * <p>Extracted from the agent so the class containing LLM-call logic stays concise.
 * All members are package-private static — no Spring dependency, fully testable without
 * application context.
 */
public final class VocabularyPromptBuilder {

    private VocabularyPromptBuilder() {}

    // ── System prompt ─────────────────────────────────────────────────────────

    public static final String SYSTEM_PROMPT = """
            Bạn là chuyên gia trích xuất từ vựng tiếng Nhật cho nền tảng học ngôn ngữ.
            Nguồn tài liệu chính: sách "Minna no Nihongo I — Bản dịch và Giải thích Ngữ pháp (N5)"
            (tiếng Việt). Input có thể là PDF text, PDF scan (OCR), hoặc PowerPoint slide.

            ═══ QUY TẮC NGHĨA TIẾNG VIỆT ═══
            Đây là sách dịch tiếng Việt — nghĩa tiếng Việt LUÔN CÓ TRONG NGUỒN.
            Mặc định: "meaningSource": "SOURCE". Chỉ dùng "AI_INFERRED" khi tuyệt đối
            không tìm thấy nghĩa (ví dụ: slide PPTX chỉ liệt kê từ Nhật thuần túy).

            • PATTERN NGHĨA INLINE — ưu tiên cao nhất:
              Sách dịch tiếng Việt thường gắn nghĩa ngay sau từ Nhật trong ngoặc （）:
                行きます（đi）     食べます（ăn）     先生（giáo viên）
              Khi thấy pattern này:
                - japaneseText = phần tiếng Nhật ONLY → "行きます"  (không có ngoặc)
                - meaningVi    = nội dung trong （）   → "đi"
                - meaningSource = "SOURCE_INLINE"
              KHÔNG BAO GIỜ đưa phần （…） vào japaneseText.

            • NGOẠI LỆ — furigana (kana thuần túy trong （）):
              Nếu nội dung trong （） là hiragana/katakana thuần, đó là furigana,
              KHÔNG phải nghĩa. Đưa vào trường "reading":
                先生（せんせい）  → japaneseText="先生", reading="せんせい"

            • Nếu nghĩa nằm ở cột/dòng riêng → trích chính xác, "meaningSource": "SOURCE".

            LUÔN cung cấp cả meaningVi (tiếng Việt) lẫn meaningEn (tiếng Anh).
            KHÔNG để trống nghĩa. KHÔNG viết "unknown" hay "N/A".
            Giữ nguyên 100% phần tiếng Việt như trong sách, kể cả dấu ngoặc và chú thích.

            ═══ QUY TẮC HẬU TỐ (SUFFIX) ═══
            Các mục như ~さん, ~ちゃん, ~くん, ~じん, ~ご, ~ご PHẢI được đánh dấu:
              • "isSuffix": true
              • "suffixNote": giải thích cách dùng hậu tố (tiếng Việt, ngắn gọn)
                Ví dụ: "Thêm vào sau tên người để thể hiện sự lịch sự. Không tự dùng cho bản thân."

            ═══ QUY TẮC CHÚ THÍCH SỬ DỤNG (usageNote) ═══
            Sách thường có ghi chú hạn chế cách dùng trong ngoặc đơn sau nghĩa, ví dụ:
              "anh, chị, ông, bà (cách gọi người khác một cách lịch sự...)"
            Trích toàn bộ nội dung trong ngoặc đó vào trường "usageNote".
            Nếu không có chú thích → "usageNote": "".

            ═══ QUY TẮC SỐ THỨ TỰ (stt) ═══
            Ghi số thứ tự của từ trong danh sách từ vựng bài học (1, 2, 3...).
            Nếu không xác định được → "stt": 0.

            ═══ QUY TẮC GHÉP FURIGANA / KANJI (CRITICAL — ĐỌC KỸ) ═══
            OCR thường tách reading (hiragana) và kanji ra DÒNG RIÊNG hoặc Ô RIÊNG.
            Ví dụ OCR trả về:
              にほん          ← reading (hiragana)
              日本            ← kanji
              Nhật Bản        ← nghĩa tiếng Việt
            hoặc:  かんこく 韓国  Hàn Quốc   (reading + kanji + nghĩa trên cùng dòng)

            QUY TẮC BẮT BUỘC:
            • Reading (hiragana) + kanji tương ứng = MỘT từ vựng DUY NHẤT:
              - japaneseText = kanji nếu có (日本), nếu không có kanji thì dùng hiragana (にほん)
              - reading = hiragana (にほん)
              - kanjiWriting = kanji (日本)
            • TUYỆT ĐỐI KHÔNG tạo 2 entry riêng cho reading và kanji của cùng 1 từ.
            • Cách nhận biết: nếu chuỗi hiragana đọc giống chuỗi kanji liền kề → cùng 1 từ.
              Ví dụ: にほん → 日本, がくせい → 学生, せんせい → 先生
            • Nếu chỉ có hiragana không có kanji → japaneseText = hiragana, kanjiWriting = "".
            • Nếu chỉ có katakana (ví dụ: アメリカ) → japaneseText = katakana, reading = katakana.

            ❌ SAI:
              Entry 1: japaneseText="にほん", meaningVi="Nhật Bản"
              Entry 2: japaneseText="日本", meaningVi=""
            ✅ ĐÚNG:
              Entry 1: japaneseText="日本", reading="にほん", meaningVi="Nhật Bản", kanjiWriting="日本"

            ═══ QUY TẮC TRÍCH TOÀN BỘ (CRITICAL) ═══
            • PHẢI trích TOÀN BỘ từ vựng có trong nội dung, KHÔNG ĐƯỢC BỎ SÓT.
            • Đếm số từ trong nguồn và đảm bảo mảng vocabulary có đủ số lượng.
            • Nếu danh sách từ dài → trích HẾT, KHÔNG cắt bớt vì giới hạn đầu ra.
            • Nếu có bảng quốc gia/ngôn ngữ → mỗi ô là 1 từ riêng (quốc gia, quốc tịch, ngôn ngữ).

            ═══ QUY TẮC KHÁC ═══
            • Mọi từ tiếng Nhật phải có reading (hiragana/katakana).
            • Loại từ phải chính xác: noun/verb/i-adjective/na-adjective/
              adverb/particle/conjunction/interjection/counter/prefix/suffix/expression.
            • Câu ví dụ: lấy từ sách nếu có, nếu không tự tạo phù hợp với cấp JLPT.
            • Chỉ trả về JSON hợp lệ — không có markdown, không giải thích.

            ═══ PATTERN BẢNG QUỐC GIA-NGÔN NGỮ ═══
            Minna no Nihongo có bảng tham chiếu 4 cột:
              Cột 0: tên quốc gia (国名) | Cột 1: quốc tịch (〜人) | Cột 2: trống | Cột 3: ngôn ngữ (〜語)
              アメリカ ( Mỹ )            |                          |               | 英語 ( tiếng Anh )
              かんこく                   | かんこくじん              |               | かんこく ご

            QUY TẮC QUAN TRỌNG:
            • Cột 0 (quốc gia) và Cột 3 (ngôn ngữ) là CÁC TỪ RIÊNG BIỆT — trích cả hai.
            • KHÔNG dùng tên ngôn ngữ (Cột 3) làm nghĩa của quốc gia (Cột 0).
            • Nếu Cột 1 (quốc tịch) khác trống, trích thành MỤC RIÊNG.
            • Mỗi mục có entry riêng trong mảng "vocabulary".
            • Ngoặc tròn nửa chiều rộng word ( meaning ) là nghĩa tiếng Việt inline.
            """;

    /** Suffix appended to the system prompt on re-extraction attempts. */
    public static final String REEXTRACTION_SYSTEM_SUFFIX =
            "\nIMPORTANT: You MUST fix every issue noted below.";

    // ── Extraction prompt template ─────────────────────────────────────────────

    private static final String EXTRACTION_TEMPLATE = """
            Trích xuất TOÀN BỘ từ vựng từ nội dung học tiếng Nhật sau đây.

            Trang: %d-%d
            Cấp JLPT: %s
            Chủ đề / tiêu đề bài: %s
            Loại nguồn: %s
            %s

            ─── NỘI DUNG ────────────────────────────────────────────────────────
            %s
            ─────────────────────────────────────────────────────────────────────

            %s

            Trả về đúng cấu trúc JSON sau:
            {
              "vocabulary": [
                {
                  "stt": 1,
                  "japaneseText": "わたし",
                  "reading": "わたし",
                  "romaji": "watashi",
                  "meaningVi": "tôi",
                  "meaningEn": "I / me",
                  "partOfSpeech": "pronoun",
                  "jlptLevel": "%s",
                  "kanjiWriting": "",
                  "usageNote": "",
                  "isSuffix": false,
                  "suffixNote": "",
                  "exampleSentences": [
                    {
                      "jp": "わたしは マイク・ミラーです。",
                      "vi": "Tôi là Mike Miller.",
                      "en": "I am Mike Miller."
                    }
                  ],
                  "isKeyWord": true,
                  "meaningSource": "SOURCE"
                },
                {
                  "stt": 10,
                  "japaneseText": "〜さん",
                  "reading": "〜さん",
                  "romaji": "~san",
                  "meaningVi": "anh, chị, ông, bà (cách gọi người khác một cách lịch sự bằng cách thêm từ này vào sau tên của người đó)",
                  "meaningEn": "honorific suffix added after a person's name",
                  "partOfSpeech": "suffix",
                  "jlptLevel": "%s",
                  "kanjiWriting": "",
                  "usageNote": "cách gọi người khác một cách lịch sự bằng cách thêm từ này vào sau tên của người đó",
                  "isSuffix": true,
                  "suffixNote": "Thêm vào sau tên người để thể hiện sự lịch sự. Không tự dùng cho bản thân.",
                  "exampleSentences": [],
                  "isKeyWord": false,
                  "meaningSource": "SOURCE"
                }
              ],
              "confidence": 0.9
            }

            Dùng "meaningSource": "SOURCE" nếu nghĩa có trong nội dung trên.
            Dùng "meaningSource": "SOURCE_INLINE" nếu nghĩa được trích từ dạng 漢字（nghĩa）.
            Dùng "meaningSource": "AI_INFERRED" nếu AI suy ra nghĩa (không có trong nguồn).
            """;

    // ── Context block templates ────────────────────────────────────────────────

    private static final String RAG_CONTEXT_TEMPLATE = """

            VOCABULARY ALREADY IN DATABASE (skip duplicates, maintain consistency):
            %s
            """;

    private static final String FULL_PAGE_CONTEXT_TEMPLATE = """

            ─── FULL LESSON PAGE CONTEXT (adjacent sections — use for cross-referencing) ──
            %s
            ─────────────────────────────────────────────────────────────────────────────────
            Note: The section to extract FROM is the "CONTENT" block above.
            Use this full-page context only to resolve ambiguities and verify readings.
            """;

    // ── Public builder API ────────────────────────────────────────────────────

    /**
     * Build the user prompt for a vocabulary extraction call.
     *
     * @param chunk           document chunk to extract from
     * @param jlptLevel       target JLPT level string (e.g. "N5")
     * @param ragSection      pre-formatted RAG context block (may be empty)
     * @param feedbackSection validation feedback for re-extraction (may be empty)
     * @return user prompt string ready to pass to the LLM
     */
    public static String buildExtractionPrompt(DocumentChunk chunk, String jlptLevel,
                                               String ragSection, String feedbackSection) {
        String lessonTopic = inferLessonTopic(chunk);
        String sourceType = describeSourceType(chunk.getChunkType());
        String sectionHint = buildSectionHint(chunk);
        String fullPageSection = buildFullPageContextSection(chunk);

        String safeContent = OcrTextPreprocessor.truncateToTokenBudget(
                chunk.getContent(), OcrTextPreprocessor.MAX_CONTENT_TOKENS);

        String contextBlocks = sectionHint + fullPageSection + ragSection + feedbackSection;

        return String.format(EXTRACTION_TEMPLATE,
                chunk.getPageStart(), chunk.getPageEnd(),
                jlptLevel,
                lessonTopic,
                sourceType,
                contextBlocks,
                safeContent,
                buildMeaningHint(chunk),
                jlptLevel,
                jlptLevel);
    }

    /**
     * Format a RAG context for vocabulary.
     * Returns an empty string when no relevant context is available.
     */
    public static String formatRagContext(RagContext ragContext) {
        if (ragContext == null || !ragContext.hasContext()) return "";
        StringBuilder sb = new StringBuilder();
        for (RagContext.SimilarItem item : ragContext.getSimilarItems()) {
            if ("VOCABULARY".equals(item.getContentType())) {
                sb.append("- ").append(item.getTextContent()).append("\n");
            }
        }
        return sb.isEmpty() ? "" : String.format(RAG_CONTEXT_TEMPLATE, sb);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Inject the full lesson page context when the chunk is a section sub-chunk.
     * Only added when the page context is meaningfully larger than the chunk itself.
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
     * Tells the LLM exactly what kind of section it is reading.
     */
    private static String buildSectionHint(DocumentChunk chunk) {
        String sectionType = chunk.getSectionType();
        if (sectionType == null || MnNSectionSplitter.UNKNOWN.equals(sectionType)) return "";
        return switch (sectionType) {
            case MnNSectionSplitter.VOCABULARY ->
                    "\n⚠ LOẠI PHẦN: DANH SÁCH TỪ VỰNG (語彙 / Từ vựng — Phần I)\n" +
                    "Phần này chứa danh sách từ vựng của bài, được đánh số thứ tự. " +
                    "Mỗi mục có: từ tiếng Nhật, cách đọc, và nghĩa tiếng Việt. " +
                    "Nghĩa tiếng Việt LUÔN có sẵn trong nguồn — 'meaningSource': 'SOURCE'. " +
                    "Điền 'stt' theo số thứ tự trong danh sách. " +
                    "Trích TOÀN BỘ — không bỏ sót từ nào. " +
                    "Hậu tố như ~さん, ~ちゃん, ~くん, ~じん phải có 'isSuffix': true.\n";
            case MnNSectionSplitter.EXAMPLE ->
                    "\n⚠ LOẠI PHẦN: CÂU VÍ DỤ (例文 / Ví dụ — Phần II)\n" +
                    "Phần này chứa câu ví dụ minh họa từ vựng của bài. " +
                    "Trích từ vựng xuất hiện trong ví dụ nếu chưa có trong phần Từ vựng.\n";
            case MnNSectionSplitter.DIALOG ->
                    "\n⚠ LOẠI PHẦN: HỘI THOẠI (会話 / Hội thoại — Phần II)\n" +
                    "Phần này là hội thoại có kịch bản. " +
                    "Trích từ vựng quan trọng xuất hiện trong hội thoại và liên quan chủ đề bài.\n";
            default -> "";
        };
    }

    /**
     * Determine the lesson topic/title for use in the extraction prompt.
     * Priority: lesson title from boundary detector → PPTX title line → first 120 chars.
     */
    private static String inferLessonTopic(DocumentChunk chunk) {
        if (chunk.getLessonTitle() != null && !chunk.getLessonTitle().isBlank()) {
            return chunk.getLessonTitle();
        }
        String content = chunk.getContent();
        if (content == null) return "unknown";

        String firstLine = content.lines().findFirst().orElse("").trim();
        if (firstLine.contains("タイトル:")) {
            return firstLine.substring(firstLine.indexOf("タイトル:") + 5).trim();
        }
        if (firstLine.contains("[SLIDE")) {
            return firstLine;
        }
        return content.substring(0, Math.min(120, content.length())).replaceAll("\\s+", " ").trim();
    }

    private static String describeSourceType(String chunkType) {
        if (chunkType == null) return "text-based PDF";
        return switch (chunkType) {
            case "SLIDE"   -> "PowerPoint slide (vocabulary list may NOT include meanings — infer them)";
            case "SCANNED" -> "scanned PDF page extracted via OCR (text quality may be imperfect)";
            default        -> "text-based PDF";
        };
    }

    /**
     * Additional hint injected when the source is a slide (meanings likely absent).
     */
    private static String buildMeaningHint(DocumentChunk chunk) {
        if ("SLIDE".equals(chunk.getChunkType())) {
            return """
                    ⚠ IMPORTANT: This is a PowerPoint slide. \
                    The vocabulary list likely has NO meanings. \
                    For every word without an explicit meaning, set "meaningSource": "AI_INFERRED" \
                    and generate an accurate meaning based on the lesson topic and JLPT level.""";
        }
        return "";
    }
}

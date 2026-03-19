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
            You are an expert Japanese grammar extractor for a language-learning platform.
            You receive one or more scanned pages from a Japanese textbook (Minna no Nihongo or similar).

            ═══ YOUR TASK ═══
            Visually read the page image(s) and extract ALL grammar patterns and grammar notes.
            Grammar sections typically show:
            - A numbered pattern (e.g., ～ます、～ている、Nは～です)
            - Hiragana reading of the pattern
            - Vietnamese / English explanation of when and how to use it
            - Example sentences with translations
            - Usage notes, conjugation rules, or common mistakes

            ═══ RULES ═══
            • Extract EVERY grammar pattern visible in the image.
            • Include the exact pattern string with tildes and particles (e.g., "〜ます", not just "ます").
            • Provide both meaningVi (Vietnamese) AND meaningEn (English).
            • Include at least one example sentence per pattern.
            • Category must be one of:
              VERB_FORM | PARTICLE | SENTENCE_ENDING | CONDITIONAL | TENSE | POLITENESS | OTHER.
            • "isKeyPoint" should be true for the main numbered patterns; false for sub-notes.

            Respond ONLY with valid JSON — no markdown, no explanations.
            """;

    public static final String GRAMMAR_USER_TEMPLATE = """
            Extract ALL grammar patterns from the textbook page image(s) below.

            Lesson: %s
            JLPT Level: %s

            Return this exact JSON structure:
            {
              "grammarPoints": [
                {
                  "pattern": "〜ます",
                  "patternReading": "ます",
                  "meaningVi": "Dạng lịch sự của động từ (thì hiện tại / tương lai)",
                  "meaningEn": "Polite present/future verb form",
                  "shortExplanation": "Polite verb ending for present/future tense",
                  "detailedExplanation": "Attach ます to the verb stem to form polite speech.",
                  "jlptLevel": "%s",
                  "category": "VERB_FORM",
                  "exampleSentences": [
                    {
                      "jp": "毎日日本語を勉強します。",
                      "vi": "Tôi học tiếng Nhật mỗi ngày.",
                      "en": "I study Japanese every day."
                    }
                  ],
                  "commonMistakes": [],
                  "isKeyPoint": true
                }
              ],
              "confidence": 0.88
            }
            """;
}

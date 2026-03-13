package com.fu.curriculumimport.service.ingestion;

import com.fu.curriculumimport.service.agent.ExtractionResult;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses structured vocabulary table rows produced by the OCR service.
 *
 * <h3>Background</h3>
 * <p>Minna no Nihongo vocabulary lists are laid out as a 4-5 column table:
 * <pre>
 *  No. │ Japanese word  │ Reading    │ Vietnamese meaning      │ Example
 *  ────┼────────────────┼────────────┼─────────────────────────┼─────────
 *   1  │ 行きます       │ いきます   │ (đt nhóm I) đi          │ どこへ…
 *   2  │ 来ます         │ きます     │ (đt nhóm III) đến       │
 * </pre>
 *
 * <p>When the OCR service (or PDFBox text extraction) returns these rows as
 * tab-separated strings via {@link DocumentChunk#getStructuredTableRows()},
 * this parser converts them directly into {@link ExtractionResult.ExtractedVocabulary}
 * objects — <b>no LLM call required</b>.
 *
 * <h3>Column detection heuristic</h3>
 * <ol>
 *   <li>Col 0: serial number (digits only) — skip</li>
 *   <li>Col 1: Japanese word (contains CJK/kana)</li>
 *   <li>Col 2: reading (pure hiragana/katakana) or romaji</li>
 *   <li>Col 3: meaning (Vietnamese/English — may contain POS tag in parentheses)</li>
 *   <li>Col 4+: example sentence (Japanese) — optional</li>
 * </ol>
 *
 * <p>If the column count or content does not match expectations, the row is
 * skipped and the caller falls back to LLM extraction for the whole chunk.
 */
@Slf4j
public final class MnNTableParser {

    private static final Pattern DIGITS_ONLY   = Pattern.compile("^\\d+\\.?$");
    private static final Pattern CJK_OR_KANA   = Pattern.compile("[\\u3040-\\u9FFF]");
    private static final Pattern PURE_KANA     = Pattern.compile("^[\\u3040-\\u30FF\\u30A0-\\u30FFー・ \\t]+$");
    private static final Pattern ROMAJI        = Pattern.compile("^[a-zA-Z\\s\\-']+$");
    private static final Pattern POS_TAG       = Pattern.compile("^\\(([^)]{1,30})\\)(.*)$");

    private MnNTableParser() {}

    /**
     * Attempt to parse structured table rows into vocabulary items.
     *
     * @param rows       tab-separated table rows from OCR structured output
     * @param jlptLevel  JLPT level to tag each item with (e.g. "N5")
     * @return parsed vocabulary list; empty when rows are not well-structured
     */
    public static List<ExtractionResult.ExtractedVocabulary> parse(
            List<String> rows, String jlptLevel) {

        if (rows == null || rows.isEmpty()) return List.of();

        // ── Fast path: country-language reference table ───────────────────────
        // Minna no Nihongo has a special 4-column table in the countries chapter:
        //   Col 0: country name  | Col 1: nationality (〜人) | Col 2: empty | Col 3: language (〜語)
        //   アメリカ ( Mý )      |                           |              | 英語 ( tiêng Anh )
        //   かんこく             | かんこくじん              |              | かんこく ご
        // Each row yields 2–3 separate vocabulary items, not 1.
        if (isCountryLanguageTable(rows)) {
            List<ExtractionResult.ExtractedVocabulary> ctResult = parseCountryLanguageTable(rows, jlptLevel);
            if (!ctResult.isEmpty()) {
                log.info("MnNTableParser: country-language table detected — extracted {} items from {} rows",
                        ctResult.size(), rows.size());
                return ctResult;
            }
        }

        // ── Standard vocabulary table ─────────────────────────────────────────
        List<ExtractionResult.ExtractedVocabulary> result = new ArrayList<>();
        int skipped = 0;

        for (String row : rows) {
            if (row == null || row.isBlank()) continue;

            String[] cells = row.split("\t", -1);
            if (cells.length < 3) {
                skipped++;
                continue;
            }

            ExtractionResult.ExtractedVocabulary vocab = parseRow(cells, jlptLevel);
            if (vocab != null) {
                result.add(vocab);
            } else {
                skipped++;
            }
        }

        if (skipped > 0) {
            log.debug("MnNTableParser: parsed {}/{} rows ({} skipped)",
                    result.size(), rows.size(), skipped);
        }

        // If fewer than half the rows parsed successfully, the table structure
        // is probably incorrect — return empty so caller uses LLM fallback.
        if (result.size() < rows.size() / 2.0 && !result.isEmpty()) {
            log.info("MnNTableParser: low parse rate ({}/{}) — discarding, using LLM fallback",
                    result.size(), rows.size());
            return List.of();
        }

        return result;
    }

    // ── Row parsing ──────────────────────────────────────────────────────────

    private static ExtractionResult.ExtractedVocabulary parseRow(String[] cells, String jlptLevel) {
        int colOffset = 0;

        // Skip leading serial-number column
        if (DIGITS_ONLY.matcher(cells[0].trim()).matches()) {
            colOffset = 1;
        }

        if (cells.length <= colOffset) return null;

        // Column 1: Japanese word
        // Strip any inline meaning attached to the word: 行きます（đi） → word=行きます, meaning=đi
        String rawJapanese = cells[colOffset].trim();
        if (rawJapanese.isBlank() || !CJK_OR_KANA.matcher(rawJapanese).find()) return null;

        FuriganaNormalizer.InlineMeaning inlineResult = FuriganaNormalizer.extractInlineMeaning(rawJapanese);
        String japanese         = inlineResult.found() ? inlineResult.word() : rawJapanese;
        String col1InlineMeaning = inlineResult.found() ? inlineResult.meaning() : null;

        // Re-validate: stripped word must still contain CJK/kana
        if (japanese.isBlank() || !CJK_OR_KANA.matcher(japanese).find()) return null;

        // Column 2: reading (hiragana/katakana) or romaji
        String reading = cells.length > colOffset + 1 ? cells[colOffset + 1].trim() : "";
        String romaji  = "";
        if (!reading.isBlank() && ROMAJI.matcher(reading).matches()) {
            romaji  = reading;
            reading = ""; // no hiragana provided in this column
        }

        // Column 3: meaning (may start with POS tag in parentheses)
        String meaningRaw   = cells.length > colOffset + 2 ? cells[colOffset + 2].trim() : "";
        String partOfSpeech = "";
        String meaningVi    = meaningRaw;
        String meaningEn    = "";

        if (!meaningRaw.isBlank()) {
            var m = POS_TAG.matcher(meaningRaw);
            if (m.matches()) {
                partOfSpeech = normalizePOS(m.group(1).trim());
                meaningVi    = m.group(2).trim();
            }
        }

        // Fallback: if column 3 is blank, use the inline meaning extracted from column 1
        if (meaningVi.isBlank() && col1InlineMeaning != null) {
            // Inline meanings may also carry a POS tag: "(đt nhóm I) đi"
            var inlineM = POS_TAG.matcher(col1InlineMeaning);
            if (inlineM.matches()) {
                partOfSpeech = normalizePOS(inlineM.group(1).trim());
                meaningVi    = inlineM.group(2).trim();
            } else {
                meaningVi = col1InlineMeaning;
            }
        }

        // Column 4 (optional): example sentence in Japanese
        List<java.util.Map<String, String>> examples = new ArrayList<>();
        if (cells.length > colOffset + 3) {
            String exJp = cells[colOffset + 3].trim();
            if (!exJp.isBlank() && CJK_OR_KANA.matcher(exJp).find()) {
                examples.add(java.util.Map.of("jp", exJp, "vi", "", "en", ""));
            }
        }

        if (japanese.isBlank() || meaningVi.isBlank()) return null;

        // SOURCE_INLINE: meaning came from （）brackets on the japanese word cell (still source-derived,
        // but worth a secondary manager review since POS detection is heuristic).
        // SOURCE: meaning came from a dedicated meaning column (col 3) — higher confidence.
        String meaningSource = (meaningRaw.isBlank() && col1InlineMeaning != null)
                ? "SOURCE_INLINE"
                : "SOURCE";

        return ExtractionResult.ExtractedVocabulary.builder()
                .japaneseText(japanese)
                .kanjiWriting(CJK_OR_KANA.matcher(japanese).find() ? japanese : null)
                .reading(PURE_KANA.matcher(reading).matches() ? reading : "")
                .romaji(romaji)
                .meaningVi(meaningVi)
                .meaningEn(meaningEn.isBlank() ? null : meaningEn)
                .partOfSpeech(partOfSpeech.isBlank() ? "noun" : partOfSpeech)
                .jlptLevel(jlptLevel != null ? jlptLevel : "N5")
                .exampleSentences(examples)
                .isKeyWord(false)
                .meaningSource(meaningSource)
                .build();
    }

    // ── Country-language table support ───────────────────────────────────────

    /**
     * Detect whether the row list is a country-language reference table rather than
     * a standard vocabulary table.
     *
     * Heuristic: ≥50 % of rows have ≥4 columns where col 0 is CJK/kana, col 2 is
     * blank, and col 3 is CJK/kana (the language name).
     */
    private static boolean isCountryLanguageTable(List<String> rows) {
        int total = 0;
        int matched = 0;
        for (String row : rows) {
            if (row == null || row.isBlank()) continue;
            String[] cells = row.split("\t", -1);
            total++;
            if (cells.length < 4) continue;
            int offset = DIGITS_ONLY.matcher(cells[0].trim()).matches() ? 1 : 0;
            if (cells.length <= offset + 3) continue;
            String col0 = cells[offset].trim();
            String col2 = cells[offset + 2].trim();
            String col3 = cells[offset + 3].trim();
            if (CJK_OR_KANA.matcher(col0).find()
                    && col2.isBlank()
                    && CJK_OR_KANA.matcher(col3).find()
                    && col3.length() < 60) {
                matched++;
            }
        }
        return total >= 3 && (matched * 1.0 / total) >= 0.5;
    }

    /**
     * Parse a country-language reference table.
     * Each row yields up to 3 vocabulary items:
     * (1) country name from col 0,
     * (2) nationality person form from col 1 (if non-empty),
     * (3) language name from col 3.
     */
    private static List<ExtractionResult.ExtractedVocabulary> parseCountryLanguageTable(
            List<String> rows, String jlptLevel) {
        List<ExtractionResult.ExtractedVocabulary> result = new ArrayList<>();
        for (String row : rows) {
            if (row == null || row.isBlank()) continue;
            String[] cells = row.split("\t", -1);
            if (cells.length < 2) continue;
            int offset = DIGITS_ONLY.matcher(cells[0].trim()).matches() ? 1 : 0;

            // Col 0: country name (e.g. アメリカ, かんこく, 中国)
            if (offset < cells.length) {
                ExtractionResult.ExtractedVocabulary country =
                        parseSingleVocabCell(cells[offset], jlptLevel);
                if (country != null) result.add(country);
            }

            // Col 1: nationality person form (e.g. かんこくじん, アメリカ人) — may be empty
            int col1 = offset + 1;
            if (col1 < cells.length && !cells[col1].trim().isBlank()) {
                ExtractionResult.ExtractedVocabulary person =
                        parseSingleVocabCell(cells[col1], jlptLevel);
                if (person != null) result.add(person);
            }

            // Col 3: language name (e.g. 英語, かんこくご, 中国語)
            int col3 = offset + 3;
            if (col3 < cells.length && !cells[col3].trim().isBlank()) {
                ExtractionResult.ExtractedVocabulary lang =
                        parseSingleVocabCell(cells[col3], jlptLevel);
                if (lang != null) result.add(lang);
            }
        }
        return result;
    }

    /**
     * Parse a single vocabulary cell (country, language, or nationality form).
     *
     * Handles:
     * <ul>
     *   <li>Full-width inline meaning: {@code アメリカ（Mỹ）} via {@link FuriganaNormalizer#extractInlineMeaning}</li>
     *   <li>Half-width inline meaning: {@code アメリカ ( Mý )} — OCR output style</li>
     *   <li>Pure-kana entries without meanings: {@code かんこく} — tagged AI_INFERRED</li>
     *   <li>Furigana annotations: {@code 英語[えいご]} → reading extracted</li>
     * </ul>
     *
     * @return {@code null} if the cell does not contain a valid CJK/kana word
     */
    private static ExtractionResult.ExtractedVocabulary parseSingleVocabCell(
            String cellText, String jlptLevel) {
        if (cellText == null || cellText.isBlank()) return null;
        String raw = cellText.trim();
        if (!CJK_OR_KANA.matcher(raw).find()) return null;

        // Extract inline meaning (handles both （）full-width and ( ) half-width)
        FuriganaNormalizer.InlineMeaning inline = FuriganaNormalizer.extractInlineMeaning(raw);
        // Collapse internal spaces from compound words like "中国 語" → "中国語"
        String japanese = (inline.found() ? inline.word() : raw).replaceAll("\\s+", "");
        String meaningVi = inline.found() ? inline.meaning() : null;

        if (japanese.isBlank() || !CJK_OR_KANA.matcher(japanese).find()) return null;

        // Extract reading if furigana was already normalized: 英語[えいご] → reading=えいご
        String reading = "";
        int rb = japanese.indexOf('[');
        int re = rb >= 0 ? japanese.indexOf(']', rb) : -1;
        if (rb >= 0 && re > rb) {
            reading = japanese.substring(rb + 1, re);
            japanese = japanese.substring(0, rb);
        } else if (PURE_KANA.matcher(japanese).matches()) {
            reading = japanese; // the word itself is kana (e.g. かんこく)
        }

        return ExtractionResult.ExtractedVocabulary.builder()
                .japaneseText(japanese)
                .kanjiWriting(japanese)
                .reading(reading)
                .romaji("")
                .meaningVi(meaningVi)
                .meaningEn(null)
                .partOfSpeech("noun")
                .jlptLevel(jlptLevel != null ? jlptLevel : "N5")
                .exampleSentences(new ArrayList<>())
                .isKeyWord(false)
                .meaningSource(meaningVi != null ? "SOURCE_INLINE" : "AI_INFERRED")
                .build();
    }

    // ── POS tag normalisation ────────────────────────────────────────────────

    /**
     * Map Vietnamese POS abbreviations (as printed in MnN Vietnamese edition)
     * to English POS strings expected by the vocabulary entity.
     */
    private static String normalizePOS(String raw) {
        String lower = raw.toLowerCase();
        if (lower.contains("đt") || lower.contains("động từ") || lower.contains("v."))
            return "verb";
        if (lower.contains("dt") || lower.contains("danh từ") || lower.contains("n."))
            return "noun";
        if (lower.contains("tt") || lower.contains("tính từ") || lower.contains("adj."))
            return lower.contains("na") ? "na-adjective" : "i-adjective";
        if (lower.contains("phó từ") || lower.contains("adv."))
            return "adverb";
        if (lower.contains("trợ từ") || lower.contains("part.") || lower.contains("hạt nhân"))
            return "particle";
        if (lower.contains("liên từ") || lower.contains("conj."))
            return "conjunction";
        if (lower.contains("thán từ") || lower.contains("interj."))
            return "interjection";
        if (lower.contains("danh số") || lower.contains("counter"))
            return "counter";
        if (lower.contains("tiền tố") || lower.contains("prefix"))
            return "prefix";
        if (lower.contains("hậu tố") || lower.contains("suffix"))
            return "suffix";
        return "noun"; // default
    }
}

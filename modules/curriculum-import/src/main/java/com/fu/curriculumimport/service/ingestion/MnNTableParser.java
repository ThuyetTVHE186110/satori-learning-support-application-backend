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
        String japanese = cells[colOffset].trim();
        if (japanese.isBlank() || !CJK_OR_KANA.matcher(japanese).find()) return null;

        // Column 2: reading (hiragana/katakana) or romaji
        String reading = cells.length > colOffset + 1 ? cells[colOffset + 1].trim() : "";
        String romaji  = "";
        if (!reading.isBlank() && ROMAJI.matcher(reading).matches()) {
            romaji  = reading;
            reading = ""; // no hiragana provided in this column
        }

        // Column 3: meaning (may start with POS tag in parentheses)
        String meaningRaw = cells.length > colOffset + 2 ? cells[colOffset + 2].trim() : "";
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

        // Column 4 (optional): example sentence in Japanese
        List<java.util.Map<String, String>> examples = new ArrayList<>();
        if (cells.length > colOffset + 3) {
            String exJp = cells[colOffset + 3].trim();
            if (!exJp.isBlank() && CJK_OR_KANA.matcher(exJp).find()) {
                examples.add(java.util.Map.of("jp", exJp, "vi", "", "en", ""));
            }
        }

        if (japanese.isBlank() || meaningVi.isBlank()) return null;

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
                .meaningSource("SOURCE")
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

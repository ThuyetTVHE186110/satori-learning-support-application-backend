package com.fu.curriculumimport.service.ingestion;

import com.fu.curriculumimport.service.agent.ExtractionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses structured vocabulary table rows produced by the OCR service.
 *
 * <h3>Background</h3>
 * <p>Japanese-language textbooks commonly present vocabulary as multi-column tables.
 * When the OCR service returns these rows as tab-separated strings via
 * {@link DocumentChunk#getStructuredTableRows()}, this parser converts them directly
 * into {@link ExtractionResult.ExtractedVocabulary} objects — <b>no LLM call required</b>.
 *
 * <h3>Dynamic column detection</h3>
 * <p>Rather than assuming a fixed column order, this parser <b>profiles every column</b>
 * across all rows and assigns roles based on content analysis:
 * <ul>
 *   <li><b>WORD</b>  — column with highest CJK/kana content rate</li>
 *   <li><b>READING</b> — column with highest pure-kana rate (among remaining)</li>
 *   <li><b>MEANING</b> — column with highest non-Japanese letter rate (Vietnamese/English)</li>
 *   <li><b>ROMAJI</b>  — column with highest ASCII-letter-only rate (among remaining)</li>
 * </ul>
 *
 * <p>If no meaning column is detected (e.g. country-language reference tables,
 * surname ranking tables), the parser returns empty and the caller falls back
 * to LLM extraction. This makes the parser <b>curriculum-agnostic</b> — it works
 * for any textbook, not just Minna no Nihongo.
 */
@Slf4j
@Component
public class MnNTableParser {

    private static final Pattern DIGITS_ONLY = Pattern.compile("^\\d+\\.?$");
    private static final Pattern CJK_OR_KANA = Pattern.compile("[\\u3040-\\u9FFF]");
    private static final Pattern PURE_KANA   = Pattern.compile("^[\\u3040-\\u30FF\\u30A0-\\u30FFー・ \\t]+$");

    /**
     * Matches "kana space kanji" format in a single cell, e.g. "かんこく 韓国".
     * Group 1 = pure kana reading, Group 2 = kanji word.
     */
    private static final Pattern KANA_SPACE_KANJI = Pattern.compile(
            "^([\\u3040-\\u30FFー]+)\\s+([\\u4E00-\\u9FFF].*)$");
    private static final Pattern ROMAJI      = Pattern.compile("^[a-zA-Z\\s\\-']+$");
    private static final Pattern POS_TAG     = Pattern.compile("^\\(([^)]{1,30})\\)(.*)$");

    /**
     * Detects Japanese text (CJK ideographs or kana) that should NOT appear in
     * a Vietnamese/English meaning field. Used as a per-row safety guard.
     */
    private static final Pattern JAPANESE_IN_MEANING = Pattern.compile("[\\u3040-\\u30FF\\u4E00-\\u9FFF]");

    /**
     * Matches Japanese text inside brackets or parenthetical notes — these are
     * quoted examples embedded within Vietnamese explanations and should NOT
     * trigger the Japanese-in-meaning safety guard.
     * Examples:
     * - "người kia (「あのかた」là cách nói lịch sự của「あのひと」)"
     * - "(hậu tố thêm vào phía sau tên của trẻ em thay cho ～さん)"
     * - "nhân viên Công ty ~ (dùng kèm theo tên công ty; ví dụ IMCの しゃいん)"
     */
    private static final Pattern JAPANESE_IN_BRACKETS = Pattern.compile(
            "「[^」]*」|｢[^｣]*｣"                                // 「...」 brackets
            + "|[～〜~][\\u3040-\\u30FFー]+"                    // tilde + kana suffix (～さん)
    );

    // ── Column role detection ─────────────────────────────────────────────────

    /**
     * Maps detected column indices for each semantic role.
     * A value of {@code -1} means the role was not detected.
     */
    private record ColumnMapping(int wordCol, int readingCol, int meaningCol, int romajiCol) {}

    /**
     * Profile every column across all rows and assign semantic roles.
     *
     * <p>For each column, counts how many non-blank cells fall into each category:
     * digits-only, CJK-mixed, pure-kana, romaji, or non-Japanese letters (Vietnamese).
     * Then assigns roles by highest rate, with minimum thresholds to avoid false positives.
     *
     * @return column mapping; any field may be {@code -1} if the role was not found
     */
    private ColumnMapping detectColumns(List<String> rows) {
        int maxCols = 0;
        int rowCount = 0;
        for (String row : rows) {
            if (row == null || row.isBlank()) continue;
            maxCols = Math.max(maxCols, row.split("\t", -1).length);
            rowCount++;
        }

        if (maxCols < 2 || rowCount == 0) {
            return new ColumnMapping(-1, -1, -1, -1);
        }

        // Per-column content profile counters
        int[] digitCount    = new int[maxCols];
        int[] cjkMixCount   = new int[maxCols]; // contains CJK/kana but NOT pure-kana
        int[] pureKanaCount = new int[maxCols];
        int[] romajiCount   = new int[maxCols];
        int[] viCount       = new int[maxCols]; // non-Japanese letters (Vietnamese/English/Latin)
        int[] nonBlank      = new int[maxCols];

        for (String row : rows) {
            if (row == null || row.isBlank()) continue;
            String[] cells = row.split("\t", -1);
            for (int i = 0; i < cells.length; i++) {
                String c = cells[i].trim();
                if (c.isBlank()) continue;
                nonBlank[i]++;

                if (DIGITS_ONLY.matcher(c).matches()) {
                    digitCount[i]++;
                } else if (PURE_KANA.matcher(c).matches()) {
                    pureKanaCount[i]++;
                } else if (ROMAJI.matcher(c).matches()) {
                    romajiCount[i]++;
                } else if (CJK_OR_KANA.matcher(c).find()) {
                    // Contains CJK/kana but is mixed (kanji + kana, or CJK + Vietnamese inline)
                    cjkMixCount[i]++;
                } else if (hasAnyLetter(c)) {
                    // Contains letters but NO CJK/kana → Vietnamese/English/Latin text
                    viCount[i]++;
                }
            }
        }

        // ── Step 1: MEANING column — highest non-Japanese letter rate (≥30%) ──
        int meaningCol = -1;
        double bestViRate = 0.3;
        for (int i = 0; i < maxCols; i++) {
            if (nonBlank[i] == 0) continue;
            double r = viCount[i] * 1.0 / nonBlank[i];
            if (r > bestViRate) {
                bestViRate = r;
                meaningCol = i;
            }
        }

        // ── Step 2: WORD column — highest CJK content rate (among remaining) ──
        int wordCol = -1;
        double bestWordRate = 0;
        for (int i = 0; i < maxCols; i++) {
            if (i == meaningCol || nonBlank[i] == 0) continue;
            double r = (cjkMixCount[i] + pureKanaCount[i]) * 1.0 / nonBlank[i];
            // Prefer columns with actual kanji over pure-kana
            if (cjkMixCount[i] > 0) r += 0.05;
            if (r > bestWordRate) {
                bestWordRate = r;
                wordCol = i;
            }
        }

        // ── Step 2b: Swap word↔reading when wordCol is predominantly pure-kana
        //     and another column has kanji (cjkMix) entries.
        //     Common pattern: hiragana | kanji | meaning (e.g. みなさん | 皆さん | ...)
        //     Without swap: word=0 (kana), reading=-1, kanji col ignored.
        //     With swap:    word=1 (kanji), reading=0 (kana) — correct.
        if (wordCol >= 0 && nonBlank[wordCol] > 0) {
            double pureKanaRateInWord = pureKanaCount[wordCol] * 1.0 / nonBlank[wordCol];
            if (pureKanaRateInWord >= 0.5) {
                // wordCol is mostly pure kana — look for a better kanji column
                int kanjiCol = -1;
                int bestKanjiCount = 0;
                for (int i = 0; i < maxCols; i++) {
                    if (i == meaningCol || i == wordCol || nonBlank[i] == 0) continue;
                    if (cjkMixCount[i] > bestKanjiCount) {
                        bestKanjiCount = cjkMixCount[i];
                        kanjiCol = i;
                    }
                }
                if (kanjiCol >= 0 && bestKanjiCount > 0) {
                    log.debug("MnNTableParser: swapping word col {} (pure kana {}) ↔ kanji col {} (cjkMix {})",
                            wordCol, pureKanaCount[wordCol], kanjiCol, bestKanjiCount);
                    int readingCandidate = wordCol;
                    wordCol = kanjiCol;
                    // The old wordCol becomes the reading column (set below after step 3)
                    // We'll handle this by directly assigning readingCol here
                    // and skipping the normal step 3 detection for these columns
                    int readingCol2 = readingCandidate;
                    // Continue to step 3 with readingCol pre-set
                    // Jump to step 4 directly
                    int romajiCol2 = -1;
                    double bestRomajiRate2 = 0.3;
                    for (int i = 0; i < maxCols; i++) {
                        if (i == meaningCol || i == wordCol || i == readingCol2 || nonBlank[i] == 0) continue;
                        double r = romajiCount[i] * 1.0 / nonBlank[i];
                        if (r > bestRomajiRate2) {
                            bestRomajiRate2 = r;
                            romajiCol2 = i;
                        }
                    }
                    log.debug("MnNTableParser: detected columns — word={}, reading={}, meaning={}, romaji={} (from {} cols, {} rows)",
                            wordCol, readingCol2, meaningCol, romajiCol2, maxCols, rowCount);
                    return new ColumnMapping(wordCol, readingCol2, meaningCol, romajiCol2);
                }
            }
        }

        // ── Step 3: READING column — highest pure-kana rate (≥30%, among remaining) ──
        int readingCol = -1;
        double bestKanaRate = 0.3;
        for (int i = 0; i < maxCols; i++) {
            if (i == meaningCol || i == wordCol || nonBlank[i] == 0) continue;
            double r = pureKanaCount[i] * 1.0 / nonBlank[i];
            if (r > bestKanaRate) {
                bestKanaRate = r;
                readingCol = i;
            }
        }

        // ── Step 4: ROMAJI column — highest romaji rate (≥30%, among remaining) ──
        int romajiCol = -1;
        double bestRomajiRate = 0.3;
        for (int i = 0; i < maxCols; i++) {
            if (i == meaningCol || i == wordCol || i == readingCol || nonBlank[i] == 0) continue;
            double r = romajiCount[i] * 1.0 / nonBlank[i];
            if (r > bestRomajiRate) {
                bestRomajiRate = r;
                romajiCol = i;
            }
        }

        log.debug("MnNTableParser: detected columns — word={}, reading={}, meaning={}, romaji={} (from {} cols, {} rows)",
                wordCol, readingCol, meaningCol, romajiCol, maxCols, rowCount);
        return new ColumnMapping(wordCol, readingCol, meaningCol, romajiCol);
    }

    /**
     * Returns true if the string contains at least one Unicode letter character.
     */
    private boolean hasAnyLetter(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetter(text.charAt(i))) return true;
        }
        return false;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Attempt to parse structured table rows into vocabulary items.
     *
     * <p>The parser dynamically detects which column contains the Japanese word,
     * reading, and Vietnamese meaning. If no meaning column is found (e.g. the
     * table is a country-language reference, surname ranking, or other non-vocabulary
     * layout), it returns empty so the caller falls back to LLM extraction.
     *
     * @param rows       tab-separated table rows from OCR structured output
     * @param jlptLevel  JLPT level to tag each item with (e.g. "N5")
     * @return parsed vocabulary list; empty when rows are not well-structured
     */
    public List<ExtractionResult.ExtractedVocabulary> parse(
            List<String> rows, String jlptLevel) {

        if (rows == null || rows.isEmpty()) return List.of();

        // ── Dynamic column detection ────────────────────────────────────────
        ColumnMapping mapping = detectColumns(rows);

        if (mapping.wordCol < 0) {
            log.info("MnNTableParser: no Japanese word column detected ({} rows) — using LLM fallback",
                    rows.size());
            return List.of();
        }

        if (mapping.meaningCol < 0) {
            log.info("MnNTableParser: no Vietnamese meaning column detected ({} rows) — using LLM fallback",
                    rows.size());
            return List.of();
        }

        // ── Parse each row using detected column positions ──────────────────
        List<ExtractionResult.ExtractedVocabulary> result = new ArrayList<>();
        int skipped = 0;

        for (String row : rows) {
            if (row == null || row.isBlank()) continue;

            String[] cells = row.split("\t", -1);

            ExtractionResult.ExtractedVocabulary vocab = parseRow(cells, jlptLevel, mapping);
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

        // ── Post-process: merge adjacent hiragana-only + kanji+meaning pairs ──
        // Common in Vietnamese-edition textbooks where hiragana and kanji are
        // on separate rows:  かんこく | (empty)  →  韓国 | Hàn Quốc
        result = mergeHiraganaKanjiPairs(result);

        // If fewer than half the rows parsed successfully, the table structure
        // is probably incorrect — return empty so caller uses LLM fallback.
        if (result.size() < rows.size() / 2.0 && !result.isEmpty()) {
            log.info("MnNTableParser: low parse rate ({}/{}) — discarding, using LLM fallback",
                    result.size(), rows.size());
            return List.of();
        }

        return result;
    }

    /**
     * Merge adjacent vocabulary items where one is hiragana-only (no meaning)
     * and the next is kanji+meaning for the same word.
     *
     * <p>Pattern in Vietnamese-edition textbooks:
     * <pre>
     *   かんこく |         ← pure kana, empty or same-as-word meaning
     *   韓国     | Hàn Quốc ← kanji form with actual meaning
     * </pre>
     * Result: word=韓国, reading=かんこく, meaning=Hàn Quốc
     */
    private List<ExtractionResult.ExtractedVocabulary> mergeHiraganaKanjiPairs(
            List<ExtractionResult.ExtractedVocabulary> items) {
        if (items.size() < 2) return items;

        List<ExtractionResult.ExtractedVocabulary> merged = new ArrayList<>();
        int i = 0;

        while (i < items.size()) {
            ExtractionResult.ExtractedVocabulary current = items.get(i);

            if (i + 1 < items.size()) {
                ExtractionResult.ExtractedVocabulary next = items.get(i + 1);

                // Check if current is pure-kana with no useful meaning
                // and next has kanji with a real meaning.
                // The next item MUST have a non-empty meaning — otherwise both are
                // broken items (e.g. from a combined table with wrong column mapping)
                // and merging would produce nonsense readings.
                String curWord = current.getJapaneseText();
                String nextWord = next.getJapaneseText();
                String nextMeaning = next.getMeaningVi();
                boolean curIsKana = curWord != null && PURE_KANA.matcher(curWord.trim()).matches();
                boolean nextHasKanji = nextWord != null && !PURE_KANA.matcher(nextWord.trim()).matches()
                        && CJK_OR_KANA.matcher(nextWord).find();
                boolean nextHasMeaning = nextMeaning != null && !nextMeaning.isBlank();
                boolean curMeaningEmpty = current.getMeaningVi() == null
                        || current.getMeaningVi().isBlank()
                        || current.getMeaningVi().equals(curWord); // meaning = word itself

                if (curIsKana && nextHasKanji && curMeaningEmpty && nextHasMeaning) {
                    // Merge: use next's word/meaning, set current's kana as reading
                    if (next.getReading() == null || next.getReading().isBlank()) {
                        next.setReading(curWord.trim());
                    }
                    merged.add(next);
                    i += 2; // skip both
                    log.debug("MnNTableParser: merged kana '{}' + kanji '{}' → reading='{}'",
                            curWord, nextWord, curWord);
                    continue;
                }
            }

            merged.add(current);
            i++;
        }

        if (merged.size() < items.size()) {
            log.debug("MnNTableParser: merged {} hiragana/kanji pairs ({} → {} items)",
                    items.size() - merged.size(), items.size(), merged.size());
        }
        return merged;
    }

    // ── Row parsing ──────────────────────────────────────────────────────────

    private ExtractionResult.ExtractedVocabulary parseRow(
            String[] cells, String jlptLevel, ColumnMapping mapping) {

        // ── Word column ─────────────────────────────────────────────────────
        if (mapping.wordCol >= cells.length) return null;
        String rawJapanese = cells[mapping.wordCol].trim();

        // ── Reading column (read early — may be needed as word fallback) ──
        String reading = "";
        if (mapping.readingCol >= 0 && mapping.readingCol < cells.length) {
            reading = cells[mapping.readingCol].trim();
        }

        // ── Split "kana space kanji" within a single cell ──────────────────
        // OCR sometimes produces "かんこく 韓国" in one cell instead of
        // separate columns. Split into reading=かんこく, word=韓国.
        var kanaKanjiMatch = KANA_SPACE_KANJI.matcher(rawJapanese);
        if (kanaKanjiMatch.matches()) {
            String kanaPart = kanaKanjiMatch.group(1).trim();
            String kanjiPart = kanaKanjiMatch.group(2).trim();
            if (reading.isBlank()) {
                reading = kanaPart;
            }
            rawJapanese = kanjiPart;
        }

        // When the kanji/word column is empty but the reading (kana) column
        // has content, use the kana as the word. Common in tables like:
        //   わたし | (empty) | tôi       ← wordCol empty, reading has kana
        //   みなさん | 皆さん | ...       ← wordCol has kanji, reading has kana
        if ((rawJapanese.isBlank() || !CJK_OR_KANA.matcher(rawJapanese).find())
                && !reading.isBlank() && CJK_OR_KANA.matcher(reading).find()) {
            rawJapanese = reading;
            reading = ""; // kana is now the word, no separate reading
        }

        if (rawJapanese.isBlank() || !CJK_OR_KANA.matcher(rawJapanese).find()) return null;

        // Skip serial-number cells that ended up in the word column
        if (DIGITS_ONLY.matcher(rawJapanese).matches()) return null;

        // Strip inline meaning: 行きます（đi）→ word=行きます, meaning=đi
        FuriganaNormalizer.InlineMeaning inlineResult = FuriganaNormalizer.extractInlineMeaning(rawJapanese);
        String japanese          = inlineResult.found() ? inlineResult.word() : rawJapanese;
        String col1InlineMeaning = inlineResult.found() ? inlineResult.meaning() : null;

        if (japanese.isBlank() || !CJK_OR_KANA.matcher(japanese).find()) return null;

        // ── Romaji column ───────────────────────────────────────────────────
        String romaji = "";
        if (mapping.romajiCol >= 0 && mapping.romajiCol < cells.length) {
            romaji = cells[mapping.romajiCol].trim();
        }

        // If reading column actually has romaji, swap
        if (!reading.isBlank() && ROMAJI.matcher(reading).matches()) {
            if (romaji.isBlank()) romaji = reading;
            reading = "";
        }

        // ── Meaning column ──────────────────────────────────────────────────
        String meaningRaw   = mapping.meaningCol < cells.length ? cells[mapping.meaningCol].trim() : "";
        String partOfSpeech = "";
        String meaningVi    = meaningRaw;

        if (!meaningRaw.isBlank()) {
            var m = POS_TAG.matcher(meaningRaw);
            if (m.matches()) {
                partOfSpeech = normalizePOS(m.group(1).trim());
                meaningVi    = m.group(2).trim();
            }
        }

        // Fallback: if meaning column is blank, use inline meaning from word cell
        if (meaningVi.isBlank() && col1InlineMeaning != null) {
            var inlineM = POS_TAG.matcher(col1InlineMeaning);
            if (inlineM.matches()) {
                partOfSpeech = normalizePOS(inlineM.group(1).trim());
                meaningVi    = inlineM.group(2).trim();
            } else {
                meaningVi = col1InlineMeaning;
            }
        }

        if (japanese.isBlank()) return null;

        // If the detected meaning cell for this specific row contains Japanese text,
        // the column detection was right globally but wrong for this row (e.g. a kanji
        // variant cell ended up in the meaning slot).  Try every remaining cell for one
        // that has Latin/Vietnamese letters and no Japanese characters before giving up.
        //
        // Exception: Japanese inside「」brackets is part of the Vietnamese explanation
        // (e.g. "người kia (「あのかた」là cách nói lịch sự...)") — strip these before checking.
        String meaningWithoutBrackets = JAPANESE_IN_BRACKETS.matcher(meaningVi).replaceAll("");
        // If the remaining text (after stripping brackets and tilde-suffixes) still
        // contains Japanese, check if the meaning is PREDOMINANTLY Vietnamese.
        // Vietnamese meanings like "thay cho ～さん" or "ví dụ IMCの しゃいん" have
        // more Latin characters than Japanese — these are valid explanations.
        boolean japaneseInMeaning = JAPANESE_IN_MEANING.matcher(meaningWithoutBrackets).find();
        if (japaneseInMeaning) {
            long latinChars = meaningWithoutBrackets.chars()
                    .filter(c -> Character.isLetter(c) && c < 0x3000).count();
            long jpChars = meaningWithoutBrackets.chars()
                    .filter(c -> (c >= 0x3040 && c <= 0x30FF) || (c >= 0x4E00 && c <= 0x9FFF)).count();
            if (latinChars > jpChars) {
                japaneseInMeaning = false; // predominantly Vietnamese — keep the meaning
            }
        }
        if (!meaningVi.isBlank() && japaneseInMeaning) {
            log.debug("MnNTableParser: meaningVi '{}' contains Japanese — scanning other cells for word '{}'",
                    meaningVi, japanese);
            String fallback = "";
            for (int i = 0; i < cells.length; i++) {
                if (i == mapping.wordCol || i == mapping.readingCol
                        || i == mapping.romajiCol || i == mapping.meaningCol) continue;
                String cell = cells[i].trim();
                if (!cell.isBlank() && !JAPANESE_IN_MEANING.matcher(cell).find() && hasAnyLetter(cell)) {
                    fallback = cell;
                    break;
                }
            }
            meaningVi = fallback; // may be empty — row still kept, LLM will fill meaning
        }

        // ── Skip rows with no useful Vietnamese meaning ────────────────────
        // After all fallback attempts, if meaningVi is still empty or equals the
        // Japanese word itself, the row has no translation → skip it.
        // This filters out country-name-only rows, institution reference data, etc.
        if (meaningVi.isBlank() || meaningVi.equals(japanese) || meaningVi.equals(rawJapanese)) {
            return null;
        }

        // ── Example column — any remaining CJK column ──────────────────────
        List<java.util.Map<String, String>> examples = new ArrayList<>();
        for (int i = 0; i < cells.length; i++) {
            if (i == mapping.wordCol || i == mapping.readingCol
                    || i == mapping.meaningCol || i == mapping.romajiCol) continue;
            String cell = cells[i].trim();
            if (!cell.isBlank() && CJK_OR_KANA.matcher(cell).find()
                    && !DIGITS_ONLY.matcher(cell).matches()) {
                examples.add(java.util.Map.of("jp", cell, "vi", "", "en", ""));
                break; // only take the first example column
            }
        }

        String meaningSource = (meaningRaw.isBlank() && col1InlineMeaning != null)
                ? "SOURCE_INLINE"
                : "SOURCE";

        return ExtractionResult.ExtractedVocabulary.builder()
                .japaneseText(japanese)
                .kanjiWriting(CJK_OR_KANA.matcher(japanese).find() ? japanese : null)
                .reading(PURE_KANA.matcher(reading).matches() ? reading : "")
                .romaji(romaji)
                .meaningVi(meaningVi)
                .meaningEn(null)
                .partOfSpeech(partOfSpeech.isBlank() ? "noun" : partOfSpeech)
                .jlptLevel(jlptLevel != null ? jlptLevel : "N5")
                .exampleSentences(examples)
                .isKeyWord(false)
                .meaningSource(meaningSource)
                .build();
    }

    // ── POS tag normalisation ────────────────────────────────────────────────

    /**
     * Map Vietnamese POS abbreviations to English POS strings expected by the
     * vocabulary entity. Works for any Vietnamese-edition Japanese textbook.
     */
    private String normalizePOS(String raw) {
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

package com.fu.curriculumimport.service.ingestion;

import com.fu.curriculumimport.service.agent.ExtractionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses vocabulary from markdown tables in Document AI / Gemini output.
 *
 * <h3>Table type classification</h3>
 * Before attempting to parse, the table is classified:
 * <ul>
 *   <li><b>STANDARD_VOCAB</b> — 2-3 column tables with reading/kanji/meaning → parsed directly (0 LLM)</li>
 *   <li><b>COUNTRY_TABLE</b> — multi-column reference tables (country/nationality/language) → delegate to LLM</li>
 *   <li><b>REFERENCE_GRID</b> — numbered grids (surnames, counters) → skip entirely</li>
 * </ul>
 *
 * <p>Standard vocab rows are converted to tab-separated format and delegated
 * to {@link MnNTableParser} for the actual column detection and parsing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarkdownVocabParser {

    private final MnNTableParser mnNTableParser;

    /** Matches markdown table separator rows like |---|---|---| */
    private static final Pattern SEPARATOR_ROW = Pattern.compile("^\\|[\\s\\-:]+\\|$");

    /** Keywords in header row indicating country/nationality/language reference tables. */
    private static final Pattern COUNTRY_TABLE_HEADER = Pattern.compile(
            "(?i)国|人|ことば|くに|Nước|Người|Ngôn ngữ|Country|Nationality|Language"
    );

    /** Numbered grid pattern: |number|kanji|number|kanji| */
    private static final Pattern REFERENCE_GRID_ROW = Pattern.compile(
            "^\\|\\s*\\d+\\s*\\|[^|]+\\|\\s*\\d+\\s*\\|[^|]+\\|"
    );

    /** Cross-reference notation ⌈...⌋ used in MnN textbooks. */
    private static final Pattern CROSS_REF = Pattern.compile("⌈([^⌋]+)⌋");

    enum TableType {
        STANDARD_VOCAB,
        COUNTRY_TABLE,
        REFERENCE_GRID
    }

    /**
     * Attempt to parse vocabulary from markdown table content.
     *
     * @param content   chunk content potentially containing markdown tables
     * @param jlptLevel JLPT level tag
     * @return parsed vocabulary list; empty if no standard vocab tables found
     *         (caller should fall through to LLM)
     */
    public List<ExtractionResult.ExtractedVocabulary> parse(String content, String jlptLevel) {
        if (content == null || content.isBlank()) return List.of();

        List<List<String>> tables = extractMarkdownTables(content);
        if (tables.isEmpty()) return List.of();

        List<ExtractionResult.ExtractedVocabulary> allVocab = new ArrayList<>();

        for (List<String> table : tables) {
            TableType type = classifyTable(table);

            switch (type) {
                case STANDARD_VOCAB -> {
                    List<String> tsvRows = convertToTsv(table);
                    List<ExtractionResult.ExtractedVocabulary> parsed =
                            mnNTableParser.parse(tsvRows, jlptLevel);

                    // Post-process: handle ⌈⌋ cross-references
                    for (ExtractionResult.ExtractedVocabulary v : parsed) {
                        extractCrossReference(v);
                    }
                    allVocab.addAll(parsed);
                    log.debug("MarkdownVocabParser: STANDARD_VOCAB table → {} items", parsed.size());
                }
                case COUNTRY_TABLE -> {
                    log.debug("MarkdownVocabParser: COUNTRY_TABLE detected — skipping (LLM handles complex tables)");
                    // Skip this table — don't discard previously parsed standard vocab tables.
                    // The LLM path in VocabularyExtractionAgent will extract country data
                    // if the overall markdown parse returns empty (no standard vocab found).
                }
                case REFERENCE_GRID -> {
                    log.debug("MarkdownVocabParser: REFERENCE_GRID detected — skipping (not vocabulary)");
                    // Skip this table, continue to check others
                }
            }
        }

        return allVocab;
    }

    /**
     * Extract all markdown tables from content.
     * A table is a contiguous block of lines starting with '|'.
     */
    private List<List<String>> extractMarkdownTables(String content) {
        List<List<String>> tables = new ArrayList<>();
        List<String> currentTable = null;
        int currentColCount = 0;

        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                // Skip separator rows (|---|---|)
                if (SEPARATOR_ROW.matcher(trimmed).matches()) continue;

                int colCount = countPipeCols(trimmed);

                // Split the table when column count changes significantly.
                // This prevents 3-col vocab tables from being merged with adjacent
                // 2-col country/location tables (which causes misaligned column mapping).
                if (currentTable != null && !currentTable.isEmpty()
                        && colCount != currentColCount) {
                    if (currentTable.size() >= 2) {
                        tables.add(currentTable);
                    }
                    currentTable = new ArrayList<>();
                    currentColCount = colCount;
                }

                if (currentTable == null) {
                    currentTable = new ArrayList<>();
                    currentColCount = colCount;
                }
                currentTable.add(trimmed);
            } else {
                if (currentTable != null && currentTable.size() >= 2) {
                    tables.add(currentTable);
                }
                currentTable = null;
                currentColCount = 0;
            }
        }

        // Don't forget the last table
        if (currentTable != null && currentTable.size() >= 2) {
            tables.add(currentTable);
        }

        return tables;
    }

    /**
     * Count the number of data columns in a markdown table row.
     * {@code | a | b | c |} → 3 columns.
     */
    private int countPipeCols(String row) {
        // Count pipe characters; columns = pipes - 1 (leading + trailing pipes)
        long pipes = row.chars().filter(c -> c == '|').count();
        return Math.max(1, (int) pipes - 1);
    }

    /**
     * Classify a markdown table by examining its header and data rows.
     */
    private TableType classifyTable(List<String> rows) {
        if (rows.isEmpty()) return TableType.REFERENCE_GRID;

        // Check header row for country/nationality/language keywords
        String headerRow = rows.get(0);
        if (COUNTRY_TABLE_HEADER.matcher(headerRow).find()) {
            return TableType.COUNTRY_TABLE;
        }

        // Check data rows for numbered grid pattern
        int gridMatchCount = 0;
        for (int i = 1; i < Math.min(rows.size(), 4); i++) {
            if (REFERENCE_GRID_ROW.matcher(rows.get(i)).matches()) {
                gridMatchCount++;
            }
        }
        if (gridMatchCount > 0 && gridMatchCount >= (Math.min(rows.size() - 1, 3)) / 2.0) {
            return TableType.REFERENCE_GRID;
        }

        return TableType.STANDARD_VOCAB;
    }

    /**
     * Convert markdown table rows to tab-separated format for MnNTableParser.
     * Strips leading/trailing pipes and converts inner pipes to tabs.
     */
    private List<String> convertToTsv(List<String> rows) {
        List<String> tsvRows = new ArrayList<>(rows.size());

        // Skip the first row if it looks like a header (all non-CJK text)
        int startIdx = 0;
        if (!rows.isEmpty()) {
            String first = rows.get(0).replaceAll("\\|", "").trim();
            boolean hasJapanese = first.chars().anyMatch(c ->
                    (c >= 0x3040 && c <= 0x9FFF));
            if (!hasJapanese && rows.size() > 1) {
                startIdx = 1; // Skip header row
            }
        }

        for (int i = startIdx; i < rows.size(); i++) {
            String row = rows.get(i);
            // Remove leading and trailing pipes, then split on inner pipes
            row = row.replaceAll("^\\|", "").replaceAll("\\|$", "");
            String tsv = row.replace("|", "\t").trim();
            if (!tsv.isBlank()) {
                tsvRows.add(tsv);
            }
        }
        return tsvRows;
    }

    /**
     * Extract ⌈...⌋ cross-reference notation into usageNote field.
     * E.g. "phía này (cách nói lịch sự của ⌈ここ⌋)" →
     *   meaningVi = "phía này (cách nói lịch sự của ここ)"
     *   usageNote = "cf. ここ"
     */
    private void extractCrossReference(ExtractionResult.ExtractedVocabulary vocab) {
        String meaning = vocab.getMeaningVi();
        if (meaning == null) return;

        var matcher = CROSS_REF.matcher(meaning);
        if (matcher.find()) {
            String ref = matcher.group(1);
            // Clean the brackets from meaning text
            vocab.setMeaningVi(meaning.replace("⌈" + ref + "⌋", ref));
            // Add cross-reference to usage note
            String existing = vocab.getUsageNote();
            String note = "cf. " + ref;
            vocab.setUsageNote(existing != null && !existing.isBlank()
                    ? existing + "; " + note : note);
        }
    }
}

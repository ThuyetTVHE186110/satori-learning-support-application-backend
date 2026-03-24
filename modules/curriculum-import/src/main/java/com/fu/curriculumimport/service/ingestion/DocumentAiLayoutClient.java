package com.fu.curriculumimport.service.ingestion;

import com.fu.curriculumimport.config.OcrProperties;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.retrying.RetrySettings;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.documentai.v1beta3.Document;
import com.google.cloud.documentai.v1beta3.DocumentProcessorServiceClient;
import com.google.cloud.documentai.v1beta3.DocumentProcessorServiceSettings;
import com.google.cloud.documentai.v1beta3.ProcessOptions;
import com.google.cloud.documentai.v1beta3.ProcessRequest;
import com.google.cloud.documentai.v1beta3.ProcessResponse;
import com.google.cloud.documentai.v1beta3.RawDocument;
import com.google.protobuf.ByteString;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * OCR Tier 0 — Google Cloud Document AI (v1beta3 API).
 *
 * <p>Supports both <b>OCR</b> and <b>Layout Parser</b> processor types via the standard
 * {@code document.text} + {@code document.pages[]} response fields, which are populated
 * by any Document AI processor:
 * <ul>
 *   <li>{@code document.text} — full OCR text for the entire document</li>
 *   <li>{@code document.pages[i].layout.textAnchor} — per-page text offset range</li>
 *   <li>{@code document.pages[i].tables[]} — structured table data with cell-level
 *       text anchors, giving {@link MnNTableParser} clean cell values</li>
 * </ul>
 *
 * <h3>Activation</h3>
 * <ol>
 *   <li>Create a <b>Layout Parser</b> processor in Google Cloud Console → Document AI.</li>
 *   <li>Note the processor ID (e.g. {@code abc123def456}).</li>
 *   <li>Set in {@code application.yml}:
 *     <pre>
 *     curriculum-import.ocr.document-ai-enabled: true
 *     curriculum-import.ocr.document-ai-project-id: 703548005421   # numeric project number
 *     curriculum-import.ocr.document-ai-location: us               # or eu
 *     curriculum-import.ocr.document-ai-processor-id: abc123def456
 *     </pre>
 *   </li>
 * </ol>
 *
 * <h3>Fallback</h3>
 * <p>If Document AI is disabled, unavailable, or returns an empty result,
 * {@link OcrClient} automatically falls back to {@link GoogleVisionOcrClient}.
 *
 * <h3>Pricing note</h3>
 * <p>Document AI Layout Parser costs ~$10 / 1 000 pages (vs $1.50 for Vision).
 * Enable selectively for documents expected to contain vocabulary tables.
 */
@Slf4j
@Component
public class DocumentAiLayoutClient {

    /** Document AI inline upload limit (20 MB per request). */
    private static final int MAX_PDF_BYTES = 20 * 1024 * 1024;

    // ── Section-hint patterns (matched against heading text from layout blocks) ──

    private static final Pattern VOCAB_HEADING = Pattern.compile(
            "(?i)từ\\s*vựng|単語|語彙|ごい|vocabulary|voca");
    private static final Pattern GRAMMAR_PATTERN_HEADING = Pattern.compile(
            "(?i)mẫu\\s*câu|văn\\s*mẫu|文型|ぶんけい|grammar\\s*pattern");
    private static final Pattern GRAMMAR_EXPLANATION_HEADING = Pattern.compile(
            "(?i)ngữ\\s*pháp|文法|ぶんぽう|giải\\s*thích|grammar\\s*note");
    private static final Pattern DIALOG_HEADING = Pattern.compile(
            "(?i)hội\\s*thoại|会話|かいわ|dialog|dialogue");
    private static final Pattern EXERCISE_HEADING = Pattern.compile(
            "(?i)luyện\\s*tập|bài\\s*tập|練習|れんしゅう|exercise|drill");

    /** Document AI Layout Parser page limit per inline request. */
    private static final int MAX_PAGES_PER_REQUEST = 15;

    private final boolean enabled;
    private final String processorName;
    private final DocumentProcessorServiceClient client;

    public DocumentAiLayoutClient(OcrProperties props) {
        this.enabled = props.isDocumentAiEnabled();

        if (!enabled) {
            this.processorName = "";
            this.client = null;
            log.info("Document AI disabled (document-ai-enabled=false)");
            return;
        }

        this.processorName = String.format(
                "projects/%s/locations/%s/processors/%s",
                props.getDocumentAiProjectId(),
                props.getDocumentAiLocation(),
                props.getDocumentAiProcessorId());

        // Regional endpoint — Document AI has no global endpoint.
        String endpoint = props.getDocumentAiLocation() + "-documentai.googleapis.com:443";

        DocumentProcessorServiceClient builtClient = null;
        try {
            DocumentProcessorServiceSettings.Builder settingsBuilder =
                    DocumentProcessorServiceSettings.newBuilder().setEndpoint(endpoint);

            String credFile = props.getCredentialsFile();
            if (credFile != null && !credFile.isBlank()) {
                try (InputStream is = new FileInputStream(credFile)) {
                    GoogleCredentials creds = GoogleCredentials.fromStream(is)
                            .createScoped("https://www.googleapis.com/auth/cloud-platform");
                    settingsBuilder.setCredentialsProvider(FixedCredentialsProvider.create(creds));
                }
            }

            // Extend the gRPC deadline — the default ~60 s is too short for OCR of a
            // full scanned PDF.  No retries: re-uploading a large PDF is expensive;
            // the fallback to Vision happens immediately instead.
            Duration timeout = Duration.ofMinutes(props.getDocumentAiTimeoutMinutes());
            RetrySettings noRetryLongTimeout = RetrySettings.newBuilder()
                    .setInitialRpcTimeoutDuration(timeout)
                    .setRpcTimeoutMultiplier(1.0)
                    .setMaxRpcTimeoutDuration(timeout)
                    .setTotalTimeoutDuration(timeout)
                    .setMaxAttempts(1)
                    .build();
            settingsBuilder.processDocumentSettings().setRetrySettings(noRetryLongTimeout);

            builtClient = DocumentProcessorServiceClient.create(settingsBuilder.build());
            log.info("Document AI client initialised (processor: {}, endpoint: {})",
                    processorName, endpoint);
        } catch (Exception e) {
            log.warn("Document AI client init failed: {} — Tier 0 disabled, will use Vision OCR",
                    e.getMessage());
        }
        this.client = builtClient;
    }

    /** Returns true if the client is available and Document AI is enabled. */
    public boolean isAvailable() {
        return enabled && client != null;
    }

    /**
     * Process a complete PDF document through Document AI Layout Parser.
     *
     * <h3>Strategy: original-first, split-fallback</h3>
     * <p>PDFBox {@link Splitter} can corrupt embedded image XObjects in scanned PDFs,
     * causing Document AI to return far fewer layout blocks on split copies than on the
     * original file. Therefore this method <b>always tries sending the original PDF
     * bytes first</b> when they are within the 20 MB inline limit. Only truly large
     * PDFs that exceed the size limit are split into sub-PDFs as a fallback.
     *
     * @param pdfBytes raw bytes of the original PDF file
     * @return one {@link OcrTextResult} per page, in page order;
     *         an empty list when Document AI is unavailable or every batch fails
     */
    public List<OcrTextResult> processDocument(byte[] pdfBytes) {
        if (!isAvailable()) return List.of();

        try (PDDocument fullDoc = Loader.loadPDF(new RandomAccessReadBuffer(pdfBytes))) {
            int totalPages = fullDoc.getNumberOfPages();

            // ── Fast path: send original PDF bytes directly (avoids splitting artifacts) ──
            if (pdfBytes.length <= MAX_PDF_BYTES) {
                log.debug("Document AI: sending full original PDF ({} pages, {} KB)",
                        totalPages, pdfBytes.length / 1024);
                List<OcrTextResult> results = processBatch(pdfBytes, 1, totalPages);
                if (!results.isEmpty()) {
                    // Pad if Document AI returned fewer pages than expected
                    while (results.size() < totalPages) {
                        results.add(OcrTextResult.empty());
                    }
                    return results;
                }
                log.warn("Document AI full-PDF call returned empty — falling back to page-range splitting");
            }

            // ── Fallback: split into sub-PDFs for oversized documents ────────────
            List<OcrTextResult> allResults = new ArrayList<>(totalPages);

            for (int start = 0; start < totalPages; start += MAX_PAGES_PER_REQUEST) {
                int end = Math.min(start + MAX_PAGES_PER_REQUEST, totalPages);
                byte[] batchPdfBytes = extractPageRange(fullDoc, start, end);

                if (batchPdfBytes.length > MAX_PDF_BYTES) {
                    log.warn("Document AI batch pages {}-{} exceeds 20 MB ({} MB) — skipping, Vision fallback will handle",
                            start + 1, end, batchPdfBytes.length / (1024 * 1024));
                    for (int i = start; i < end; i++) allResults.add(OcrTextResult.empty());
                    continue;
                }

                log.debug("Document AI: sending pages {}-{} of {} ({} KB)",
                        start + 1, end, totalPages, batchPdfBytes.length / 1024);
                List<OcrTextResult> batchResults = processBatch(batchPdfBytes, start + 1, end);
                allResults.addAll(batchResults);
            }

            return allResults;

        } catch (Exception e) {
            log.warn("Document AI processDocument failed (will fallback to Vision): {}", e.getMessage());
            log.debug("Document AI stack trace:", e);
            return List.of();
        }
    }

    /**
     * Send a PDF to Document AI and return per-page results.
     *
     * <p>Supports <b>two response formats</b> depending on the processor type:
     * <ol>
     *   <li><b>OCR processor</b>: {@code document.text} + {@code document.pages[]} —
     *       full OCR text with per-page text anchors and structured tables.</li>
     *   <li><b>Layout Parser</b>: {@code document.documentLayout.blocks} —
     *       semantic structural blocks grouped by {@code pageSpan}.</li>
     * </ol>
     * The method tries the OCR format first; if {@code document.text} is empty,
     * falls back to the Layout Parser format.
     */
    private List<OcrTextResult> processBatch(byte[] batchPdfBytes, int logPageStart, int logPageEnd) {
        try {
            RawDocument rawDocument = RawDocument.newBuilder()
                    .setContent(ByteString.copyFrom(batchPdfBytes))
                    .setMimeType("application/pdf")
                    .build();

            ProcessRequest request = ProcessRequest.newBuilder()
                    .setName(processorName)
                    .setRawDocument(rawDocument)
                    .setProcessOptions(ProcessOptions.newBuilder()
                            .setLayoutConfig(ProcessOptions.LayoutConfig.newBuilder()
                                    .setEnableTableAnnotation(true)
                                    .setEnableImageAnnotation(false)
                                    .build())
                            .build())
                    .build();

            ProcessResponse response = client.processDocument(request);
            Document doc = response.getDocument();
            int batchSize = logPageEnd - logPageStart + 1;

            // ── Try OCR-processor format first: document.text + document.pages[] ──
            String fullText = doc.getText();
            int pageCount = doc.getPagesCount();
            int blockCount = doc.getDocumentLayout().getBlocksCount();

            log.debug("Document AI batch {}-{}: {} chars fullText, {} pages, {} layout blocks",
                    logPageStart, logPageEnd, fullText.length(), pageCount, blockCount);

            if (!fullText.isEmpty() && pageCount > 0) {
                log.debug("Document AI batch {}-{}: using OCR format (document.text + pages[])",
                        logPageStart, logPageEnd);
                return parseOcrFormat(doc, fullText, pageCount, batchSize, logPageStart);
            }

            // ── Fallback: Layout Parser format (documentLayout.blocks) ───────────
            if (blockCount > 0) {
                log.debug("Document AI batch {}-{}: using Layout Parser format (documentLayout.blocks)",
                        logPageStart, logPageEnd);
                return parseLayoutBlocks(doc, blockCount, batchSize, logPageStart);
            }

            log.warn("Document AI batch {}-{}: empty response (no text, no pages, no blocks)",
                    logPageStart, logPageEnd);
            return List.of();

        } catch (Exception e) {
            log.warn("Document AI batch {}-{} failed: {} — Vision will handle these pages",
                    logPageStart, logPageEnd, e.getMessage());
            return List.of();
        }
    }

    /** Parse response using OCR-processor format: document.text + document.pages[]. */
    private List<OcrTextResult> parseOcrFormat(Document doc, String fullText,
                                                int pageCount, int batchSize, int logPageStart) {
        List<OcrTextResult> results = new ArrayList<>(batchSize);
        for (int p = 0; p < batchSize; p++) {
            if (p >= pageCount) {
                results.add(OcrTextResult.empty());
                continue;
            }

            Document.Page page = doc.getPages(p);

            // Per-page text via textAnchor into document.text
            String pageText = "";
            if (page.hasLayout() && page.getLayout().hasTextAnchor()) {
                pageText = extractTextFromAnchor(fullText, page.getLayout().getTextAnchor());
            }
            if (pageText.isBlank() && page.getParagraphsCount() > 0) {
                StringBuilder sb = new StringBuilder();
                for (Document.Page.Paragraph para : page.getParagraphsList()) {
                    if (para.hasLayout() && para.getLayout().hasTextAnchor()) {
                        sb.append(extractTextFromAnchor(fullText, para.getLayout().getTextAnchor()));
                    }
                }
                pageText = sb.toString();
            }

            // Structured table rows
            List<String> tableRows = new ArrayList<>();
            for (Document.Page.Table table : page.getTablesList()) {
                extractTableRowsFromPage(fullText, table, tableRows);
            }

            String normalized = FuriganaNormalizer.normalize(pageText.trim());
            double confidence = tableRows.isEmpty() ? 0.92 : 0.98;
            results.add(new OcrTextResult(normalized, confidence, tableRows));

            log.debug("Document AI page {}/{} (overall {}): {} chars, {} table rows [OCR format]",
                    p + 1, batchSize, logPageStart + p,
                    normalized.length(), tableRows.size());
        }
        return results;
    }

    /**
     * Parse response using Layout Parser format: documentLayout.blocks grouped by pageSpan.
     *
     * <p>Layout Parser returns a <b>nested tree</b> of blocks: heading-1 blocks contain
     * heading-2 children, which contain heading-3 children, which may contain table blocks.
     * This method recursively flattens all leaf text and table blocks, grouping them by
     * their {@code pageSpan.pageStart} for per-page output.
     */
    private List<OcrTextResult> parseLayoutBlocks(Document doc, int blockCount,
                                                    int batchSize, int logPageStart) {
        Document.DocumentLayout layout = doc.getDocumentLayout();

        // Flatten the nested block tree into per-page lists of leaf blocks
        Map<Integer, StringBuilder> pageTextMap = new TreeMap<>();
        Map<Integer, List<String>> pageTableMap = new TreeMap<>();
        Map<Integer, String> pageSectionHints = new TreeMap<>();

        for (Document.DocumentLayout.DocumentLayoutBlock block : layout.getBlocksList()) {
            flattenBlock(block, pageTextMap, pageTableMap, pageSectionHints);
        }

        log.debug("Document AI Layout Parser: {} top-level blocks flattened across pages {}",
                blockCount, pageTextMap.keySet());

        List<OcrTextResult> results = new ArrayList<>(batchSize);
        for (int p = 1; p <= batchSize; p++) {
            StringBuilder pageText = pageTextMap.getOrDefault(p, new StringBuilder());
            List<String> tableRows = pageTableMap.getOrDefault(p, List.of());
            String sectionHint = pageSectionHints.get(p);

            // Table presence on a page without a heading hint → likely vocabulary
            if (sectionHint == null && !tableRows.isEmpty()) {
                sectionHint = MnNSectionSplitter.VOCABULARY;
            }

            String normalized = FuriganaNormalizer.normalize(pageText.toString().trim());
            double confidence = tableRows.isEmpty() ? 0.92 : 0.98;
            results.add(new OcrTextResult(normalized, confidence, tableRows, sectionHint));

            log.debug("Document AI page {}/{} (overall {}): {} chars, {} table rows, hint={} [Layout format]",
                    p, batchSize, logPageStart + p - 1,
                    normalized.length(), tableRows.size(), sectionHint);
        }
        return results;
    }

    /**
     * Recursively flatten a Layout Parser block tree into <b>markdown-formatted</b> text.
     *
     * <p>Heading blocks are emitted as {@code #}, {@code ##}, {@code ###} prefixes.
     * Table blocks are rendered as markdown tables (pipe-delimited) for rich LLM context,
     * while tab-separated rows are collected separately for {@link MnNTableParser} fast-path.
     * List entries become {@code - } bullet items.
     * Header/footer blocks are skipped.
     */
    private void flattenBlock(Document.DocumentLayout.DocumentLayoutBlock block,
                                Map<Integer, StringBuilder> pageTextMap,
                                Map<Integer, List<String>> pageTableMap,
                                Map<Integer, String> pageSectionHints) {
        int page = block.getPageSpan().getPageStart();

        if (block.hasTextBlock()) {
            Document.DocumentLayout.DocumentLayoutBlock.LayoutTextBlock textBlock = block.getTextBlock();
            String type = textBlock.getType();

            // Skip headers/footers (page headers like "Lophoctiengnhat.com", page numbers)
            if ("header".equals(type) || "footer".equals(type)) {
                return;
            }

            String text = textBlock.getText();
            if (text != null && !text.isBlank()) {
                StringBuilder sb = pageTextMap.computeIfAbsent(page, k -> new StringBuilder());
                String mdLine = toMarkdownLine(type, text);
                sb.append(mdLine).append("\n");

                // Derive section hint from heading text — first match wins per page
                if (type != null && type.startsWith("heading-") && !pageSectionHints.containsKey(page)) {
                    String hint = inferSectionHintFromHeading(text);
                    if (hint != null) {
                        pageSectionHints.put(page, hint);
                    }
                }
            }

            // Recurse into nested child blocks
            for (Document.DocumentLayout.DocumentLayoutBlock child : textBlock.getBlocksList()) {
                flattenBlock(child, pageTextMap, pageTableMap, pageSectionHints);
            }

        } else if (block.hasTableBlock()) {
            var tableBlock = block.getTableBlock();

            // 1) Tab-separated rows for MnNTableParser fast-path (vocabulary extraction)
            List<String> tableRows = pageTableMap.computeIfAbsent(page, k -> new ArrayList<>());
            extractTableRowsFromLayoutBlock(tableBlock, tableRows);

            // 2) Markdown table for rich content in chunk text (LLM extraction context)
            String mdTable = toMarkdownTable(tableBlock);
            if (!mdTable.isEmpty()) {
                pageTextMap.computeIfAbsent(page, k -> new StringBuilder())
                        .append("\n").append(mdTable).append("\n");
            }

        } else if (block.hasListBlock()) {
            // List blocks contain list entries, each entry has child blocks.
            // Document AI sometimes wraps heading-2 grammar sections inside list blocks —
            // we must respect the child's type instead of blindly prefixing "- ".
            for (var entry : block.getListBlock().getListEntriesList()) {
                for (Document.DocumentLayout.DocumentLayoutBlock child : entry.getBlocksList()) {
                    if (child.hasTextBlock()) {
                        Document.DocumentLayout.DocumentLayoutBlock.LayoutTextBlock childText = child.getTextBlock();
                        String itemText = childText.getText();
                        String childType = childText.getType();
                        if (itemText != null && !itemText.isBlank()) {
                            StringBuilder sb = pageTextMap.computeIfAbsent(page, k -> new StringBuilder());
                            // If the child is a heading (e.g. heading-2), render as markdown heading
                            // instead of a bullet — preserves grammar section structure.
                            if (childType != null && childType.startsWith("heading-")) {
                                sb.append(toMarkdownLine(childType, itemText)).append("\n");
                                // Also derive section hint from heading text
                                if (!pageSectionHints.containsKey(page)) {
                                    String hint = inferSectionHintFromHeading(itemText);
                                    if (hint != null) pageSectionHints.put(page, hint);
                                }
                            } else {
                                sb.append("- ").append(itemText.trim()).append("\n");
                            }
                        }
                        // Recurse deeper if the list item has nested children
                        for (Document.DocumentLayout.DocumentLayoutBlock nested : childText.getBlocksList()) {
                            flattenBlock(nested, pageTextMap, pageTableMap, pageSectionHints);
                        }
                    } else {
                        flattenBlock(child, pageTextMap, pageTableMap, pageSectionHints);
                    }
                }
            }
        }
    }

    /**
     * Map a heading text to a MnN section type constant.
     * Returns {@code null} when no match — caller applies table-based fallback.
     */
    private static String inferSectionHintFromHeading(String headingText) {
        if (VOCAB_HEADING.matcher(headingText).find())              return MnNSectionSplitter.VOCABULARY;
        if (GRAMMAR_PATTERN_HEADING.matcher(headingText).find())    return MnNSectionSplitter.GRAMMAR_PATTERN;
        if (GRAMMAR_EXPLANATION_HEADING.matcher(headingText).find()) return MnNSectionSplitter.GRAMMAR_EXPLANATION;
        if (DIALOG_HEADING.matcher(headingText).find())             return MnNSectionSplitter.DIALOG;
        if (EXERCISE_HEADING.matcher(headingText).find())           return MnNSectionSplitter.EXERCISE;
        return null;
    }

    /** Map a Layout Parser block type to a markdown-prefixed line. */
    private static String toMarkdownLine(String type, String text) {
        if (type == null) return text.trim();
        if (type.startsWith("heading-")) {
            int level = Character.getNumericValue(type.charAt(type.length() - 1));
            if (level >= 1 && level <= 6) {
                return "#".repeat(level) + " " + text.trim();
            }
        }
        return text.trim();   // paragraph, caption, etc.
    }

    /**
     * Convert a Layout Parser {@link Document.DocumentLayout.DocumentLayoutBlock.LayoutTableBlock}
     * to a markdown pipe-delimited table.
     *
     * <p>Example output:
     * <pre>
     * | おばさん (cô, bác gái) | おばあさん (bà) |
     * | いえ (nhà) | いいえ (không) |
     * </pre>
     */
    private static String toMarkdownTable(
            Document.DocumentLayout.DocumentLayoutBlock.LayoutTableBlock table) {
        StringBuilder md = new StringBuilder();
        int maxCols = 0;

        // Header rows
        for (var row : table.getHeaderRowsList()) {
            var cells = row.getCellsList();
            if (cells.isEmpty()) continue;
            maxCols = Math.max(maxCols, cells.size());
            md.append("| ");
            for (var cell : cells) {
                md.append(extractCellText(cell)).append(" | ");
            }
            md.append("\n");
        }

        // Separator row (only if we have headers)
        if (maxCols > 0) {
            md.append("|");
            for (int i = 0; i < maxCols; i++) md.append(" --- |");
            md.append("\n");
        }

        // Body rows
        for (var row : table.getBodyRowsList()) {
            var cells = row.getCellsList();
            if (cells.isEmpty()) continue;
            if (maxCols == 0) maxCols = cells.size(); // infer from first body row
            md.append("| ");
            for (var cell : cells) {
                md.append(extractCellText(cell)).append(" | ");
            }
            md.append("\n");
        }

        // Add separator after first body row if no header was present
        if (!table.getHeaderRowsList().isEmpty() || table.getBodyRowsCount() == 0) {
            return md.toString();
        }
        // Insert separator after first body row when there are no explicit headers
        String result = md.toString();
        int firstNewline = result.indexOf('\n');
        if (firstNewline > 0 && maxCols > 0) {
            StringBuilder sep = new StringBuilder("|");
            for (int i = 0; i < maxCols; i++) sep.append(" --- |");
            result = result.substring(0, firstNewline + 1) + sep + "\n" + result.substring(firstNewline + 1);
        }
        return result;
    }

    /** Extract plain text from a table cell's nested blocks. */
    private static String extractCellText(
            Document.DocumentLayout.DocumentLayoutBlock.LayoutTableCell cell) {
        StringBuilder sb = new StringBuilder();
        for (var block : cell.getBlocksList()) {
            if (block.hasTextBlock()) {
                String text = block.getTextBlock().getText().replace("\n", " ").trim();
                if (!text.isBlank()) {
                    if (!sb.isEmpty()) sb.append(" ");
                    sb.append(text);
                }
            }
        }
        return sb.toString().trim();
    }

    /**
     * Extract pages [startInclusive, endExclusive) from a PDDocument into a new PDF byte array.
     *
     * <p>Uses {@link Splitter} rather than {@code importPage()} because {@code importPage()}
     * copies only the page dictionary, not the embedded image XObjects. For scanned PDFs
     * every page content is an image XObject; without it Document AI sees structurally
     * valid but visually empty pages and returns 0 OCR results.
     * {@code Splitter} performs a full deep-copy including all referenced resources.
     */
    private byte[] extractPageRange(PDDocument source, int startInclusive, int endExclusive)
            throws Exception {
        Splitter splitter = new Splitter();
        splitter.setStartPage(startInclusive + 1);           // Splitter uses 1-based indexing
        splitter.setEndPage(endExclusive);                   // inclusive, 1-based
        splitter.setSplitAtPage(endExclusive - startInclusive); // produce one document for the range

        List<PDDocument> docs = splitter.split(source);
        if (docs.isEmpty()) {
            throw new IllegalStateException("Splitter returned no documents for pages "
                    + (startInclusive + 1) + "-" + endExclusive);
        }
        for (int i = 1; i < docs.size(); i++) {
            try { docs.get(i).close(); } catch (Exception ignored) {}
        }
        try (PDDocument sub = docs.get(0)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            sub.save(baos);
            return baos.toByteArray();
        }
    }

    /** Release the gRPC channel on Spring context shutdown. */
    @PreDestroy
    public void close() {
        if (client != null) {
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Extract a text span from {@code document.text} using a {@link Document.TextAnchor}.
     * Each text anchor contains one or more segments with start/end byte offsets.
     */
    private static String extractTextFromAnchor(String fullText, Document.TextAnchor anchor) {
        StringBuilder sb = new StringBuilder();
        for (Document.TextAnchor.TextSegment seg : anchor.getTextSegmentsList()) {
            long start = seg.getStartIndex();
            long end = seg.getEndIndex();
            if (start >= 0 && end <= fullText.length() && start < end) {
                sb.append(fullText, (int) start, (int) end);
            }
        }
        return sb.toString();
    }

    /**
     * Extract tab-separated body rows from a Layout Parser
     * {@link Document.DocumentLayout.DocumentLayoutBlock.LayoutTableBlock}.
     * Cell text comes from nested {@code textBlock.text} fields (no text anchors).
     */
    private static void extractTableRowsFromLayoutBlock(
            Document.DocumentLayout.DocumentLayoutBlock.LayoutTableBlock table,
            List<String> rows) {
        for (var row : table.getBodyRowsList()) {
            var cells = row.getCellsList();
            if (cells.size() < 2) continue;
            StringBuilder rowSb = new StringBuilder();
            for (var cell : cells) {
                if (!rowSb.isEmpty()) rowSb.append("\t");
                StringBuilder cellSb = new StringBuilder();
                for (var block : cell.getBlocksList()) {
                    if (block.hasTextBlock()) {
                        String text = block.getTextBlock().getText().replace("\n", " ").trim();
                        if (!text.isBlank()) {
                            if (!cellSb.isEmpty()) cellSb.append(" ");
                            cellSb.append(text);
                        }
                    }
                }
                rowSb.append(cellSb.toString().trim());
            }
            String rowStr = rowSb.toString().stripTrailing();
            if (!rowStr.isBlank() && rowStr.contains("\t")) {
                rows.add(rowStr);
            }
        }
    }

    /**
     * Extract tab-separated body rows from a {@link Document.Page.Table}.
     * Cell text is resolved via {@link Document.TextAnchor} offsets into
     * {@code document.text}, which works with both OCR and Layout Parser processors.
     */
    private static void extractTableRowsFromPage(String fullText,
                                                   Document.Page.Table table,
                                                   List<String> rows) {
        for (Document.Page.Table.TableRow row : table.getBodyRowsList()) {
            var cells = row.getCellsList();
            if (cells.size() < 2) continue; // single-cell rows are headings / footnotes

            StringBuilder rowSb = new StringBuilder();
            for (var cell : cells) {
                if (!rowSb.isEmpty()) rowSb.append("\t");
                String cellText = "";
                if (cell.hasLayout() && cell.getLayout().hasTextAnchor()) {
                    cellText = extractTextFromAnchor(fullText, cell.getLayout().getTextAnchor());
                }
                rowSb.append(cellText.replace("\n", " ").trim());
            }

            String rowStr = rowSb.toString().stripTrailing();
            if (!rowStr.isBlank() && rowStr.contains("\t")) {
                rows.add(rowStr);
            }
        }
    }
}

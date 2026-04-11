package com.fu.curriculumimport.service.ingestion;

import com.fu.curriculumimport.config.OcrProperties;
import com.fu.curriculumimport.exception.InvalidImportFileException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

/**
 * Unit tests for ScannedPdfIngestionService.
 *
 * Strategy: inject mocked OcrClient so OCR calls are fully controlled; use
 * real PDFBox to create in-memory blank PDFs so PDFRenderer has a genuine
 * document to render (no I/O required).
 *
 * Coverage:
 *
 * parse() — failure paths:
 *  - All pages produce empty OCR text         → InvalidImportFileException
 *  - getBytes() throws IOException            → InvalidImportFileException
 *
 * parse() — happy path:
 *  - Single-page PDF, OCR returns text        → 1 chunk, chunkType=SCANNED
 *  - Multi-page PDF, text stays < 2000 chars  → single chunk covering all pages
 *  - Multi-page PDF, text exceeds 2000 chars  → multiple chunks (sliding window)
 *  - Some pages produce empty OCR             → empty pages contribute no text
 *  - Low-confidence page (< minConfidence)    → still included in output
 *  - OcrTextResult.empty() for a page         → page contributes empty string
 *
 * parse() — chunk metadata:
 *  - chunkType always "SCANNED"
 *  - estimatedTokenCount = content.length / 4
 *  - chunkIndex is sequential from 0
 *  - pageStart / pageEnd span the correct pages
 *
 * parse() — DocumentParseResult:
 *  - totalPages == number of PDF pages
 *  - pageTexts.size() == totalPages
 *  - fullText is non-blank when OCR produces text
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ScannedPdfIngestionService")
class ScannedPdfIngestionServiceTest {

    @Mock private OcrProperties ocrProps;
    @Mock private OcrClient     ocrClient;

    @InjectMocks
    private ScannedPdfIngestionService service;

    // ── OCR response constants ──────────────────────────────────────────────
    private static final double HIGH_CONF  = 0.95;
    private static final double LOW_CONF   = 0.30; // below typical minConfidence=0.5

    /** A single OCR text line (~40 chars). */
    private static final String SHORT_OCR = "待合室　診察　処方箋　看護師　医師　病院";

    /**
     * ~608-char OCR line (76 chars × 8 repetitions).
     * With 4 pages: 608 × 4 + 8 (\n\n separators) = 2440 chars > 2000 threshold
     * → produces multiple chunks. Length is intentional and deterministic.
     */
    private static final String LONG_OCR =
            ("日本語の単語リスト：待合室、診察、処方箋、看護師、医師、病院、手術、入院、退院、検査、治療、薬局、診断、症状、" +
             "注射、体温、血圧、健康保険、救急車、緊急。").repeat(8);  // 76 × 8 = 608 chars

    // ── PDF builder helpers ─────────────────────────────────────────────────

    /** Creates a blank PDF with {@code pageCount} empty pages (no text layer). */
    private byte[] blankPdf(int pageCount) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
                doc.addPage(new PDPage());
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private MockMultipartFile wrapPdf(byte[] bytes) {
        return new MockMultipartFile(
                "file", "scan.pdf", "application/pdf", bytes);
    }

    /**
     * Helper: create batch OCR result with same OcrTextResult for all pages.
     */
    private OcrBatchResult batchResults(int pageCount, OcrTextResult result) {
        List<OcrTextResult> results = new java.util.ArrayList<>();
        for (int i = 0; i < pageCount; i++) {
            results.add(result);
        }
        return OcrBatchResult.of(results);
    }

    /**
     * Helper: create batch OCR result with specific results per page.
     */
    private OcrBatchResult batchResults(OcrTextResult... results) {
        return OcrBatchResult.of(java.util.Arrays.asList(results));
    }

    // ── Default mock setup ──────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        given(ocrProps.getRenderDpi()).willReturn(72);   // low DPI → fast rendering in tests
        given(ocrProps.getMinConfidence()).willReturn(0.5);
    }

    // =========================================================================
    // parse() — failure paths
    // =========================================================================

    @Nested
    @DisplayName("parse() — failure paths")
    class FailureTests {

        @Test
        @DisplayName("All pages produce empty OCR → InvalidImportFileException('no text')")
        void parse_allPagesEmptyOcr_throwsNoText() throws Exception {
            byte[] pdf = blankPdf(2);
            given(ocrClient.extractTextBatch(anyList(), any(byte[].class))).willReturn(batchResults(2, OcrTextResult.empty()));

            assertThatThrownBy(() -> service.parse(wrapPdf(pdf)))
                    .isInstanceOf(InvalidImportFileException.class)
                    .hasMessageContaining("no text");
        }

        @Test
        @DisplayName("getBytes() throws IOException → InvalidImportFileException")
        void parse_getBytesThrowsIOException_wrapsException() throws Exception {
            MultipartFile broken = mock(MultipartFile.class);
            given(broken.getOriginalFilename()).willReturn("scan.pdf");
            given(broken.getBytes()).willThrow(new IOException("disk failure"));

            assertThatThrownBy(() -> service.parse(broken))
                    .isInstanceOf(InvalidImportFileException.class)
                    .hasMessageContaining("Failed to read PDF bytes");
        }

        @Test
        @DisplayName("OcrClient returns empty for every page → throws even if there are 3 pages")
        void parse_threePagesAllEmpty_throws() throws Exception {
            byte[] pdf = blankPdf(3);
            given(ocrClient.extractTextBatch(anyList(), any(byte[].class))).willReturn(batchResults(3, OcrTextResult.empty()));

            assertThatThrownBy(() -> service.parse(wrapPdf(pdf)))
                    .isInstanceOf(InvalidImportFileException.class);
        }
    }

    // =========================================================================
    // parse() — single page happy path
    // =========================================================================

    @Nested
    @DisplayName("parse() — single-page OCR")
    class SinglePageTests {

        @Test
        @DisplayName("Single page, OCR returns short text → 1 chunk with chunkType=SCANNED")
        void parse_singlePageShortText_oneScannedChunk() throws Exception {
            byte[] pdf = blankPdf(1);
            given(ocrClient.extractTextBatch(anyList(), any(byte[].class))).willReturn(batchResults(1, new OcrTextResult(SHORT_OCR, HIGH_CONF)));

            DocumentParseResult result = service.parse(wrapPdf(pdf));

            assertThat(result.getTotalPages()).isEqualTo(1);
            assertThat(result.getChunks()).hasSize(1);
            assertThat(result.getChunks().get(0).getChunkType()).isEqualTo("SCANNED");
        }

        @Test
        @DisplayName("Single page → pageTexts.size() == 1")
        void parse_singlePage_pageTextsHasOneEntry() throws Exception {
            byte[] pdf = blankPdf(1);
            given(ocrClient.extractTextBatch(anyList(), any(byte[].class))).willReturn(batchResults(1, new OcrTextResult(SHORT_OCR, HIGH_CONF)));

            DocumentParseResult result = service.parse(wrapPdf(pdf));

            assertThat(result.getPageTexts()).hasSize(1);
            assertThat(result.getPageTexts().get(0)).isEqualTo(SHORT_OCR);
        }

        @Test
        @DisplayName("Single page → fullText is non-blank and contains OCR output")
        void parse_singlePage_fullTextContainsOcrOutput() throws Exception {
            byte[] pdf = blankPdf(1);
            given(ocrClient.extractTextBatch(anyList(), any(byte[].class))).willReturn(batchResults(1, new OcrTextResult(SHORT_OCR, HIGH_CONF)));

            DocumentParseResult result = service.parse(wrapPdf(pdf));

            assertThat(result.getFullText()).isNotBlank();
            assertThat(result.getFullText()).contains(SHORT_OCR);
        }

        @Test
        @DisplayName("Low-confidence page (< minConfidence) → still included in result")
        void parse_lowConfidencePage_stillIncluded() throws Exception {
            byte[] pdf = blankPdf(1);
            given(ocrClient.extractTextBatch(anyList(), any(byte[].class))).willReturn(batchResults(1, new OcrTextResult(SHORT_OCR, LOW_CONF)));

            DocumentParseResult result = service.parse(wrapPdf(pdf));

            // Low confidence just triggers a warning — content is still present
            assertThat(result.getChunks()).hasSize(1);
            assertThat(result.getChunks().get(0).getContent()).contains(SHORT_OCR);
        }
    }

    // =========================================================================
    // parse() — multi-page chunking
    // =========================================================================

    @Nested
    @DisplayName("parse() — multi-page chunking (sliding window)")
    class MultiPageChunkingTests {

        @Test
        @DisplayName("4 pages, combined text < 2000 chars → single chunk spanning all pages")
        void parse_fourPagesUnder2000Chars_singleChunkSpansAllPages() throws Exception {
            byte[] pdf = blankPdf(4);
            given(ocrClient.extractTextBatch(anyList(), any(byte[].class))).willReturn(batchResults(4, new OcrTextResult(SHORT_OCR, HIGH_CONF))); // ~40 chars × 4 = ~160 chars total

            DocumentParseResult result = service.parse(wrapPdf(pdf));

            assertThat(result.getTotalPages()).isEqualTo(4);
            assertThat(result.getChunks()).hasSize(1);

            DocumentChunk chunk = result.getChunks().get(0);
            assertThat(chunk.getPageStart()).isEqualTo(1);
            assertThat(chunk.getPageEnd()).isEqualTo(4);
        }

        @Test
        @DisplayName("4 pages, each ~630 chars → multiple chunks (total ~2520 chars > 2000 threshold)")
        void parse_fourPagesOver2000Chars_multipleChunks() throws Exception {
            byte[] pdf = blankPdf(4);
            // Each page returns LONG_OCR (~630 chars); 4 pages = ~2520 chars → should produce 2+ chunks
            given(ocrClient.extractTextBatch(anyList(), any(byte[].class))).willReturn(batchResults(4, new OcrTextResult(LONG_OCR, HIGH_CONF)));

            DocumentParseResult result = service.parse(wrapPdf(pdf));

            assertThat(result.getChunks().size()).isGreaterThan(1);
        }

        @Test
        @DisplayName("One page empty, one page with text → empty contributes empty string only")
        void parse_mixedEmptyAndTextPages_emptyPageContributesEmptyString() throws Exception {
            byte[] pdf = blankPdf(2);
            // extractTextBatch returns all pages at once - page 1 empty, page 2 has text
            given(ocrClient.extractTextBatch(anyList(), any(byte[].class))).willReturn(
                    batchResults(OcrTextResult.empty(), new OcrTextResult(SHORT_OCR, HIGH_CONF)));

            DocumentParseResult result = service.parse(wrapPdf(pdf));

            assertThat(result.getPageTexts()).hasSize(2);
            assertThat(result.getPageTexts().get(0)).isEmpty();
            assertThat(result.getPageTexts().get(1)).isEqualTo(SHORT_OCR);
        }

        @Test
        @DisplayName("Some empty, some text pages → fullText only contains non-empty pages")
        void parse_mixedPages_fullTextContainsOnlyNonEmptyContent() throws Exception {
            byte[] pdf = blankPdf(3);
            // extractTextBatch returns all 3 pages at once: page 1 empty, page 2 has text, page 3 empty
            given(ocrClient.extractTextBatch(anyList(), any(byte[].class))).willReturn(
                    batchResults(OcrTextResult.empty(), new OcrTextResult(SHORT_OCR, HIGH_CONF), OcrTextResult.empty()));

            DocumentParseResult result = service.parse(wrapPdf(pdf));

            assertThat(result.getFullText()).contains(SHORT_OCR);
            // Only the one non-empty page contributed to fullText
            assertThat(result.getFullText().strip()).isEqualTo(SHORT_OCR);
        }
    }

    // =========================================================================
    // parse() — chunk metadata correctness
    // =========================================================================

    @Nested
    @DisplayName("parse() — chunk metadata")
    class ChunkMetadataTests {

        @Test
        @DisplayName("chunkType is always 'SCANNED'")
        void parse_chunks_chunkTypeIsAlwaysScanned() throws Exception {
            byte[] pdf = blankPdf(2);
            given(ocrClient.extractTextBatch(anyList(), any(byte[].class))).willReturn(batchResults(2, new OcrTextResult(LONG_OCR, HIGH_CONF)));

            DocumentParseResult result = service.parse(wrapPdf(pdf));

            for (DocumentChunk chunk : result.getChunks()) {
                assertThat(chunk.getChunkType()).isEqualTo("SCANNED");
            }
        }

        @Test
        @DisplayName("estimatedTokenCount = content.length / 4")
        void parse_chunk_estimatedTokenCountIsContentLengthDiv4() throws Exception {
            byte[] pdf = blankPdf(1);
            given(ocrClient.extractTextBatch(anyList(), any(byte[].class))).willReturn(batchResults(1, new OcrTextResult(SHORT_OCR, HIGH_CONF)));

            DocumentParseResult result = service.parse(wrapPdf(pdf));
            DocumentChunk chunk = result.getChunks().get(0);

            assertThat(chunk.getEstimatedTokenCount())
                    .isEqualTo(chunk.getContent().length() / 4);
        }

        @Test
        @DisplayName("chunkIndex values are sequential starting from 0")
        void parse_multipleChunks_chunkIndicesAreSequential() throws Exception {
            byte[] pdf = blankPdf(5);
            given(ocrClient.extractTextBatch(anyList(), any(byte[].class))).willReturn(batchResults(5, new OcrTextResult(LONG_OCR, HIGH_CONF)));

            DocumentParseResult result = service.parse(wrapPdf(pdf));

            List<DocumentChunk> chunks = result.getChunks();
            for (int i = 0; i < chunks.size(); i++) {
                assertThat(chunks.get(i).getChunkIndex()).isEqualTo(i);
            }
        }

        @Test
        @DisplayName("pageStart >= 1 and pageEnd >= pageStart for all chunks")
        void parse_chunkPageBoundaries_areValid() throws Exception {
            byte[] pdf = blankPdf(4);
            given(ocrClient.extractTextBatch(anyList(), any(byte[].class))).willReturn(batchResults(4, new OcrTextResult(LONG_OCR, HIGH_CONF)));

            DocumentParseResult result = service.parse(wrapPdf(pdf));

            for (DocumentChunk chunk : result.getChunks()) {
                assertThat(chunk.getPageStart()).isGreaterThanOrEqualTo(1);
                assertThat(chunk.getPageEnd()).isGreaterThanOrEqualTo(chunk.getPageStart());
            }
        }

        @Test
        @DisplayName("chunk content is trimmed and non-blank")
        void parse_chunkContent_isTrimmedAndNonBlank() throws Exception {
            byte[] pdf = blankPdf(1);
            given(ocrClient.extractTextBatch(anyList(), any(byte[].class))).willReturn(batchResults(1, new OcrTextResult(SHORT_OCR, HIGH_CONF)));

            DocumentParseResult result = service.parse(wrapPdf(pdf));

            DocumentChunk chunk = result.getChunks().get(0);
            assertThat(chunk.getContent()).isNotBlank();
            assertThat(chunk.getContent()).isEqualTo(chunk.getContent().trim());
        }
    }

    // =========================================================================
    // parse() — OcrClient call verification
    // =========================================================================

    @Nested
    @DisplayName("parse() — OcrClient interaction")
    class OcrClientInteractionTests {

        @Test
        @DisplayName("OcrClient.extractTextBatch() is called once with all pages")
        void parse_extractTextBatchCalledOnce() throws Exception {
            byte[] pdf = blankPdf(3);
            given(ocrClient.extractTextBatch(anyList(), any(byte[].class))).willReturn(batchResults(3, new OcrTextResult(SHORT_OCR, HIGH_CONF)));

            service.parse(wrapPdf(pdf));

            then(ocrClient).should(times(1)).extractTextBatch(anyList(), any(byte[].class));
        }

        @Test
        @DisplayName("If first page empty but second page has text → no exception thrown")
        void parse_firstPageEmptySecondHasText_succeeds() throws Exception {
            byte[] pdf = blankPdf(2);
            given(ocrClient.extractTextBatch(anyList(), any(byte[].class))).willReturn(
                    batchResults(OcrTextResult.empty(), new OcrTextResult(SHORT_OCR, HIGH_CONF)));

            assertThatNoException().isThrownBy(() -> service.parse(wrapPdf(pdf)));
        }
    }
}

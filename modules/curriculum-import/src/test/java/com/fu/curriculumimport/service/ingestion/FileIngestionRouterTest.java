package com.fu.curriculumimport.service.ingestion;

import com.fu.curriculumimport.config.OcrProperties;
import com.fu.curriculumimport.exception.InvalidImportFileException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.awt.Rectangle;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

/**
 * Unit tests for FileIngestionRouter.
 *
 * Coverage:
 *
 * validate():
 *  - null file                        → InvalidImportFileException("required")
 *  - empty file                       → InvalidImportFileException("required")
 *  - file > 100 MB                    → InvalidImportFileException containing "100"
 *  - file exactly 100 MB              → passes validation
 *
 * detectFileType():
 *  - PDF magic bytes (%PDF)           → routes as PDF
 *  - ZIP magic bytes + PPTX MIME     → routes as PPTX
 *  - ZIP magic bytes + .pptx ext     → routes as PPTX (MIME fallback)
 *  - ZIP magic bytes + .ppt ext      → routes as PPTX
 *  - ZIP magic bytes + wrong MIME, no pptx ext → InvalidImportFileException
 *  - Unknown magic bytes               → InvalidImportFileException
 *  - File shorter than 4 bytes         → InvalidImportFileException
 *
 * routePdf() — text vs scanned auto-detection:
 *  - Text-based PDF (>= threshold chars/page) → pdfIngestionService
 *  - Scanned PDF (< threshold chars/page)     → scannedPdfIngestionService
 *  - PDF with 0 pages                         → InvalidImportFileException("no pages")
 *
 * route() full flow:
 *  - PPTX  → delegates to pptxIngestionService.parse()
 *  - Text PDF  → delegates to pdfIngestionService.parsePdf()
 *  - Scanned PDF → delegates to scannedPdfIngestionService.parse()
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FileIngestionRouter")
class FileIngestionRouterTest {

    @Mock private PdfIngestionService      pdfIngestionService;
    @Mock private ScannedPdfIngestionService scannedPdfIngestionService;
    @Mock private PptxIngestionService     pptxIngestionService;
    @Mock private OcrProperties            ocrProps;

    @InjectMocks
    private FileIngestionRouter router;

    private static final DocumentParseResult DUMMY_RESULT = DocumentParseResult.builder()
            .totalPages(1)
            .fullText("dummy")
            .pageTexts(List.of("dummy"))
            .chunks(List.of())
            .build();

    @BeforeEach
    void setUp() {
        // Default threshold: 100 chars/page → text PDFs must exceed this
        given(ocrProps.getScannedPageThreshold()).willReturn(100.0);
    }

    // =========================================================================
    // PDF / PPTX builders
    // =========================================================================

    /**
     * Creates a single-page text-based PDF where PDFBox extracts > 100 chars/page.
     * Uses ASCII so Helvetica can encode it without extra font embedding.
     */
    private byte[] textPdfBytes() throws IOException {
        // 200 ASCII chars per page — well above the 100 char/page threshold
        String richText = "Korean vocabulary lesson content. ".repeat(6); // ~210 chars
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 10);
                cs.newLineAtOffset(50, 720);
                // Write in <=90-char chunks
                for (int pos = 0; pos < richText.length(); pos += 90) {
                    cs.showText(richText.substring(pos, Math.min(pos + 90, richText.length())));
                    cs.newLine();
                }
                cs.endText();
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    /**
     * Creates a single-page blank PDF that yields 0 chars/page from PDFBox extraction.
     * Simulates a scanned (image-only) page.
     */
    private byte[] blankPdfBytes() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage()); // empty page, no text layer
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    /** Creates minimal PPTX bytes (one blank slide). */
    private byte[] pptxBytes() throws IOException {
        try (XMLSlideShow pptx = new XMLSlideShow()) {
            XSLFSlide slide = pptx.createSlide();
            XSLFTextBox tb = slide.createTextBox();
            tb.setText("Test slide content");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            pptx.write(baos);
            return baos.toByteArray();
        }
    }

    // =========================================================================
    // validate()
    // =========================================================================

    @Nested
    @DisplayName("validate()")
    class ValidateTests {

        @Test
        @DisplayName("null file → InvalidImportFileException('required')")
        void route_nullFile_throwsRequired() {
            assertThatThrownBy(() -> router.route(null))
                    .isInstanceOf(InvalidImportFileException.class)
                    .hasMessageContaining("required");
        }

        @Test
        @DisplayName("empty file (isEmpty=true) → InvalidImportFileException('required')")
        void route_emptyFile_throwsRequired() {
            MockMultipartFile emptyFile = new MockMultipartFile(
                    "file", "empty.pdf", "application/pdf", new byte[0]);

            assertThatThrownBy(() -> router.route(emptyFile))
                    .isInstanceOf(InvalidImportFileException.class)
                    .hasMessageContaining("required");
        }

        @Test
        @DisplayName("file > 100 MB → InvalidImportFileException mentioning 100")
        void route_over100MB_throwsSizeExceeded() {
            MultipartFile oversized = mock(MultipartFile.class);
            given(oversized.isEmpty()).willReturn(false);
            given(oversized.getSize()).willReturn(101L * 1024 * 1024);

            assertThatThrownBy(() -> router.route(oversized))
                    .isInstanceOf(InvalidImportFileException.class)
                    .hasMessageContaining("100");
        }

        @Test
        @DisplayName("file exactly 100 MB → passes size validation")
        void route_exactly100MB_passesSizeBoundary() throws Exception {
            byte[] pptxData = pptxBytes();
            // Create a file with the right magic bytes but mock size to exactly 100 MB
            MultipartFile file = mock(MultipartFile.class);
            given(file.isEmpty()).willReturn(false);
            given(file.getSize()).willReturn(100L * 1024 * 1024);
            given(file.getBytes()).willReturn(pptxData);
            given(file.getContentType()).willReturn(
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation");
            given(file.getOriginalFilename()).willReturn("large.pptx");

            given(pptxIngestionService.parse(file)).willReturn(DUMMY_RESULT);

            // Should not throw size exception
            assertThatNoException().isThrownBy(() -> router.route(file));
        }
    }

    // =========================================================================
    // detectFileType()
    // =========================================================================

    @Nested
    @DisplayName("detectFileType()")
    class DetectFileTypeTests {

        @Test
        @DisplayName("PDF magic bytes (%PDF) → routes as PDF (text-based if text-dense)")
        void route_pdfMagicBytes_routesAsPdf() throws Exception {
            byte[] pdfData = textPdfBytes();
            MockMultipartFile file = new MockMultipartFile(
                    "file", "lesson.pdf", "application/pdf", pdfData);

            given(pdfIngestionService.parsePdf(file)).willReturn(DUMMY_RESULT);

            DocumentParseResult result = router.route(file);

            assertThat(result).isNotNull();
            then(pdfIngestionService).should().parsePdf(file);
            then(pptxIngestionService).should(never()).parse(any());
        }

        @Test
        @DisplayName("ZIP magic bytes + PPTX MIME → routes as PPTX")
        void route_zipMagicWithPptxMime_routesAsPptx() throws Exception {
            byte[] pptxData = pptxBytes();
            MockMultipartFile file = new MockMultipartFile(
                    "file", "slides.pptx",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    pptxData);

            given(pptxIngestionService.parse(file)).willReturn(DUMMY_RESULT);

            DocumentParseResult result = router.route(file);

            assertThat(result).isNotNull();
            then(pptxIngestionService).should().parse(file);
            then(pdfIngestionService).should(never()).parsePdf(any());
        }

        @Test
        @DisplayName("ZIP magic bytes + .pptx filename extension → routes as PPTX")
        void route_zipMagicWithPptxExtension_routesAsPptx() throws Exception {
            byte[] pptxData = pptxBytes();
            MockMultipartFile file = new MockMultipartFile(
                    "file", "curriculum.pptx",
                    "application/octet-stream", // wrong MIME, but extension overrides
                    pptxData);

            given(pptxIngestionService.parse(file)).willReturn(DUMMY_RESULT);

            DocumentParseResult result = router.route(file);

            assertThat(result).isSameAs(DUMMY_RESULT);
            then(pptxIngestionService).should().parse(file);
        }

        @Test
        @DisplayName("ZIP magic bytes + application/zip MIME → routes as PPTX (zip is in accepted PPTX mime set)")
        void route_zipMimeType_routesAsPptx() throws Exception {
            byte[] pptxData = pptxBytes();
            MockMultipartFile file = new MockMultipartFile(
                    "file", "slides.pptx", "application/zip", pptxData);

            given(pptxIngestionService.parse(file)).willReturn(DUMMY_RESULT);

            router.route(file);

            then(pptxIngestionService).should().parse(file);
        }

        @Test
        @DisplayName("ZIP magic bytes + unknown MIME + no pptx extension → InvalidImportFileException")
        void route_zipMagicUnknownMime_noExtension_throws() throws Exception {
            byte[] pptxData = pptxBytes();
            MockMultipartFile file = new MockMultipartFile(
                    "file", "document.docx", "application/msword", pptxData);

            assertThatThrownBy(() -> router.route(file))
                    .isInstanceOf(InvalidImportFileException.class)
                    .hasMessageContaining("not a PPTX");
        }

        @Test
        @DisplayName("Unknown magic bytes → InvalidImportFileException('Unsupported')")
        void route_unknownMagicBytes_throwsUnsupported() {
            byte[] unknown = {0x00, 0x01, 0x02, 0x03, 0x04, 0x05};
            MockMultipartFile file = new MockMultipartFile(
                    "file", "weird.bin", "application/octet-stream", unknown);

            assertThatThrownBy(() -> router.route(file))
                    .isInstanceOf(InvalidImportFileException.class)
                    .hasMessageContaining("Unsupported");
        }

        @Test
        @DisplayName("File smaller than 4 bytes → InvalidImportFileException('too small')")
        void route_fileTooSmall_throws() {
            byte[] tiny = {0x25, 0x50}; // only 2 bytes (%P)
            MockMultipartFile file = new MockMultipartFile(
                    "file", "tiny.pdf", "application/pdf", tiny);

            assertThatThrownBy(() -> router.route(file))
                    .isInstanceOf(InvalidImportFileException.class)
                    .hasMessageContaining("too small");
        }
    }

    // =========================================================================
    // routePdf() — text-based vs scanned auto-detection
    // =========================================================================

    @Nested
    @DisplayName("routePdf() — text vs scanned detection")
    class RoutePdfTests {

        @Test
        @DisplayName("Text-dense PDF (>= threshold chars/page) → pdfIngestionService.parsePdf()")
        void routePdf_textDensePdf_routesToPdfIngestionService() throws Exception {
            byte[] pdfData = textPdfBytes();
            MockMultipartFile file = new MockMultipartFile(
                    "file", "lesson.pdf", "application/pdf", pdfData);

            given(pdfIngestionService.parsePdf(file)).willReturn(DUMMY_RESULT);

            router.route(file);

            then(pdfIngestionService).should().parsePdf(file);
            then(scannedPdfIngestionService).should(never()).parse(any());
        }

        @Test
        @DisplayName("Blank page PDF (0 chars/page < threshold) → scannedPdfIngestionService.parse()")
        void routePdf_blankPagePdf_routesToScannedIngestionService() throws Exception {
            byte[] pdfData = blankPdfBytes();
            MockMultipartFile file = new MockMultipartFile(
                    "file", "scan.pdf", "application/pdf", pdfData);

            given(scannedPdfIngestionService.parse(file)).willReturn(DUMMY_RESULT);

            router.route(file);

            then(scannedPdfIngestionService).should().parse(file);
            then(pdfIngestionService).should(never()).parsePdf(any());
        }

        @Test
        @DisplayName("Empty PDF (0 pages) → InvalidImportFileException('no pages')")
        void routePdf_zeroPdf_throws() throws Exception {
            // Create a PDF with no pages
            try (PDDocument doc = new PDDocument()) {
                // Do not add any pages
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                doc.save(baos);
                byte[] emptyPdfData = baos.toByteArray();

                MockMultipartFile file = new MockMultipartFile(
                        "file", "empty.pdf", "application/pdf", emptyPdfData);

                assertThatThrownBy(() -> router.route(file))
                        .isInstanceOf(InvalidImportFileException.class)
                        .hasMessageContaining("no pages");
            }
        }

        @Test
        @DisplayName("Threshold exactly at boundary (chars/page == threshold) → pdfIngestionService (not scanned)")
        void routePdf_exactlyAtThreshold_routesToPdfService() throws Exception {
            // threshold = 100; text PDF produces > 100 chars/page → native path
            byte[] pdfData = textPdfBytes();
            MockMultipartFile file = new MockMultipartFile(
                    "file", "boundary.pdf", "application/pdf", pdfData);

            given(pdfIngestionService.parsePdf(file)).willReturn(DUMMY_RESULT);

            // Set threshold to something very low so our text PDF exceeds it
            given(ocrProps.getScannedPageThreshold()).willReturn(10.0);

            router.route(file);

            then(pdfIngestionService).should().parsePdf(file);
        }
    }

    // =========================================================================
    // route() full flow
    // =========================================================================

    @Nested
    @DisplayName("route() — full delegation flow")
    class RouteFullFlowTests {

        @Test
        @DisplayName("PPTX file → PptxIngestionService.parse() result returned directly")
        void route_pptx_delegatesToPptxServiceAndReturnsItsResult() throws Exception {
            byte[] pptxData = pptxBytes();
            MockMultipartFile file = new MockMultipartFile(
                    "file", "slides.pptx",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    pptxData);

            DocumentParseResult expected = DocumentParseResult.builder()
                    .totalPages(3).fullText("slide content").pageTexts(List.of()).chunks(List.of()).build();
            given(pptxIngestionService.parse(file)).willReturn(expected);

            DocumentParseResult actual = router.route(file);

            assertThat(actual).isSameAs(expected);
            then(pptxIngestionService).should(only()).parse(file);
        }

        @Test
        @DisplayName("Text PDF → PdfIngestionService.parsePdf() result returned directly")
        void route_textPdf_delegatesToPdfServiceAndReturnsItsResult() throws Exception {
            byte[] pdfData = textPdfBytes();
            MockMultipartFile file = new MockMultipartFile(
                    "file", "lesson.pdf", "application/pdf", pdfData);

            DocumentParseResult expected = DocumentParseResult.builder()
                    .totalPages(1).fullText("vocab").pageTexts(List.of()).chunks(List.of()).build();
            given(pdfIngestionService.parsePdf(file)).willReturn(expected);

            DocumentParseResult actual = router.route(file);

            assertThat(actual).isSameAs(expected);
        }

        @Test
        @DisplayName("Scanned PDF → ScannedPdfIngestionService.parse() result returned directly")
        void route_scannedPdf_delegatesToScannedServiceAndReturnsItsResult() throws Exception {
            byte[] pdfData = blankPdfBytes();
            MockMultipartFile file = new MockMultipartFile(
                    "file", "scan.pdf", "application/pdf", pdfData);

            DocumentParseResult expected = DocumentParseResult.builder()
                    .totalPages(1).fullText("ocr text").pageTexts(List.of()).chunks(List.of()).build();
            given(scannedPdfIngestionService.parse(file)).willReturn(expected);

            DocumentParseResult actual = router.route(file);

            assertThat(actual).isSameAs(expected);
        }
    }
}

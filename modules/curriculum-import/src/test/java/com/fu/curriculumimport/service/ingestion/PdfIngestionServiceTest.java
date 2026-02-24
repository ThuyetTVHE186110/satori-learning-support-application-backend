package com.fu.curriculumimport.service.ingestion;

import com.fu.curriculumimport.exception.InvalidImportFileException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for PdfIngestionService.
 *
 * Coverage:
 *
 * validateFile (exercised through parsePdf):
 *  - null file                                        → InvalidImportFileException
 *  - empty file (isEmpty() == true)                   → InvalidImportFileException
 *  - wrong content-type AND no .pdf extension         → InvalidImportFileException
 *  - wrong content-type BUT filename ends with .pdf   → validation passes
 *  - content-type "application/pdf"                   → validation passes
 *  - file size > 50 MB                                → InvalidImportFileException containing "50MB"
 *
 * parsePdf (real PDFBox integration):
 *  - getBytes() throws IOException                    → InvalidImportFileException("Failed to parse PDF")
 *  - single short page PDF                            → 1 totalPage, exactly 1 chunk
 *  - multi-page PDF totalling > 2000 chars            → more than 1 chunk
 *  - chunk fields (chunkType, pageStart, pageEnd, estimatedTokenCount) are correctly populated
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PdfIngestionService")
class PdfIngestionServiceTest {

    // -------------------------------------------------------------------------
    // System under test — no injected dependencies, instantiated directly
    // -------------------------------------------------------------------------
    private PdfIngestionService service;

    @BeforeEach
    void setUp() {
        service = new PdfIngestionService();
    }

    // =========================================================================
    // PDF builder helpers
    // =========================================================================

    /**
     * Creates a real, loadable single-page PDF whose text layer contains {@code pageText}.
     * Uses only printable ASCII so that Helvetica (WinAnsiEncoding) can encode it.
     */
    private byte[] buildSinglePagePdf(String pageText) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            addPageWithText(doc, pageText);
            return toBytes(doc);
        }
    }

    /**
     * Creates a real, loadable PDF with one page per entry in {@code pageTexts}.
     */
    private byte[] buildMultiPagePdf(List<String> pageTexts) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (String text : pageTexts) {
                addPageWithText(doc, text);
            }
            return toBytes(doc);
        }
    }

    /**
     * Appends a single page to {@code doc} and writes {@code text} into it.
     *
     * <p>Text is split into lines of at most 90 characters so that each
     * {@code showText} call stays well within PDFBox's per-call limits and
     * the total character count written per page is predictable.
     */
    private void addPageWithText(PDDocument doc, String text) throws IOException {
        PDPage page = new PDPage();
        doc.addPage(page);

        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setFont(font, 10);
            cs.setLeading(14);
            cs.newLineAtOffset(50, 720);

            // Write text in chunks of 90 chars to keep each showText call safe
            int pos = 0;
            while (pos < text.length()) {
                int end = Math.min(pos + 90, text.length());
                cs.showText(text.substring(pos, end));
                cs.newLine();
                pos = end;
            }

            cs.endText();
        }
    }

    private byte[] toBytes(PDDocument doc) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    // =========================================================================
    // validateFile — exercised through parsePdf()
    // =========================================================================

    @Nested
    @DisplayName("validateFile()")
    class ValidateFileTests {

        @Test
        @DisplayName("null file → throws InvalidImportFileException with 'required' message")
        void nullFile_throwsInvalidImportFileException() {
            assertThatThrownBy(() -> service.parsePdf(null))
                    .isInstanceOf(InvalidImportFileException.class)
                    .hasMessageContaining("required");
        }

        @Test
        @DisplayName("empty file (isEmpty() == true) → throws InvalidImportFileException")
        void emptyFile_throwsInvalidImportFileException() {
            // MockMultipartFile with an empty byte array → isEmpty() == true
            MockMultipartFile emptyFile = new MockMultipartFile(
                    "file", "empty.pdf", "application/pdf", new byte[0]);

            assertThatThrownBy(() -> service.parsePdf(emptyFile))
                    .isInstanceOf(InvalidImportFileException.class)
                    .hasMessageContaining("required");
        }

        @Test
        @DisplayName("wrong content-type AND filename without .pdf extension → throws InvalidImportFileException")
        void wrongContentTypeAndNoExtension_throwsInvalidImportFileException() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "document.txt", "text/plain", new byte[]{1, 2, 3});

            assertThatThrownBy(() -> service.parsePdf(file))
                    .isInstanceOf(InvalidImportFileException.class)
                    .hasMessageContaining("PDF");
        }

        @Test
        @DisplayName("wrong content-type BUT filename ends with .pdf → validation passes, PDF is parsed")
        void wrongContentTypeButPdfExtension_validationPasses() throws IOException {
            // A real parseable PDF — validation must not throw the file-type error
            byte[] pdfBytes = buildSinglePagePdf("Hello");
            MockMultipartFile file = new MockMultipartFile(
                    "file", "document.pdf", "text/plain", pdfBytes);

            // Should complete without throwing any exception
            DocumentParseResult result = service.parsePdf(file);

            assertThat(result).isNotNull();
            assertThat(result.getTotalPages()).isEqualTo(1);
        }

        @Test
        @DisplayName("content-type 'application/pdf' → validation passes, PDF is parsed")
        void correctContentType_validationPasses() throws IOException {
            byte[] pdfBytes = buildSinglePagePdf("Hello");
            MockMultipartFile file = new MockMultipartFile(
                    "file", "document.pdf", "application/pdf", pdfBytes);

            DocumentParseResult result = service.parsePdf(file);

            assertThat(result).isNotNull();
            assertThat(result.getTotalPages()).isEqualTo(1);
        }

        @Test
        @DisplayName("file size > 50 MB → throws InvalidImportFileException containing '50MB'")
        void oversizedFile_throwsInvalidImportFileException() throws IOException {
            // Mock so getSize() returns > 50 MB without allocating real memory
            MultipartFile oversized = mock(MultipartFile.class);
            given(oversized.isEmpty()).willReturn(false);
            given(oversized.getContentType()).willReturn("application/pdf");
            given(oversized.getOriginalFilename()).willReturn("large.pdf");
            given(oversized.getSize()).willReturn(51L * 1024 * 1024); // 51 MB

            assertThatThrownBy(() -> service.parsePdf(oversized))
                    .isInstanceOf(InvalidImportFileException.class)
                    .hasMessageContaining("50MB");
        }
    }

    // =========================================================================
    // parsePdf() — integration with real PDFBox
    // =========================================================================

    @Nested
    @DisplayName("parsePdf()")
    class ParsePdfTests {

        @Test
        @DisplayName("getBytes() throws IOException → throws InvalidImportFileException with 'Failed to parse PDF'")
        void getBytes_throwsIOException_wrappedAsInvalidImportFileException() throws IOException {
            MultipartFile brokenFile = mock(MultipartFile.class);
            given(brokenFile.isEmpty()).willReturn(false);
            given(brokenFile.getContentType()).willReturn("application/pdf");
            given(brokenFile.getOriginalFilename()).willReturn("broken.pdf");
            given(brokenFile.getSize()).willReturn(1024L);
            given(brokenFile.getBytes()).willThrow(new IOException("disk read error"));

            assertThatThrownBy(() -> service.parsePdf(brokenFile))
                    .isInstanceOf(InvalidImportFileException.class)
                    .hasMessageContaining("Failed to parse PDF");
        }

        @Test
        @DisplayName("single short page PDF → totalPages == 1, exactly one chunk produced")
        void singleShortPage_returnsOneChunkAndOneTotalPage() throws IOException {
            // Short text well below the 2000-char threshold → single chunk
            String shortText = "This is a short Korean lesson introduction.";
            byte[] pdfBytes = buildSinglePagePdf(shortText);

            MockMultipartFile file = new MockMultipartFile(
                    "file", "lesson.pdf", "application/pdf", pdfBytes);

            DocumentParseResult result = service.parsePdf(file);

            assertThat(result.getTotalPages()).isEqualTo(1);
            assertThat(result.getChunks()).hasSize(1);
        }

        @Test
        @DisplayName("multi-page PDF totalling more than 2000 chars → more than one chunk")
        void multiPagePdfExceeding2000Chars_producesMultipleChunks() throws IOException {
            // Each page carries ~500 ASCII characters.
            // Accumulating 4 pages = ~2000 chars triggers the first chunk boundary;
            // the 5th page then forms a second chunk.
            String lineOf90 = "Korean vocabulary: taberu, nomu, kiku, hanasu, yomu, kaku, miru, iku, kuru, suru OK";
            //  "lineOf90" is exactly 84 chars; 6 repetitions per page = 504 chars of extracted text
            String pageContent = lineOf90.repeat(6); // ~504 chars

            List<String> pages = List.of(pageContent, pageContent, pageContent, pageContent, pageContent);
            byte[] pdfBytes = buildMultiPagePdf(pages);

            MockMultipartFile file = new MockMultipartFile(
                    "file", "curriculum.pdf", "application/pdf", pdfBytes);

            DocumentParseResult result = service.parsePdf(file);

            assertThat(result.getTotalPages()).isEqualTo(5);
            assertThat(result.getChunks().size()).isGreaterThan(1);
        }

        @Test
        @DisplayName("chunk fields are correctly populated: chunkType='TEXT', pageStart>=1, pageEnd>=pageStart, estimatedTokenCount>0")
        void chunkFields_areCorrectlyPopulated() throws IOException {
            // Build a two-page PDF so we get at least one chunk guaranteed
            String pageContent = "This page contains vocabulary and grammar content for the Korean language lesson.";
            byte[] pdfBytes = buildMultiPagePdf(List.of(pageContent, pageContent));

            MockMultipartFile file = new MockMultipartFile(
                    "file", "lesson.pdf", "application/pdf", pdfBytes);

            DocumentParseResult result = service.parsePdf(file);

            assertThat(result.getChunks()).isNotEmpty();

            for (DocumentChunk chunk : result.getChunks()) {
                assertThat(chunk.getChunkType())
                        .as("chunkType must be TEXT")
                        .isEqualTo("TEXT");
                assertThat(chunk.getPageStart())
                        .as("pageStart must be at least 1")
                        .isGreaterThanOrEqualTo(1);
                assertThat(chunk.getPageEnd())
                        .as("pageEnd must be >= pageStart")
                        .isGreaterThanOrEqualTo(chunk.getPageStart());
                assertThat(chunk.getEstimatedTokenCount())
                        .as("estimatedTokenCount must be positive")
                        .isPositive();
            }
        }

        @Test
        @DisplayName("parse result fullText is non-blank and pageTexts has one entry per page")
        void parseResult_fullTextAndPageTextsArePopulated() throws IOException {
            String page1 = "First page content for Korean lesson.";
            String page2 = "Second page content covering grammar patterns.";
            byte[] pdfBytes = buildMultiPagePdf(List.of(page1, page2));

            MockMultipartFile file = new MockMultipartFile(
                    "file", "lesson.pdf", "application/pdf", pdfBytes);

            DocumentParseResult result = service.parsePdf(file);

            assertThat(result.getTotalPages()).isEqualTo(2);
            assertThat(result.getPageTexts()).hasSize(2);
            assertThat(result.getFullText()).isNotBlank();
        }

        @Test
        @DisplayName("chunkIndex values start at 0 and increment sequentially")
        void chunkIndices_areSequentialFromZero() throws IOException {
            // 5 pages of ~500 chars each to guarantee at least 2 chunks
            String pageContent = "Lesson content covering vocabulary and grammar items in Korean language study. ".repeat(7);
            List<String> pages = List.of(pageContent, pageContent, pageContent, pageContent, pageContent);
            byte[] pdfBytes = buildMultiPagePdf(pages);

            MockMultipartFile file = new MockMultipartFile(
                    "file", "curriculum.pdf", "application/pdf", pdfBytes);

            DocumentParseResult result = service.parsePdf(file);

            List<DocumentChunk> chunks = result.getChunks();
            assertThat(chunks).hasSizeGreaterThan(1);

            for (int i = 0; i < chunks.size(); i++) {
                assertThat(chunks.get(i).getChunkIndex())
                        .as("chunk at list index %d should have chunkIndex %d", i, i)
                        .isEqualTo(i);
            }
        }

        @Test
        @DisplayName("chunk content is trimmed and non-blank")
        void chunkContent_isTrimmedAndNonBlank() throws IOException {
            String pageContent = "Sample content for trimming verification.";
            byte[] pdfBytes = buildSinglePagePdf(pageContent);

            MockMultipartFile file = new MockMultipartFile(
                    "file", "lesson.pdf", "application/pdf", pdfBytes);

            DocumentParseResult result = service.parsePdf(file);

            assertThat(result.getChunks()).isNotEmpty();
            for (DocumentChunk chunk : result.getChunks()) {
                assertThat(chunk.getContent()).isNotBlank();
                // trimmed means no leading/trailing whitespace
                assertThat(chunk.getContent())
                        .isEqualTo(chunk.getContent().trim());
            }
        }
    }
}

package com.fu.curriculumimport.service.ingestion;

import com.fu.curriculumimport.exception.InvalidImportFileException;
import org.apache.poi.xslf.usermodel.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

/**
 * Unit tests for PptxIngestionService.
 *
 * Coverage:
 *
 * parse() — file validation:
 *  - valid PPTX                          → parses successfully
 *  - getInputStream() throws IOException → InvalidImportFileException
 *
 * parse() — slide-to-chunk mapping:
 *  - empty presentation (0 slides)       → 0 chunks, totalPages=0
 *  - single slide                        → 1 chunk with chunkIndex=0, pageStart=1, pageEnd=1
 *  - multiple slides                     → N chunks, one per slide
 *  - chunkIndex == slide index (0-based)
 *  - pageStart == pageEnd == slideNumber (1-based)
 *  - chunkType always "SLIDE"
 *  - estimatedTokenCount = content.length / 4
 *
 * parse() — structured text format:
 *  - header "[SLIDE N] タイトル: ..." always present
 *  - no title shape → header shows "(no title)"
 *  - body text box → content appears in output
 *  - table in slide → rows rendered as tab-separated text
 *  - slide with no body shapes and no notes → only header in output
 *
 * parse() — DocumentParseResult fields:
 *  - totalPages == number of slides
 *  - fullText is concatenation of all slide texts
 *  - pageTexts has one entry per slide
 *  - chunks list has one entry per slide
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PptxIngestionService")
class PptxIngestionServiceTest {

    // No injected dependencies — PptxIngestionService uses POI APIs directly
    private final PptxIngestionService service = new PptxIngestionService();

    // =========================================================================
    // PPTX builder helpers
    // =========================================================================

    /**
     * Creates a single-slide PPTX with one text box containing {@code bodyText}.
     * The text box will NOT be detected as a title (no "title" in shape name).
     */
    private byte[] singleSlidePptx(String bodyText) throws IOException {
        try (XMLSlideShow pptx = new XMLSlideShow()) {
            XSLFSlide slide = pptx.createSlide();
            XSLFTextBox tb = slide.createTextBox();
            tb.clearText();
            tb.setText(bodyText);
            tb.setAnchor(new Rectangle2D.Double(50, 100, 600, 200));
            return toBytes(pptx);
        }
    }

    /**
     * Builds a PPTX with multiple slides. Each slide has one body text box with
     * the corresponding entry from {@code slideTexts}.
     */
    private byte[] multiSlidePptx(List<String> slideTexts) throws IOException {
        try (XMLSlideShow pptx = new XMLSlideShow()) {
            for (String text : slideTexts) {
                XSLFSlide slide = pptx.createSlide();
                XSLFTextBox tb = slide.createTextBox();
                tb.clearText();
                tb.setText(text);
                tb.setAnchor(new Rectangle2D.Double(50, 100, 600, 200));
            }
            return toBytes(pptx);
        }
    }

    /**
     * Builds PPTX with one slide that contains a table (2 rows × 2 cols).
     */
    private byte[] tableSlide(String cell00, String cell01, String cell10, String cell11)
            throws IOException {
        try (XMLSlideShow pptx = new XMLSlideShow()) {
            XSLFSlide slide = pptx.createSlide();
            XSLFTable table = slide.createTable(2, 2);
            table.setAnchor(new Rectangle2D.Double(50, 100, 600, 200));
            table.getRows().get(0).getCells().get(0).setText(cell00);
            table.getRows().get(0).getCells().get(1).setText(cell01);
            table.getRows().get(1).getCells().get(0).setText(cell10);
            table.getRows().get(1).getCells().get(1).setText(cell11);
            return toBytes(pptx);
        }
    }

    /** Builds an empty PPTX with no slides. */
    private byte[] emptyPptx() throws IOException {
        try (XMLSlideShow pptx = new XMLSlideShow()) {
            return toBytes(pptx);
        }
    }

    private byte[] toBytes(XMLSlideShow pptx) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            pptx.write(baos);
            return baos.toByteArray();
        }
    }

    private MockMultipartFile wrapPptx(byte[] bytes) {
        return new MockMultipartFile(
                "file", "lesson.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                bytes);
    }

    // =========================================================================
    // parse() — file I/O
    // =========================================================================

    @Nested
    @DisplayName("parse() — file I/O")
    class FileIoTests {

        @Test
        @DisplayName("getInputStream() throws IOException → InvalidImportFileException")
        void parse_getInputStreamThrows_wrapsAsInvalidImportFileException() throws IOException {
            MultipartFile broken = mock(MultipartFile.class);
            given(broken.getOriginalFilename()).willReturn("broken.pptx");
            given(broken.getInputStream()).willThrow(new IOException("disk error"));

            assertThatThrownBy(() -> service.parse(broken))
                    .isInstanceOf(InvalidImportFileException.class)
                    .hasMessageContaining("Failed to parse PPTX");
        }

        @Test
        @DisplayName("Valid single-slide PPTX → parses without exception")
        void parse_validPptx_noException() throws IOException {
            byte[] data = singleSlidePptx("Test content");
            DocumentParseResult result = service.parse(wrapPptx(data));
            assertThat(result).isNotNull();
        }
    }

    // =========================================================================
    // parse() — slide → chunk mapping
    // =========================================================================

    @Nested
    @DisplayName("parse() — slide to chunk mapping")
    class SlideToChunkMappingTests {

        @Test
        @DisplayName("Empty presentation (0 slides) → 0 chunks, totalPages=0")
        void parse_emptyPresentation_zeroChunks() throws IOException {
            DocumentParseResult result = service.parse(wrapPptx(emptyPptx()));

            assertThat(result.getTotalPages()).isZero();
            assertThat(result.getChunks()).isEmpty();
            assertThat(result.getPageTexts()).isEmpty();
        }

        @Test
        @DisplayName("Single slide → exactly 1 chunk with chunkIndex=0, pageStart=1, pageEnd=1")
        void parse_singleSlide_oneChunkWithCorrectMetadata() throws IOException {
            DocumentParseResult result = service.parse(wrapPptx(singleSlidePptx("Vocab list")));

            assertThat(result.getTotalPages()).isEqualTo(1);
            assertThat(result.getChunks()).hasSize(1);

            DocumentChunk chunk = result.getChunks().get(0);
            assertThat(chunk.getChunkIndex()).isZero();
            assertThat(chunk.getPageStart()).isEqualTo(1);
            assertThat(chunk.getPageEnd()).isEqualTo(1);
        }

        @Test
        @DisplayName("3-slide PPTX → 3 chunks, each chunk covers exactly its own slide")
        void parse_threeSlides_threeChunksWithCorrectPageBoundaries() throws IOException {
            List<String> texts = List.of("Slide 1 body", "Slide 2 body", "Slide 3 body");
            DocumentParseResult result = service.parse(wrapPptx(multiSlidePptx(texts)));

            assertThat(result.getTotalPages()).isEqualTo(3);
            assertThat(result.getChunks()).hasSize(3);

            for (int i = 0; i < 3; i++) {
                DocumentChunk chunk = result.getChunks().get(i);
                assertThat(chunk.getChunkIndex())
                        .as("chunk %d should have chunkIndex %d", i, i)
                        .isEqualTo(i);
                assertThat(chunk.getPageStart())
                        .as("chunk %d pageStart should be %d", i, i + 1)
                        .isEqualTo(i + 1);
                assertThat(chunk.getPageEnd())
                        .as("chunk %d pageEnd == pageStart for a single-slide chunk", i)
                        .isEqualTo(i + 1);
            }
        }

        @Test
        @DisplayName("chunkType is always 'SLIDE' for every chunk")
        void parse_allChunks_chunkTypeIsSlide() throws IOException {
            List<String> texts = List.of("First", "Second", "Third");
            DocumentParseResult result = service.parse(wrapPptx(multiSlidePptx(texts)));

            for (DocumentChunk chunk : result.getChunks()) {
                assertThat(chunk.getChunkType())
                        .as("chunkType for PPTX must always be SLIDE")
                        .isEqualTo("SLIDE");
            }
        }

        @Test
        @DisplayName("estimatedTokenCount = content.length / 4 (integer division)")
        void parse_estimatedTokenCount_isContentLengthDiv4() throws IOException {
            DocumentParseResult result = service.parse(wrapPptx(singleSlidePptx("Content")));

            DocumentChunk chunk = result.getChunks().get(0);
            int expectedTokens = chunk.getContent().length() / 4;
            assertThat(chunk.getEstimatedTokenCount()).isEqualTo(expectedTokens);
        }
    }

    // =========================================================================
    // parse() — structured text format
    // =========================================================================

    @Nested
    @DisplayName("parse() — structured text format")
    class StructuredTextTests {

        @Test
        @DisplayName("Each slide header contains '[SLIDE N] タイトル:' prefix")
        void parse_slideHeader_alwaysPresent() throws IOException {
            List<String> texts = List.of("Slide 1 body", "Slide 2 body");
            DocumentParseResult result = service.parse(wrapPptx(multiSlidePptx(texts)));

            assertThat(result.getChunks().get(0).getContent()).contains("[SLIDE 1] タイトル:");
            assertThat(result.getChunks().get(1).getContent()).contains("[SLIDE 2] タイトル:");
        }

        @Test
        @DisplayName("Slide with no title shape → header shows '(no title)'")
        void parse_noTitleShape_headerShowsNoTitle() throws IOException {
            // singleSlidePptx creates a text box — not a title placeholder
            DocumentParseResult result = service.parse(wrapPptx(singleSlidePptx("Some body text")));

            String content = result.getChunks().get(0).getContent();
            assertThat(content).contains("(no title)");
        }

        @Test
        @DisplayName("Body text box → content appears in output")
        void parse_bodyTextBox_contentAppearsInOutput() throws IOException {
            String bodyText = "待合室、診察、処方箋";
            DocumentParseResult result = service.parse(wrapPptx(singleSlidePptx(bodyText)));

            String content = result.getChunks().get(0).getContent();
            assertThat(content).contains(bodyText);
        }

        @Test
        @DisplayName("Body text box → surrounded by '=== NỘI DUNG ===' section")
        void parse_bodyTextBox_appearsInContentSection() throws IOException {
            String bodyText = "Grammar pattern content";
            DocumentParseResult result = service.parse(wrapPptx(singleSlidePptx(bodyText)));

            String content = result.getChunks().get(0).getContent();
            assertThat(content).contains("=== NỘI DUNG ===");
        }

        @Test
        @DisplayName("Table in slide → rows rendered as tab-separated entries")
        void parse_tableInSlide_renderedAsTabSeparatedRows() throws IOException {
            DocumentParseResult result = service.parse(wrapPptx(
                    tableSlide("単語", "意味", "待合室", "waiting room")));

            String content = result.getChunks().get(0).getContent();
            // Each row is tab-separated
            assertThat(content).contains("単語\t意味");
            assertThat(content).contains("待合室\twaiting room");
        }

        @Test
        @DisplayName("Slide with no body shapes → header present, no content section")
        void parse_slideWithNoBody_noContentSection() throws IOException {
            // emptyPptx → 0 slides. Need a one-slide PPTX but with no text shapes.
            // Build a PPTX with one blank slide (no shapes added)
            try (XMLSlideShow pptx = new XMLSlideShow()) {
                pptx.createSlide(); // blank slide — no extra shapes
                byte[] noBodyData = toBytes(pptx);

                DocumentParseResult result = service.parse(wrapPptx(noBodyData));

                String content = result.getChunks().get(0).getContent();
                assertThat(content).contains("[SLIDE 1]");
                assertThat(content).doesNotContain("=== NỘI DUNG ===");
            }
        }

        @Test
        @DisplayName("Chunk content is non-blank and trimmed")
        void parse_chunkContent_isTrimmedAndNonBlank() throws IOException {
            DocumentParseResult result = service.parse(wrapPptx(singleSlidePptx("Content")));

            DocumentChunk chunk = result.getChunks().get(0);
            assertThat(chunk.getContent()).isNotBlank();
            assertThat(chunk.getContent()).isEqualTo(chunk.getContent().trim());
        }

        @Test
        @DisplayName("Different slides produce independently structured chunk content")
        void parse_multipleSlides_eachChunkContainsItsOwnHeader() throws IOException {
            List<String> texts = List.of("Body of slide A", "Body of slide B");
            DocumentParseResult result = service.parse(wrapPptx(multiSlidePptx(texts)));

            String slide1Content = result.getChunks().get(0).getContent();
            String slide2Content = result.getChunks().get(1).getContent();

            assertThat(slide1Content).contains("[SLIDE 1]");
            assertThat(slide1Content).doesNotContain("[SLIDE 2]");

            assertThat(slide2Content).contains("[SLIDE 2]");
            assertThat(slide2Content).doesNotContain("[SLIDE 1]");
        }
    }

    // =========================================================================
    // parse() — DocumentParseResult aggregate fields
    // =========================================================================

    @Nested
    @DisplayName("parse() — DocumentParseResult fields")
    class ParseResultFieldTests {

        @Test
        @DisplayName("totalPages equals number of slides")
        void parse_totalPagesEqualsSlideCount() throws IOException {
            List<String> texts = List.of("A", "B", "C", "D");
            DocumentParseResult result = service.parse(wrapPptx(multiSlidePptx(texts)));

            assertThat(result.getTotalPages()).isEqualTo(4);
        }

        @Test
        @DisplayName("pageTexts has one entry per slide")
        void parse_pageTextsHasOneEntryPerSlide() throws IOException {
            List<String> texts = List.of("Slide1", "Slide2", "Slide3");
            DocumentParseResult result = service.parse(wrapPptx(multiSlidePptx(texts)));

            assertThat(result.getPageTexts()).hasSize(3);
        }

        @Test
        @DisplayName("fullText is concatenation of all slide texts separated by newlines")
        void parse_fullTextContainsAllSlideContent() throws IOException {
            List<String> texts = List.of("Vocabulary list", "Grammar patterns");
            DocumentParseResult result = service.parse(wrapPptx(multiSlidePptx(texts)));

            assertThat(result.getFullText()).contains("Vocabulary list");
            assertThat(result.getFullText()).contains("Grammar patterns");
        }

        @Test
        @DisplayName("pageTexts entries match corresponding chunk content")
        void parse_pageTextsMatchChunkContent() throws IOException {
            List<String> texts = List.of("BodyA", "BodyB");
            DocumentParseResult result = service.parse(wrapPptx(multiSlidePptx(texts)));

            // pageTexts[i] should equal chunks[i].content
            for (int i = 0; i < result.getChunks().size(); i++) {
                assertThat(result.getPageTexts().get(i))
                        .isEqualTo(result.getChunks().get(i).getContent());
            }
        }
    }
}

package com.fu.curriculumimport.service.ingestion;

import com.fu.curriculumimport.config.OcrProperties;
import com.fu.curriculumimport.exception.InvalidImportFileException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Set;

/**
 * <b>Single entry-point</b> for all file ingestion.
 *
 * <h3>Routing logic</h3>
 * <ol>
 *   <li><b>Magic-bytes detection</b> – determines the real file type regardless
 *       of the {@code Content-Type} header sent by the client.</li>
 *   <li>PPTX → {@link PptxIngestionService} (1 slide = 1 lesson chunk).</li>
 *   <li>PDF text-based → {@link PdfIngestionService} (character-window chunks).</li>
 *   <li>PDF scanned (low text density) → {@link ScannedPdfIngestionService}
 *       (PaddleOCR sidecar).</li>
 *   <li>Anything else → {@link InvalidImportFileException}.</li>
 * </ol>
 *
 * <h3>Scanned-PDF auto-detection</h3>
 * PDFBox text extraction is attempted first. If the average character count per
 * page falls below {@link OcrProperties#getScannedPageThreshold()} the file is
 * treated as a scanned image PDF and routed to the OCR service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileIngestionRouter {

    // PPTX magic bytes: PK zip header (0x50 0x4B 0x03 0x04)
    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};
    // PDF magic bytes: %PDF (0x25 0x50 0x44 0x46)
    private static final byte[] PDF_MAGIC = {0x25, 0x50, 0x44, 0x46};

    private static final long MAX_FILE_SIZE = 100L * 1024 * 1024; // 100 MB (PPTX can be large)

    private static final Set<String> PPTX_MIME = Set.of(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.ms-powerpoint.presentation.macroEnabled.12",
            "application/zip"                   // some clients send generic zip
    );
    private static final Set<String> PDF_MIME = Set.of("application/pdf");

    private final PdfIngestionService pdfIngestionService;
    private final ScannedPdfIngestionService scannedPdfIngestionService;
    private final PptxIngestionService pptxIngestionService;
    private final OcrProperties ocrProps;

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Detect file type, validate it, and route to the appropriate ingestion service.
     *
     * @param file uploaded file (PDF or PPTX)
     * @return structured parse result with chunks ready for the Agentic RAG pipeline
     * @throws InvalidImportFileException for unsupported types or oversized files
     */
    public DocumentParseResult route(MultipartFile file) {
        validate(file);

        FileType type = detectFileType(file);
        log.info("File '{}' detected as {}", file.getOriginalFilename(), type);

        return switch (type) {
            case PPTX -> pptxIngestionService.parse(file);
            case PDF  -> routePdf(file);
        };
    }

    // ── PDF sub-routing (text vs scanned) ───────────────────────────────────

    private DocumentParseResult routePdf(MultipartFile file) {
        // 1. Try native text extraction
        try (PDDocument doc = Loader.loadPDF(file.getBytes())) {
            int totalPages = doc.getNumberOfPages();
            if (totalPages == 0) {
                throw new InvalidImportFileException("PDF has no pages.");
            }

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(Math.min(totalPages, 5)); // sample first 5 pages
            String sample = stripper.getText(doc);

            double charsPerPage = (double) sample.length() / Math.min(totalPages, 5);
            log.debug("PDF sample: {} chars/page (threshold={})",
                    String.format("%.1f", charsPerPage), ocrProps.getScannedPageThreshold());

            if (charsPerPage < ocrProps.getScannedPageThreshold()) {
                log.info("Scanned PDF detected ({} chars/page). Routing to OCR pipeline.", String.format("%.1f", charsPerPage));
                return scannedPdfIngestionService.parse(file);
            }

            log.info("Text-based PDF detected ({} chars/page). Using native extraction.", String.format("%.1f", charsPerPage));
            return pdfIngestionService.parsePdf(file);

        } catch (InvalidImportFileException e) {
            throw e;
        } catch (IOException e) {
            throw new InvalidImportFileException("Cannot read PDF file: " + e.getMessage());
        }
    }

    // ── Validation ──────────────────────────────────────────────────────────

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidImportFileException("File is required and must not be empty.");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new InvalidImportFileException(String.format(
                    "File size %.1f MB exceeds maximum of %d MB.",
                    file.getSize() / (1024.0 * 1024.0),
                    MAX_FILE_SIZE / (1024 * 1024)));
        }
    }

    // ── Magic-bytes detection ────────────────────────────────────────────────

    /**
     * Determine the real file type using the first 4 bytes (magic bytes),
     * falling back to the declared MIME type when the stream cannot be read.
     */
    private FileType detectFileType(MultipartFile file) {
        try {
            byte[] header = file.getBytes();
            if (header.length < 4) {
                throw new InvalidImportFileException("File is too small to be a valid PDF or PPTX.");
            }

            if (matchesMagic(header, PDF_MAGIC)) {
                return FileType.PDF;
            }

            // PPTX is a ZIP archive — check magic bytes first
            if (matchesMagic(header, ZIP_MAGIC)) {
                // All PPTX files are ZIP, but so are DOCX, XLSX, etc.
                // Use MIME type as tiebreaker.
                String mime = file.getContentType();
                String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
                if ((mime != null && PPTX_MIME.contains(mime.toLowerCase()))
                        || filename.toLowerCase().endsWith(".pptx")
                        || filename.toLowerCase().endsWith(".ppt")) {
                    return FileType.PPTX;
                }
                throw new InvalidImportFileException(
                        "ZIP-based file is not a PPTX presentation. Received MIME: " + mime);
            }

            throw new InvalidImportFileException(
                    "Unsupported file format. Only PDF and PPTX files are accepted. "
                            + "Received MIME: " + file.getContentType());

        } catch (InvalidImportFileException e) {
            throw e;
        } catch (IOException e) {
            // Last resort: trust the declared MIME type
            String mime = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
            if (PDF_MIME.contains(mime)) return FileType.PDF;
            if (PPTX_MIME.contains(mime)) return FileType.PPTX;
            throw new InvalidImportFileException("Cannot determine file type: " + e.getMessage());
        }
    }

    private boolean matchesMagic(byte[] data, byte[] magic) {
        if (data.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) {
            if (data[i] != magic[i]) return false;
        }
        return true;
    }

    // ── Internal enum ────────────────────────────────────────────────────────

    private enum FileType { PDF, PPTX }
}

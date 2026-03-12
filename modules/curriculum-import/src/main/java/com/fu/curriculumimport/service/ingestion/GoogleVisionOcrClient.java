package com.fu.curriculumimport.service.ingestion;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.vision.v1.*;
import com.google.cloud.vision.v1.Feature.Type;
import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/**
 * OCR client backed by <b>Google Cloud Vision Document Text Detection</b>.
 *
 * <h3>Why Google Vision for scanned Japanese textbooks?</h3>
 * <ul>
 *   <li>Returns per-block layout information (paragraphs, tables) via the
 *       {@code DOCUMENT_TEXT_DETECTION} feature — not just a flat text stream.</li>
 *   <li>Recognises CJK characters and furigana more accurately than PaddleOCR
 *       on book-quality scans.</li>
 *   <li>Detects table structure via bounding-box column alignment heuristic.</li>
 * </ul>
 *
 * <h3>Activation</h3>
 * <p>Set {@code curriculum-import.ocr.provider=google_vision} (or {@code auto})
 * in {@code application.yml}. Requires:
 * <ol>
 *   <li>Environment variable {@code GOOGLE_APPLICATION_CREDENTIALS} pointing to
 *       a service-account JSON key with {@code roles/cloudvision.user}.</li>
 *   <li>The {@code google-cloud-vision} Maven dependency (already included in
 *       the module POM).</li>
 * </ol>
 *
 * <p>When Google Vision is unavailable the caller ({@link OcrClient}) falls back
 * to the PaddleOCR sidecar automatically when {@code provider=auto}.
 *
 * <h3>Table detection heuristic</h3>
 * <p>Google Vision does not explicitly label table cells. This client infers
 * table rows by clustering text blocks with similar Y coordinates and at least
 * 3 distinct X columns. Each detected row is serialised as a tab-separated
 * string compatible with {@link MnNTableParser}.
 */
@Slf4j
public class GoogleVisionOcrClient {

    /** Path to service-account JSON key; null/blank = use ADC env var. */
    private final String credentialsFile;

    public GoogleVisionOcrClient(String credentialsFile) {
        this.credentialsFile = credentialsFile;
    }

    /**
     * Call Google Cloud Vision DOCUMENT_TEXT_DETECTION and return structured result.
     *
     * @param image page image from PDFRenderer
     * @return OCR result with plain text and optional table rows; {@link OcrTextResult#empty()} on error
     */
    public OcrTextResult extractText(BufferedImage image) {
        try {
            byte[] imageBytes = encodeToJpeg(image);
            return callVisionApi(imageBytes);
        } catch (Exception e) {
            log.warn("Google Vision OCR failed [{}]: {} — falling back to PaddleOCR",
                    e.getClass().getSimpleName(), e.getMessage());
            log.debug("Google Vision stack trace:", e);
            return OcrTextResult.empty();
        }
    }

    // ── Internal implementation ──────────────────────────────────────────────

    private OcrTextResult callVisionApi(byte[] imageBytes) throws IOException {

        ByteString imgBytes = ByteString.copyFrom(imageBytes);

        Image visionImage = Image.newBuilder()
                .setContent(imgBytes)
                .build();

        Feature feature = Feature.newBuilder()
                .setType(Type.DOCUMENT_TEXT_DETECTION)
                .build();

        AnnotateImageRequest request = AnnotateImageRequest.newBuilder()
                .setImage(visionImage)
                .addFeatures(feature)
                .build();

        try (ImageAnnotatorClient client = createVisionClient()) {

            BatchAnnotateImagesResponse batchResp =
                    client.batchAnnotateImages(List.of(request));

            AnnotateImageResponse resp = batchResp.getResponses(0);

            if (resp.hasError()) {
                log.warn("Google Vision API error: {}", resp.getError().getMessage());
                return OcrTextResult.empty();
            }

            TextAnnotation fullTextAnnotation = resp.getFullTextAnnotation();
            String fullText = fullTextAnnotation.getText();

            // Apply furigana normalization (consistent with PaddleOCR path)
            String normalizedText = FuriganaNormalizer.normalize(fullText != null ? fullText : "");

            // Extract structured blocks for table detection
            List<String> tableRows = extractTableRows(fullTextAnnotation);

            log.debug("Google Vision OCR: {} chars, {} table rows detected",
                    normalizedText.length(), tableRows.size());

            return new OcrTextResult(normalizedText, 0.95, tableRows);
        }
    }

    /**
     * Create an {@code ImageAnnotatorClient}.
     * <ul>
     *   <li>If {@code credentialsFile} is set → load the service-account JSON and pass it
     *       explicitly via {@code ImageAnnotatorSettings}.</li>
     *   <li>Otherwise → use Application Default Credentials (ADC /
     *       {@code GOOGLE_APPLICATION_CREDENTIALS} env var).</li>
     * </ul>
     */
    private ImageAnnotatorClient createVisionClient() throws IOException {
        if (credentialsFile == null || credentialsFile.isBlank()) {
            log.debug("Google Vision: using Application Default Credentials (ADC)");
            return ImageAnnotatorClient.create();
        }

        log.debug("Google Vision: loading credentials from {}", credentialsFile);

        GoogleCredentials credentials;
        try (InputStream fis = new FileInputStream(credentialsFile)) {
            credentials = GoogleCredentials.fromStream(fis);
        }

        ImageAnnotatorSettings settings = ImageAnnotatorSettings.newBuilder()
                .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                .build();

        return ImageAnnotatorClient.create(settings);
    }

    /**
     * Detect table rows from Google Vision block structure.
     *
     * <p>Strategy: group text blocks by rounded Y center within a ±20px tolerance,
     * then for each Y-group with ≥3 distinct X positions emit a tab-separated row.
     */
    private List<String> extractTableRows(TextAnnotation fullTextAnnotation) {
        List<String> rows = new ArrayList<>();
        try {
            List<Page> pages = fullTextAnnotation.getPagesList();
            if (pages.isEmpty()) return rows;

            Page page = pages.get(0);
            List<Block> blocks = page.getBlocksList();

            record Cell(int yBucket, int x, String text) {}
            List<Cell> cells = new ArrayList<>();

            for (Block block : blocks) {
                for (Paragraph para : block.getParagraphsList()) {
                    BoundingPoly bb = para.getBoundingBox();
                    List<Vertex> vertices = bb.getVerticesList();
                    if (vertices.size() < 4) continue;

                    int y0 = vertices.get(0).getY();
                    int y2 = vertices.get(2).getY();
                    int x0 = vertices.get(0).getX();
                    int yCenter = (y0 + y2) / 2;
                    int yBucket = (yCenter / 25) * 25; // 25px bucket = one text line

                    StringBuilder paraText = new StringBuilder();
                    for (Word word : para.getWordsList()) {
                        for (Symbol sym : word.getSymbolsList()) {
                            paraText.append(sym.getText());
                        }
                        paraText.append(" ");
                    }
                    String text = paraText.toString().trim();
                    if (!text.isBlank()) {
                        cells.add(new Cell(yBucket, x0, text));
                    }
                }
            }

            // Group cells by Y bucket
            TreeMap<Integer, List<Cell>> byY = new TreeMap<>();
            for (Cell c : cells) {
                byY.computeIfAbsent(c.yBucket(), k -> new ArrayList<>()).add(c);
            }

            // Emit rows where ≥3 distinct x-positions detected (= likely table row)
            for (var entry : byY.entrySet()) {
                List<Cell> rowCells = entry.getValue();
                if (rowCells.size() < 3) continue;
                rowCells.sort(Comparator.comparingInt(Cell::x));
                StringBuilder rowSb = new StringBuilder();
                for (Cell c : rowCells) {
                    if (!rowSb.isEmpty()) rowSb.append("\t");
                    rowSb.append(c.text());
                }
                rows.add(rowSb.toString());
            }

        } catch (Exception e) {
            log.debug("Table row extraction failed (non-critical): {}", e.getMessage());
        }
        return rows;
    }

    private byte[] encodeToJpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", baos);
        return baos.toByteArray();
    }
}

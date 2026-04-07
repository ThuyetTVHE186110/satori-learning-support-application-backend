package com.fu.assignment.ai;

import com.google.cloud.vision.v1.*;
import com.google.protobuf.ByteString;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.springframework.web.multipart.MultipartFile;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleVisionOcrService {

    @Value("${curriculum-import.ocr.credentials-file:}")
    private String credentialsFile;

    /**
     * Extracts text from multiple image URLs using Google Cloud Vision API.
     * The text is concatenated in the order of the URLs.
     */
    public String extractTextFromImages(List<String> imageUrls) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return "";
        }

        StringBuilder combinedText = new StringBuilder();
        
        try (ImageAnnotatorClient client = buildClient()) {
            for (int i = 0; i < imageUrls.size(); i++) {
                String imgUrl = imageUrls.get(i);
                try {
                    String extractedText = extractTextFromSingleUrl(client, imgUrl);
                    if (!extractedText.isBlank()) {
                        combinedText.append("[Trang ").append(i + 1).append("]\n");
                        combinedText.append(extractedText).append("\n\n");
                    }
                } catch (Exception e) {
                    log.error("Failed to extract text from image URL: {}", imgUrl, e);
                }
            }
        } catch (IOException e) {
            log.error("Failed to initialize Google Vision ImageAnnotatorClient", e);
        }

        return combinedText.toString().trim();
    }

    /**
     * Extracts text from multiple MultipartFile images using Google Cloud Vision API.
     */
    public String extractTextFromFiles(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return "";
        }

        StringBuilder combinedText = new StringBuilder();

        try (ImageAnnotatorClient client = buildClient()) {
            for (int i = 0; i < files.size(); i++) {
                MultipartFile file = files.get(i);
                try {
                    String extractedText = extractTextFromSingleFile(client, file);
                    if (!extractedText.isBlank()) {
                        combinedText.append("[Trang ").append(i + 1).append("]\n");
                        combinedText.append(extractedText).append("\n\n");
                    }
                } catch (Exception e) {
                    log.error("Failed to extract text from file: {}", file.getOriginalFilename(), e);
                }
            }
        } catch (IOException e) {
            log.error("Failed to initialize Google Vision ImageAnnotatorClient", e);
        }

        return combinedText.toString().trim();
    }

    private ImageAnnotatorClient buildClient() throws IOException {
        if (credentialsFile == null || credentialsFile.isBlank()) {
            log.debug("Google Vision: using Application Default Credentials (ADC)");
            return ImageAnnotatorClient.create();
        }

        log.debug("Google Vision: loading credentials from {}", credentialsFile);

        com.google.auth.oauth2.GoogleCredentials credentials;
        try (InputStream fis = new FileInputStream(credentialsFile)) {
            credentials = com.google.auth.oauth2.GoogleCredentials.fromStream(fis);
        }

        ImageAnnotatorSettings settings = ImageAnnotatorSettings.newBuilder()
                .setCredentialsProvider(com.google.api.gax.core.FixedCredentialsProvider.create(credentials))
                .build();

        return ImageAnnotatorClient.create(settings);
    }

    private String extractTextFromSingleUrl(ImageAnnotatorClient client, String imageUrl) throws IOException {
        ByteString imgBytes;
        try (InputStream in = new URL(imageUrl).openStream()) {
            imgBytes = ByteString.readFrom(in);
        }

        return callVisionApi(client, imgBytes);
    }

    private String extractTextFromSingleFile(ImageAnnotatorClient client, MultipartFile file) throws IOException {
        ByteString imgBytes;
        try (InputStream in = file.getInputStream()) {
            imgBytes = ByteString.readFrom(in);
        }

        return callVisionApi(client, imgBytes);
    }

    private String callVisionApi(ImageAnnotatorClient client, ByteString imgBytes) {
        Image img = Image.newBuilder().setContent(imgBytes).build();
        Feature feat = Feature.newBuilder().setType(Feature.Type.DOCUMENT_TEXT_DETECTION).build();
        AnnotateImageRequest request = AnnotateImageRequest.newBuilder()
                .addFeatures(feat)
                .setImage(img)
                .build();

        BatchAnnotateImagesResponse response = client.batchAnnotateImages(List.of(request));
        StringBuilder resultText = new StringBuilder();

        for (AnnotateImageResponse res : response.getResponsesList()) {
            if (res.hasError()) {
                log.error("Google Vision Error: {}", res.getError().getMessage());
                continue;
            }
            if (res.getFullTextAnnotation() != null) {
                resultText.append(res.getFullTextAnnotation().getText());
            }
        }

        return resultText.toString();
    }
}

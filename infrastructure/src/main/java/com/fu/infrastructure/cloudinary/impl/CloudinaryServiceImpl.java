package com.fu.infrastructure.cloudinary.impl;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.fu.common.exception.BusinessException;
import com.fu.infrastructure.cloudinary.CloudinaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * Implementation of CloudinaryService using Cloudinary SDK.
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CloudinaryServiceImpl implements CloudinaryService {

    private final Cloudinary cloudinary;

    @Value("${app.cloudinary.enabled:true}")
    private boolean cloudinaryEnabled;

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB
    private static final long MAX_AUDIO_FILE_SIZE = 10 * 1024 * 1024; // 10MB

    @Override
    public String uploadImage(MultipartFile file, String folder) {
        if (!cloudinaryEnabled) {
            log.info("[CLOUDINARY DISABLED] Would upload file: {} to folder: {}", file.getOriginalFilename(), folder);
            return "https://placeholder.co/600x400?text=Cloudinary+Disabled";
        }

        validateImageFile(file);

        try {
            log.info("Uploading image to Cloudinary folder: {}", folder);

            Map<?, ?> uploadResult = cloudinary.uploader().upload(file.getBytes(), ObjectUtils.asMap(
                    "folder", folder,
                    "resource_type", "image",
                    "overwrite", true
            ));

            String secureUrl = (String) uploadResult.get("secure_url");
            log.info("Image uploaded successfully: {}", secureUrl);

            return secureUrl;
        } catch (IOException e) {
            log.error("Failed to upload image to Cloudinary", e);
            throw new BusinessException("UPLOAD_FAILED", "Failed to upload image: " + e.getMessage());
        }
    }

    @Override
    public void deleteImage(String publicId) {
        if (!cloudinaryEnabled) {
            log.info("[CLOUDINARY DISABLED] Would delete image with public ID: {}", publicId);
            return;
        }

        try {
            log.info("Deleting image from Cloudinary: {}", publicId);

            Map<?, ?> result = cloudinary.uploader().destroy(publicId, ObjectUtils.emptyMap());

            String deleteResult = (String) result.get("result");
            if ("ok".equals(deleteResult)) {
                log.info("Image deleted successfully: {}", publicId);
            } else {
                log.warn("Image deletion returned unexpected result: {} for publicId: {}", deleteResult, publicId);
            }
        } catch (IOException e) {
            log.error("Failed to delete image from Cloudinary: {}", publicId, e);
            // Don't throw exception for deletion failures - log and continue
        }
    }

    @Override
    public String extractPublicId(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }

        try {
            // Cloudinary URL format: https://res.cloudinary.com/{cloud}/image/upload/v{version}/{public_id}.{ext}
            String[] parts = url.split("/upload/");
            if (parts.length < 2) {
                return null;
            }
            String afterUpload = parts[1];
            // Remove version prefix (v1234567890/)
            if (afterUpload.matches("^v\\d+/.*")) {
                afterUpload = afterUpload.substring(afterUpload.indexOf('/') + 1);
            }
            // Remove file extension
            int lastDot = afterUpload.lastIndexOf('.');
            if (lastDot > 0) {
                afterUpload = afterUpload.substring(0, lastDot);
            }
            return afterUpload;
        } catch (Exception e) {
            log.warn("Failed to extract public ID from URL: {}", url, e);
            return null;
        }
    }

    /**
     * Validate that the file is a valid image.
     */
    private void validateImageFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("INVALID_FILE", "File is required and must not be empty");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException("FILE_TOO_LARGE", "File size must not exceed 5MB");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BusinessException("INVALID_FILE_TYPE", "Only image files are allowed (JPEG, PNG, GIF, WebP)");
        }
    }

    @Override
    public String uploadAudio(MultipartFile file, String folder) {
        if (!cloudinaryEnabled) {
            log.info("[CLOUDINARY DISABLED] Would upload audio: {} to folder: {}", file.getOriginalFilename(), folder);
            return "https://placeholder.co/audio?text=Cloudinary+Disabled";
        }

        validateAudioFile(file);

        try {
            log.info("Uploading audio to Cloudinary folder: {}", folder);

            Map<?, ?> uploadResult = cloudinary.uploader().upload(file.getBytes(), ObjectUtils.asMap(
                    "folder", folder,
                    "resource_type", "video",
                    "overwrite", true
            ));

            String secureUrl = (String) uploadResult.get("secure_url");
            log.info("Audio uploaded successfully: {}", secureUrl);

            return secureUrl;
        } catch (IOException e) {
            log.error("Failed to upload audio to Cloudinary", e);
            throw new BusinessException("UPLOAD_FAILED", "Failed to upload audio: " + e.getMessage());
        }
    }

    private void validateAudioFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("INVALID_FILE", "Audio file is required and must not be empty");
        }

        if (file.getSize() > MAX_AUDIO_FILE_SIZE) {
            throw new BusinessException("FILE_TOO_LARGE", "Audio file size must not exceed 10MB");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("audio/")) {
            throw new BusinessException("INVALID_FILE_TYPE", "Only audio files are allowed (WAV, MP3, OGG, WebM, M4A)");
        }
    }
}


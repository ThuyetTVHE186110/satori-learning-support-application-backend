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
import java.util.Arrays;
import java.util.List;
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
            log.info("Đang tải ảnh lên thư mục Cloudinary: {}", folder);

            Map<?, ?> uploadResult = cloudinary.uploader().upload(file.getBytes(), ObjectUtils.asMap(
                    "folder", folder,
                    "resource_type", "image",
                    "overwrite", true
            ));

            String secureUrl = (String) uploadResult.get("secure_url");
            log.info("Tải ảnh lên thành công: {}", secureUrl);

            return secureUrl;
        } catch (IOException e) {
            log.error("Tải ảnh lên Cloudinary thất bại", e);
            throw new BusinessException("UPLOAD_FAILED", "Tải ảnh lên thất bại: " + e.getMessage());
        }
    }

    @Override
    public void deleteImage(String publicId) {
        if (!cloudinaryEnabled) {
            log.info("[CLOUDINARY DISABLED] Would delete image with public ID: {}", publicId);
            return;
        }
        try {
            log.info("Đang xóa ảnh khỏi Cloudinary: {}", publicId);

            Map<?, ?> result = cloudinary.uploader().destroy(publicId, ObjectUtils.emptyMap());

            String deleteResult = (String) result.get("result");
            if ("ok".equals(deleteResult)) {
                log.info("Xóa ảnh thành công: {}", publicId);
            } else {
                log.warn("Việc xóa ảnh trả về kết quả không mong đợi: {} cho publicId: {}", deleteResult, publicId);
            }
        } catch (IOException e) {
            log.error("Xóa ảnh khỏi Cloudinary thất bại: {}", publicId, e);
            // Don't throw exception for deletion failures - log and continue
        }
    }

    @Override
    public void deleteAudio(String publicId) {
        if (!cloudinaryEnabled) {
            log.info("[CLOUDINARY DISABLED] Would delete audio with public ID: {}", publicId);
            return;
        }
        try {
            log.info("Đang xóa âm thanh khỏi Cloudinary: {}", publicId);

            Map<?, ?> result = cloudinary.uploader().destroy(publicId,
                    ObjectUtils.asMap("resource_type", "video"));

            String deleteResult = (String) result.get("result");
            if ("ok".equals(deleteResult)) {
                log.info("Xóa âm thanh thành công: {}", publicId);
            } else {
                log.warn("Việc xóa âm thanh trả về kết quả không mong đợi: {} cho publicId: {}", deleteResult, publicId);
            }
        } catch (IOException e) {
            log.error("Xóa âm thanh khỏi Cloudinary thất bại: {}", publicId, e);
            // Don't throw — deletion failure should not block the main flow
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
            log.warn("Trích xuất public ID từ URL thất bại: {}", url, e);
            return null;
        }
    }

    /**
     * Validate that the file is a valid image.
     */
    private void validateImageFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("INVALID_FILE", "Tệp là bắt buộc và không được để trống");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException("FILE_TOO_LARGE", "Kích thước tệp không được vượt quá 5MB");
        }

        String fileName = file.getOriginalFilename();

        // Danh sách các đuôi file ảnh cho phép
        List<String> allowedExtensions = Arrays.asList("jpg", "jpeg", "png", "gif", "webp", "heic", "heif");


        if (fileName == null || allowedExtensions.stream().noneMatch(ext -> fileName.toLowerCase().endsWith("." + ext))) {
            throw new BusinessException("INVALID_FILE_TYPE", "Chỉ cho phép các định dạng ảnh JPEG, PNG, GIF, WebP, HEIC, HEIF, JPG");
        }
    }

    @Override
    public String uploadAudio(MultipartFile file, String folder) {
        if (!cloudinaryEnabled) {
            log.info("[CLOUDINARY ĐANG TẮT] Sẽ tải âm thanh: {} lên thư mục: {}", file.getOriginalFilename(), folder);
            return "https://placeholder.co/audio?text=Cloudinary+Dang+Tat";
        }

        validateAudioFile(file);

        try {
            log.info("Đang tải âm thanh lên thư mục Cloudinary: {}", folder);

            Map<?, ?> uploadResult = cloudinary.uploader().upload(file.getBytes(), ObjectUtils.asMap(
                    "folder", folder,
                    "resource_type", "video",
                    "overwrite", true
            ));

            String secureUrl = (String) uploadResult.get("secure_url");
            log.info("Tải âm thanh lên thành công: {}", secureUrl);

            return secureUrl;
        } catch (IOException e) {
            log.error("Tải âm thanh lên Cloudinary thất bại", e);
            throw new BusinessException("UPLOAD_FAILED", "Tải âm thanh lên thất bại: " + e.getMessage());
        }
    }

    private void validateAudioFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("INVALID_FILE", "Tệp âm thanh là bắt buộc và không được để trống");
        }

        if (file.getSize() > MAX_AUDIO_FILE_SIZE) {
            throw new BusinessException("FILE_TOO_LARGE", "Kích thước tệp âm thanh không được vượt quá 10MB");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("audio/")) {
            throw new BusinessException("INVALID_FILE_TYPE", "Chỉ cho phép các tệp âm thanh (WAV, MP3, OGG, WebM, M4A)");
        }
    }
}


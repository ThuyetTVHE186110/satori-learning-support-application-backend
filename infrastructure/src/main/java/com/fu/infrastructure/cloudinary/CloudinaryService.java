package com.fu.infrastructure.cloudinary;

import org.springframework.web.multipart.MultipartFile;

/**
 * Cloudinary Service Interface.
 * Provides image upload and deletion capabilities for the application.
 * Used by course management for thumbnail uploads, etc.
 *
 */
public interface CloudinaryService {

    /**
     * Upload an image to Cloudinary.
     *
     * @param file   the image file to upload
     * @param folder the Cloudinary folder to store the image in
     * @return the secure URL of the uploaded image
     */
    String uploadImage(MultipartFile file, String folder);

    /**
     * Delete an image from Cloudinary by its public ID.
     *
     * @param publicId the Cloudinary public ID of the image
     */
    void deleteImage(String publicId);

    /**
     * Extract the public ID from a Cloudinary URL.
     *
     * @param url the Cloudinary image URL
     * @return the extracted public ID, or null if extraction fails
     */
    String extractPublicId(String url);

    /**
     * Upload an audio file to Cloudinary.
     * Uses resource_type "video" (Cloudinary convention for audio files).
     *
     * @param file   the audio file to upload
     * @param folder the Cloudinary folder to store the audio in
     * @return the secure URL of the uploaded audio
     */
    String uploadAudio(MultipartFile file, String folder);
}

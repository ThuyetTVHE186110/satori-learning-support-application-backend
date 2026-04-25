package com.fu.course.service;

import com.fu.course.dto.request.CreateVocabularyRequest;
import com.fu.course.dto.request.UpdateVocabularyRequest;
import com.fu.course.dto.response.VocabularyResponse;
import com.fu.course.entity.Course.JlptLevel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

/**
 * Service interface for Vocabulary management.
 */
public interface VocabularyService {

    // ===== CRUD =====

    /**
     * Create a new vocabulary entry (MANAGER only).
     */
    VocabularyResponse createVocabulary(CreateVocabularyRequest request, UUID managerId);

    /**
     * Update an existing vocabulary entry (MANAGER only).
     * Only non-null fields in the request are applied.
     */
    VocabularyResponse updateVocabulary(UUID vocabularyId, UpdateVocabularyRequest request, UUID managerId);

    /**
     * Get vocabulary by ID.
     */
    VocabularyResponse getVocabularyById(UUID vocabularyId);

    /**
     * Soft-delete a vocabulary entry (MANAGER only).
     */
    void deleteVocabulary(UUID vocabularyId, UUID managerId);

    /**
     * Remove the link between a lesson and a vocabulary entry without affecting
     * the vocabulary itself. Other lessons that reference this vocabulary keep it.
     */
    void unlinkVocabularyFromLesson(UUID lessonId, UUID vocabularyId, UUID managerId);

    // ===== Query =====

    /**
     * Get all vocabulary, optionally filtered by JLPT level.
     */
    Page<VocabularyResponse> getAllVocabulary(JlptLevel jlptLevel, Pageable pageable);

    /**
     * Get all vocabulary linked to a specific lesson.
     */
    List<VocabularyResponse> getVocabularyByLesson(UUID lessonId);

    /**
     * Search vocabulary by keyword (text, reading, meaning).
     */
    Page<VocabularyResponse> searchVocabulary(String keyword, Pageable pageable);
}

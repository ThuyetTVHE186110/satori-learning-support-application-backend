package com.fu.course.service;

import com.fu.course.dto.request.CreateGrammarPointRequest;
import com.fu.course.dto.request.UpdateGrammarPointRequest;
import com.fu.course.dto.response.GrammarPointResponse;
import com.fu.course.entity.Course.JlptLevel;
import com.fu.course.entity.GrammarPoint.GrammarCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

/**
 * Service interface for GrammarPoint management.
 */
public interface GrammarPointService {

    // ===== CRUD =====

    /**
     * Create a new grammar point (MANAGER only).
     */
    GrammarPointResponse createGrammarPoint(CreateGrammarPointRequest request, UUID managerId);

    /**
     * Update an existing grammar point (MANAGER only).
     * Only non-null fields in the request are applied.
     */
    GrammarPointResponse updateGrammarPoint(UUID grammarPointId, UpdateGrammarPointRequest request, UUID managerId);

    /**
     * Get grammar point by ID.
     */
    GrammarPointResponse getGrammarPointById(UUID grammarPointId);

    /**
     * Soft-delete a grammar point (MANAGER only).
     */
    void deleteGrammarPoint(UUID grammarPointId, UUID managerId);

    /**
     * Remove the link between a lesson and a grammar point without affecting the
     * grammar point itself. Other lessons that reference it keep it.
     */
    void unlinkGrammarFromLesson(UUID lessonId, UUID grammarPointId, UUID managerId);

    // ===== Query =====

    /**
     * Get all grammar points, optionally filtered by JLPT level and/or category.
     */
    Page<GrammarPointResponse> getAllGrammarPoints(JlptLevel jlptLevel, GrammarCategory category, Pageable pageable);

    /**
     * Get all grammar points linked to a specific lesson.
     */
    List<GrammarPointResponse> getGrammarPointsByLesson(UUID lessonId);

    /**
     * Search grammar points by keyword (pattern or meaning).
     */
    Page<GrammarPointResponse> searchGrammarPoints(String keyword, Pageable pageable);
}

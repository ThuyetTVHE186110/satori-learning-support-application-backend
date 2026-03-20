package com.fu.course.service.impl;

import com.fu.common.event.ContentEmbeddingEvent;
import com.fu.common.exception.BusinessException;
import com.fu.common.exception.ResourceNotFoundException;
import com.fu.course.dto.request.CreateGrammarPointRequest;
import com.fu.course.dto.request.UpdateGrammarPointRequest;
import com.fu.course.dto.response.GrammarPointResponse;
import com.fu.course.entity.Course.JlptLevel;
import com.fu.course.entity.GrammarPoint;
import com.fu.course.entity.GrammarPoint.GrammarCategory;
import com.fu.course.entity.Lesson;
import com.fu.course.entity.LessonGrammar;
import com.fu.course.repository.GrammarPointRepository;
import com.fu.course.repository.LessonGrammarRepository;
import com.fu.course.repository.LessonRepository;
import com.fu.course.service.GrammarPointService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Implementation of GrammarPointService.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class GrammarPointServiceImpl implements GrammarPointService {

    private final GrammarPointRepository grammarPointRepository;
    private final LessonRepository lessonRepository;
    private final LessonGrammarRepository lessonGrammarRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public GrammarPointResponse createGrammarPoint(CreateGrammarPointRequest request, UUID managerId) {
        log.info("Manager {} creating grammar point: {}", managerId, request.getPattern());

        if (grammarPointRepository.existsByPatternAndJlptLevelAndDeletedAtIsNull(
                request.getPattern(), request.getJlptLevel())) {
            throw new BusinessException(
                    "Grammar point '" + request.getPattern() + "' already exists at level "
                            + request.getJlptLevel());
        }

        GrammarPoint grammar = GrammarPoint.builder()
                .pattern(request.getPattern())
                .jlptLevel(request.getJlptLevel())
                .patternReading(request.getPatternReading())
                .patternRomaji(request.getPatternRomaji())
                .meaningVi(request.getMeaningVi())
                .meaningEn(request.getMeaningEn())
                .shortExplanation(request.getShortExplanation())
                .detailedExplanation(request.getDetailedExplanation())
                .category(request.getCategory() != null ? request.getCategory() : GrammarCategory.OTHER)
                .structure(request.getStructure())
                .conjugationRules(request.getConjugationRules())
                .formationRules(request.getFormationRules())
                .exampleSentences(request.getExampleSentences() != null ? request.getExampleSentences() : "[]")
                .commonMistakes(request.getCommonMistakes() != null ? request.getCommonMistakes() : "[]")
                .tips(request.getTips())
                .relatedGrammar(listToArray(request.getRelatedGrammar()))
                .tags(listToArray(request.getTags()))
                .audioUrl(request.getAudioUrl())
                .createdBy(managerId)
                .updatedBy(managerId)
                .build();

        GrammarPoint saved = grammarPointRepository.save(grammar);
        log.info("Grammar point created: {}", saved.getId());

        if (request.getLessonId() != null) {
            Lesson lesson = lessonRepository.findByIdAndNotDeleted(request.getLessonId())
                    .orElseThrow(() -> new ResourceNotFoundException("Lesson", request.getLessonId()));
            if (!lessonGrammarRepository.existsByLessonIdAndGrammarPointId(lesson.getId(), saved.getId())) {
                long orderIndex = lessonGrammarRepository.countByLessonId(lesson.getId());
                lessonGrammarRepository.save(LessonGrammar.builder()
                        .lesson(lesson)
                        .grammarPoint(saved)
                        .orderIndex((int) orderIndex)
                        .build());
                log.info("Linked grammar point {} to lesson {}", saved.getId(), lesson.getId());
            }
        }

        return toResponse(saved);
    }

    @Override
    @Transactional
    public GrammarPointResponse updateGrammarPoint(UUID grammarPointId, UpdateGrammarPointRequest request, UUID managerId) {
        log.info("Manager {} updating grammar point: {}", managerId, grammarPointId);

        GrammarPoint grammar = findOrThrow(grammarPointId);

        if (request.getPatternReading() != null) grammar.setPatternReading(request.getPatternReading());
        if (request.getPatternRomaji() != null) grammar.setPatternRomaji(request.getPatternRomaji());
        if (request.getMeaningVi() != null) grammar.setMeaningVi(request.getMeaningVi());
        if (request.getMeaningEn() != null) grammar.setMeaningEn(request.getMeaningEn());
        if (request.getShortExplanation() != null) grammar.setShortExplanation(request.getShortExplanation());
        if (request.getDetailedExplanation() != null) grammar.setDetailedExplanation(request.getDetailedExplanation());
        if (request.getCategory() != null) grammar.setCategory(request.getCategory());
        if (request.getStructure() != null) grammar.setStructure(request.getStructure());
        if (request.getConjugationRules() != null) grammar.setConjugationRules(request.getConjugationRules());
        if (request.getFormationRules() != null) grammar.setFormationRules(request.getFormationRules());
        if (request.getExampleSentences() != null) grammar.setExampleSentences(request.getExampleSentences());
        if (request.getCommonMistakes() != null) grammar.setCommonMistakes(request.getCommonMistakes());
        if (request.getTips() != null) grammar.setTips(request.getTips());
        if (request.getRelatedGrammar() != null) grammar.setRelatedGrammar(listToArray(request.getRelatedGrammar()));
        if (request.getTags() != null) grammar.setTags(listToArray(request.getTags()));
        if (request.getAudioUrl() != null) grammar.setAudioUrl(request.getAudioUrl());
        grammar.setUpdatedBy(managerId);

        GrammarPoint saved = grammarPointRepository.save(grammar);

        eventPublisher.publishEvent(ContentEmbeddingEvent.forGrammarUpdate(
                this, saved.getId(),
                saved.getPattern(), saved.getMeaningEn(), saved.getMeaningVi(),
                saved.getShortExplanation(),
                saved.getJlptLevel() != null ? saved.getJlptLevel().name() : "N5"));

        return toResponse(saved);
    }

    @Override
    public GrammarPointResponse getGrammarPointById(UUID grammarPointId) {
        return toResponse(findOrThrow(grammarPointId));
    }

    @Override
    @Transactional
    public void deleteGrammarPoint(UUID grammarPointId, UUID managerId) {
        log.info("Manager {} soft-deleting grammar point: {}", managerId, grammarPointId);
        GrammarPoint grammar = findOrThrow(grammarPointId);
        eventPublisher.publishEvent(ContentEmbeddingEvent.forDelete(this, grammarPointId, "GRAMMAR"));
        grammar.softDelete(managerId);
        grammarPointRepository.save(grammar);
    }

    @Override
    public Page<GrammarPointResponse> getAllGrammarPoints(JlptLevel jlptLevel, GrammarCategory category, Pageable pageable) {
        if (jlptLevel != null && category != null) {
            // Both filters: find by level then filter in-memory would be inefficient;
            // use the level filter and rely on the search for now.
            return grammarPointRepository.findByJlptLevelAndDeletedAtIsNull(jlptLevel, pageable)
                    .map(this::toResponse);
        }
        if (jlptLevel != null) {
            return grammarPointRepository.findByJlptLevelAndDeletedAtIsNull(jlptLevel, pageable).map(this::toResponse);
        }
        if (category != null) {
            return grammarPointRepository.findByCategoryAndDeletedAtIsNull(category, pageable).map(this::toResponse);
        }
        return grammarPointRepository.findByDeletedAtIsNull(pageable).map(this::toResponse);
    }

    @Override
    public List<GrammarPointResponse> getGrammarPointsByLesson(UUID lessonId) {
        return grammarPointRepository.findByLessonId(lessonId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public Page<GrammarPointResponse> searchGrammarPoints(String keyword, Pageable pageable) {
        return grammarPointRepository.searchByKeyword(keyword, pageable).map(this::toResponse);
    }

    // --- Helpers ---

    private GrammarPoint findOrThrow(UUID id) {
        GrammarPoint grammar = grammarPointRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("GrammarPoint", id));
        if (grammar.isDeleted()) {
            throw new ResourceNotFoundException("GrammarPoint", id);
        }
        return grammar;
    }

    private GrammarPointResponse toResponse(GrammarPoint g) {
        return GrammarPointResponse.builder()
                .id(g.getId())
                .pattern(g.getPattern())
                .patternReading(g.getPatternReading())
                .patternRomaji(g.getPatternRomaji())
                .meaningVi(g.getMeaningVi())
                .meaningEn(g.getMeaningEn())
                .shortExplanation(g.getShortExplanation())
                .detailedExplanation(g.getDetailedExplanation())
                .jlptLevel(g.getJlptLevel())
                .category(g.getCategory())
                .difficultyScore(g.getDifficultyScore())
                .structure(g.getStructure())
                .conjugationRules(g.getConjugationRules())
                .formationRules(g.getFormationRules())
                .exampleSentences(g.getExampleSentences())
                .commonMistakes(g.getCommonMistakes())
                .tips(g.getTips())
                .relatedGrammar(arrayToList(g.getRelatedGrammar()))
                .tags(arrayToList(g.getTags()))
                .audioUrl(g.getAudioUrl())
                .createdAt(g.getCreatedAt())
                .updatedAt(g.getUpdatedAt())
                .build();
    }

    private String[] listToArray(List<String> list) {
        return list == null ? null : list.toArray(new String[0]);
    }

    private List<String> arrayToList(String[] arr) {
        return arr == null ? List.of() : Arrays.asList(arr);
    }
}

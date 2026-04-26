package com.fu.course.service.impl;

import com.fu.infrastructure.event.ContentEmbeddingEvent;
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

        Lesson lesson = null;
        if (request.getLessonId() != null) {
            lesson = lessonRepository.findByIdAndNotDeleted(request.getLessonId())
                    .orElseThrow(() -> new ResourceNotFoundException("Lesson", request.getLessonId()));

            if (lesson.getCourse() != null && lesson.getCourse().getJlptLevel() != null) {
                JlptLevel courseLevel = lesson.getCourse().getJlptLevel();
                if (request.getJlptLevel() != null && !courseLevel.equals(request.getJlptLevel())) {
                    throw new BusinessException("JLPT level of grammar point must match the course level (" + courseLevel + ")");
                }
                request.setJlptLevel(courseLevel);
            }
        }

        GrammarPoint grammarPoint = grammarPointRepository
                .findByPatternAndJlptLevelAndDeletedAtIsNull(request.getPattern(), request.getJlptLevel())
                .orElseGet(() -> {
                    GrammarPoint newGp = GrammarPoint.builder()
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
                    return grammarPointRepository.save(newGp);
                });

        if (lesson != null) {
            boolean existsInLesson = lessonGrammarRepository.existsByLessonIdAndGrammarPointId(lesson.getId(), grammarPoint.getId());
            if (existsInLesson) {
                throw new BusinessException("Grammar point '" + request.getPattern() + "' already exists in this lesson");
            }

            long orderIndex = lessonGrammarRepository.countByLessonId(lesson.getId());
            lessonGrammarRepository.save(LessonGrammar.builder()
                    .lesson(lesson)
                    .grammarPoint(grammarPoint)
                    .orderIndex((int) orderIndex)
                    .build());

            log.info("Linked grammar point {} to lesson {}", grammarPoint.getId(), lesson.getId());
        }

        return toResponse(grammarPoint);
    }

    @Override
    @Transactional
    public GrammarPointResponse updateGrammarPoint(UUID grammarPointId, UpdateGrammarPointRequest request, UUID managerId) {
        log.info("Manager {} updating grammar point: {}", managerId, grammarPointId);

        GrammarPoint grammar = findOrThrow(grammarPointId);

        if (request.getPattern() != null || request.getJlptLevel() != null) {
            String newPattern = request.getPattern() != null ? request.getPattern() : grammar.getPattern();
            JlptLevel newLevel = request.getJlptLevel() != null ? request.getJlptLevel() : grammar.getJlptLevel();
            boolean patternChanged = !newPattern.equals(grammar.getPattern()) || newLevel != grammar.getJlptLevel();
            if (patternChanged && grammarPointRepository.existsByPatternAndJlptLevelAndDeletedAtIsNull(newPattern, newLevel)) {
                throw new BusinessException(
                        "Grammar point '" + newPattern + "' already exists at level " + newLevel);
            }
            if (request.getPattern() != null) grammar.setPattern(request.getPattern());
            if (request.getJlptLevel() != null) grammar.setJlptLevel(request.getJlptLevel());
        }

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
        // Hard-delete junctions first so list counts and lesson detail stay consistent
        // and we don't leave dangling rows pointing to a tombstone grammar point.
        lessonGrammarRepository.deleteByGrammarPointId(grammarPointId);
        eventPublisher.publishEvent(ContentEmbeddingEvent.forDelete(this, grammarPointId, "GRAMMAR"));
        grammar.softDelete(managerId);
        grammarPointRepository.save(grammar);
    }

    @Override
    @Transactional
    public void unlinkGrammarFromLesson(UUID lessonId, UUID grammarPointId, UUID managerId) {
        log.info("Manager {} unlinking grammar {} from lesson {}", managerId, grammarPointId, lessonId);
        lessonRepository.findByIdAndNotDeleted(lessonId)
                .orElseThrow(() -> new ResourceNotFoundException("Lesson", lessonId));
        int removed = lessonGrammarRepository.deleteByLessonIdAndGrammarPointId(lessonId, grammarPointId);
        if (removed == 0) {
            throw new ResourceNotFoundException(
                    "LessonGrammar link (lesson=" + lessonId + ", grammar=" + grammarPointId + ")", grammarPointId);
        }
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
